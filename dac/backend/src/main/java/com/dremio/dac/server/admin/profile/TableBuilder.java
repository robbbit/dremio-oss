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
package com.dremio.dac.server.admin.profile;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;

import com.dremio.common.util.PrettyPrintUtils;

class TableBuilder {
  private final NumberFormat format = NumberFormat.getInstance(Locale.US);
  private final DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
  private final DecimalFormat dec = new DecimalFormat("0.00");
  private final DecimalFormat intformat = new DecimalFormat("#,###");

  private StringBuilder sb;
  private int w = 0;
  private int width;

  public TableBuilder(final String[] columns) {
    sb = new StringBuilder();
    width = columns.length;

    format.setMaximumFractionDigits(3);

    sb.append("<table class=\"table text-right\">\n<thead>\n<tr>");
    for (final String cn : columns) {
      sb.append("<th>" + cn + "</th>");
    }
    sb.append("</thead>\n</tr>\n");
  }

  public void appendCell(final String s, final String link) {
    if (w == 0) {
      sb.append("<tr>");
    }
    sb.append(String.format("<td>%s%s</td>", s, link != null ? link : ""));
    if (++w >= width) {
      sb.append("</tr>\n");
      w = 0;
    }
  }

  public void appendRepeated(final String s, final String link, final int n) {
    for (int i = 0; i < n; i++) {
      appendCell(s, link);
    }
  }

  public void appendTime(final long d, final String link) {
    appendCell(dateFormat.format(d), link);
  }

  public void appendMillis(final long p) {
    appendCell(SimpleDurationFormat.format(p), null);
  }

  public void appendNanos(final long p) {
    appendMillis(Math.round(p / 1000.0 / 1000.0));
  }

  public void appendNanosWithUnit(final long p) {
    appendCell(SimpleDurationFormat.formatNanos(p), null);
  }

  public void appendFormattedNumber(final Number n, final String link) {
    appendCell(format.format(n), link);
  }

  public void appendFormattedInteger(final long n, final String link) {
    appendCell(intformat.format(n), link);
  }

  public void appendInteger(final long l, final String link) {
    appendCell(Long.toString(l), link);
  }

  public void appendBytes(final long l, final String link){
    appendCell(PrettyPrintUtils.bytePrint(l, false), link);
  }

  public String build() {
    String rv;
    rv = sb.append("\n</table>").toString();
    sb = null;
    return rv;
  }
}
