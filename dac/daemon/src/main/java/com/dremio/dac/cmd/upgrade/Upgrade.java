/*
 * Copyright (C) 2017-2018 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.dac.cmd.upgrade;

import static com.dremio.common.util.DremioVersionInfo.VERSION;
import static com.dremio.dac.util.ClusterVersionUtils.fromClusterVersion;
import static com.dremio.dac.util.ClusterVersionUtils.toClusterVersion;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.dremio.common.Version;
import com.dremio.common.config.LogicalPlanPersistence;
import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.scanner.ClassPathScanner;
import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.dac.cmd.CmdUtils;
import com.dremio.dac.proto.model.source.ClusterIdentity;
import com.dremio.dac.proto.model.source.UpgradeStatus;
import com.dremio.dac.proto.model.source.UpgradeTaskRun;
import com.dremio.dac.proto.model.source.UpgradeTaskStore;
import com.dremio.dac.server.DACConfig;
import com.dremio.dac.support.SupportService;
import com.dremio.dac.support.UpgradeStore;
import com.dremio.datastore.KVStoreProvider;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.exec.catalog.ConnectionReader;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/**
 * Upgrade command.
 *
 * Extracts store version and uses it to decide if upgrade is possible and which tasks should be executed.
 * If no version is found, the tool assumes it's 1.0.6 as there is no way to identify versions prior to that anyway
 * Adding ability to store task state in KVStore itself. It allows:
 * 1. Not to repeat task run if already run, but upgrade aborted/stopped in between
 * 2. Repeat task run is it was not successful
 */
public class Upgrade {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Upgrade.class);

  /**
   * A {@code Version} ordering ignoring qualifiers for the sake of upgrade
   */
  public static final Comparator<Version> UPGRADE_VERSION_ORDERING = Comparator
      .comparing(Version::getMajorVersion)
      .thenComparing(Version::getMinorVersion)
      .thenComparing(Version::getPatchVersion)
      .thenComparing(Version::getBuildNumber);



  private final DACConfig dacConfig;
  private final ScanResult classpathScan;
  private final boolean verbose;

  private final List<? extends UpgradeTask> upgradeTasks;

  public Upgrade(DACConfig dacConfig, ScanResult classPathScan, boolean verbose) {
    this.dacConfig = dacConfig;
    this.classpathScan = classPathScan;
    this.verbose = verbose;

    // Get all the upgrade tasks present in the classpath, correctly ordered
    List<? extends UpgradeTask> allTasks = classPathScan.getImplementations(UpgradeTask.class).stream()
      .map(clazz -> {
        // All upgrade tasks should be valid classes accessible from Upgrade with a no-arg constructor
        try {
          final Constructor<? extends UpgradeTask> constructor = clazz.getConstructor();
          return constructor.newInstance();
        } catch (NoSuchMethodException e) {
          if (verbose) {
            System.out.printf("Ignoring class without public no-arg constructor %s.%n", clazz.getSimpleName());
          }
        } catch (InstantiationException e) {
          if (verbose && clazz != UpgradeTask.class) {
            System.out.printf("Ignoring abstract class %s.%n", clazz.getSimpleName());
          }
        } catch (IllegalAccessException e) {
          if (verbose) {
            System.out.printf("Ignoring class without public constructor %s.%n", clazz.getSimpleName());
          }
        } catch (InvocationTargetException e) {
          if (verbose) {
            System.out.printf("Ignoring class %s (failed during instantiation with message %s).%n",
                clazz.getSimpleName(), e.getTargetException().getMessage());
          }
        }
        return null;
      })
      // Filter out null values
      .filter(Objects::nonNull)
      .collect(Collectors.toList());

    final UpgradeTaskDependencyResolver upgradeTaskDependencyResolver =
      new UpgradeTaskDependencyResolver(allTasks);
    this.upgradeTasks = upgradeTaskDependencyResolver.topologicalTasksSort();
  }

  protected DACConfig getDACConfig() {
    return dacConfig;
  }

  @VisibleForTesting
  List<? extends UpgradeTask> getUpgradeTasks() {
    return upgradeTasks;
  }

  private static Version retrieveStoreVersion(ClusterIdentity identity) {
    final Version storeVersion = fromClusterVersion(identity.getVersion());
    return storeVersion != null ? storeVersion : LegacyUpgradeTask.VERSION_106;
  }

  protected void ensureUpgradeSupported(Version storeVersion) {
    // make sure we are not trying to downgrade
    Preconditions.checkState(UPGRADE_VERSION_ORDERING.compare(storeVersion, VERSION) <= 0,
      "Downgrading from version %s to %s is not supported", storeVersion, VERSION);
  }


  public void run() throws Exception {
    Optional<LocalKVStoreProvider> storeOptional = CmdUtils.getKVStoreProvider(dacConfig.getConfig(), classpathScan);
    if (!storeOptional.isPresent()) {
      System.out.println("No database found. Skipping upgrade");
      return;
    }
    try (final KVStoreProvider storeProvider = storeOptional.get()) {
      storeProvider.start();

      run(storeProvider);
    }

  }

  public void run(final KVStoreProvider storeProvider) throws Exception {
    final Optional<ClusterIdentity> identity = SupportService.getClusterIdentity(storeProvider);
    final UpgradeStore upgradeStore = new UpgradeStore(storeProvider);

    if (!identity.isPresent()) {
      throw UserException.validationError().message("No Cluster Identity found").build(logger);
    }

    ClusterIdentity clusterIdentity = identity.get();

    final Version kvStoreVersion = retrieveStoreVersion(clusterIdentity);
    System.out.println("KVStore version is " + kvStoreVersion.getVersion());
    ensureUpgradeSupported(kvStoreVersion);

    System.out.println("\n Upgrade Tasks Status before Upgrade");
    System.out.println(upgradeStore.toString());
    System.out.println();

    List<UpgradeTask> tasksToRun = new ArrayList<>();
    for(UpgradeTask task: upgradeTasks) {
      if (upgradeStore.isUpgradeTaskCompleted(task.getTaskUUID())) {
        if (verbose) {
          System.out.println("Task: '" + task + "' completed. Skipping.");
        }
        continue;
      }
      tasksToRun.add(task);
    }

    if (!tasksToRun.isEmpty()) {
      final SabotConfig sabotConfig = dacConfig.getConfig().getSabotConfig();
      final LogicalPlanPersistence lpPersistence = new LogicalPlanPersistence(sabotConfig, classpathScan);
      final ConnectionReader connectionReader = ConnectionReader.of(classpathScan, sabotConfig);

      final UpgradeContext context = new UpgradeContext(storeProvider, lpPersistence, connectionReader);

      for (UpgradeTask task : tasksToRun) {
        System.out.println(task);
        upgradeExternal(task, context, upgradeStore, kvStoreVersion);
      }
    }

    try {
      clusterIdentity.setVersion(toClusterVersion(VERSION));
      SupportService.updateClusterIdentity(storeProvider, clusterIdentity);
    } catch (Throwable e) {
      throw new RuntimeException("Failed to update store version", e);
    }

    System.out.println("\n Upgrade Tasks Status after Upgrade");
    System.out.println(upgradeStore.toString());
    System.out.println();
  }

  /**
   * To upgrade with update of UpgradeStore
   * @param context
   * @param kvStoreVersion
   * @throws Exception
   */
  static void upgradeExternal(UpgradeTask upgradeTask, UpgradeContext context,
                              UpgradeStore upgradeStore, Version kvStoreVersion) throws
    Exception {
    if (!proceedWithUpgrade(upgradeTask, upgradeStore, kvStoreVersion)) {
      return;
    }
    long startTime = System.currentTimeMillis();
    try {
      upgradeTask.upgrade(context);
    } catch (Exception e) {
      try {
        completeUpgradeTaskRun(upgradeTask, upgradeStore, startTime, UpgradeStatus.FAILED);
      } catch (Exception ex) {
        e.addSuppressed(ex);
        System.out.println("Failed to update task '" + upgradeTask.getTaskName() + "' state to FAILED ");
      }
      throw e;
    }
    completeUpgradeTaskRun(upgradeTask, upgradeStore, startTime, UpgradeStatus.COMPLETED);
  }
  /**
   * To check if max version of the task is less then current KVStore version
   * if it is true - there is no point of running this task anymore
   * record it in KVStore, otherwise we will need to proceed with upgrade
   * @param upgradeStore
   * @param kvStoreVersion
   * @return
   * @throws Exception
   */
  private static boolean proceedWithUpgrade(UpgradeTask upgradeTask, UpgradeStore upgradeStore, Version kvStoreVersion) throws
    Exception {

    // proceed with upgrade unless some legacy tasks are in the past
    int compareResult = -1;
    if (upgradeTask instanceof LegacyUpgradeTask) {
      LegacyUpgradeTask legacyTask = (LegacyUpgradeTask) upgradeTask;
      compareResult = UPGRADE_VERSION_ORDERING.compare(kvStoreVersion, legacyTask.getMaxVersion());
    }
    if (compareResult <= 0) {
      return true;
    }
    // we are past max version for which upgrade is relevant
    // just insert entry into upgrade store
    UpgradeTaskRun upgradeTaskRun = new UpgradeTaskRun()
      .setStartTime(System.currentTimeMillis())
      .setEndTime(System.currentTimeMillis())
      .setStatus(UpgradeStatus.OUTDATED);
    upgradeStore.createUpgradeTaskStoreEntry(upgradeTask.getTaskUUID(), upgradeTask.getTaskName(), upgradeTaskRun);
    return false;
  }

  /**
   * Insert entry into UpgradeStore with task completion
   * @param upgradeStore
   * @param startTime
   * @param upgradeStatus
   * @return UpgradeTaskSTore object
   * @throws Exception
   */
  private static UpgradeTaskStore completeUpgradeTaskRun(
    UpgradeTask upgradeTask, UpgradeStore upgradeStore, long startTime, UpgradeStatus upgradeStatus)
    throws Exception {
    UpgradeTaskRun upgradeTaskRun = new UpgradeTaskRun()
      .setStartTime(startTime)
      .setEndTime(System.currentTimeMillis())
      .setStatus(upgradeStatus);
    return upgradeStore.addUpgradeRun(upgradeTask.getTaskUUID(), upgradeTask.getTaskName(), upgradeTaskRun);
  }

  public static void main(String[] args) {
    final DACConfig dacConfig = DACConfig.newConfig();
    final ScanResult classPathScan = ClassPathScanner.fromPrescan(dacConfig.getConfig().getSabotConfig());
    try {
      Upgrade upgrade = new Upgrade(dacConfig, classPathScan, true);
      upgrade.run();
    } catch (Exception e) {
      e.printStackTrace();
      System.err.println("Upgrade failed " + e);
      System.exit(1);
    }
  }
}
