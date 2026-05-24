package com.botwithus.bot.cli.output;

import java.util.ArrayList;
import java.util.List;

public final class TableFormatter {

    private final List<String[]> rows = new ArrayList<>();
    private String[] headers;

    public TableFormatter headers(String... headers) {
        this.headers = headers;
        return this;
    }

    public TableFormatter row(String... values) {
        rows.add(values);
        return this;
    }

    public String build() {
        if (headers == null || headers.length == 0) {
            return "";
        }

        int cols = headers.length;
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = headers[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < cols && i < row.length; i++) {
                widths[i] = Math.max(widths[i], stripAnsi(row[i]).length());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(border(widths));
        sb.append(formatRow(headers, widths));
        sb.append(border(widths));
        for (String[] row : rows) {
            sb.append(formatRow(row, widths));
        }
        sb.append(border(widths));
        return sb.toString();
    }

    private static String formatRow(String[] values, int[] widths) {
        StringBuilder sb = new StringBuilder("| ");
        for (int i = 0; i < widths.length; i++) {
            String val = i < values.length ? values[i] : "";
            sb.append(pad(val, widths[i]));
            sb.append(i < widths.length - 1 ? " | " : " |");
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String border(int[] widths) {
        StringBuilder sb = new StringBuilder("+-");
        for (int i = 0; i < widths.length; i++) {
            sb.append("-".repeat(widths[i]));
            sb.append(i < widths.length - 1 ? "-+-" : "-+");
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String pad(String s, int width) {
        int visible = stripAnsi(s).length();
        if (visible >= width) {
            return s;
        }
        return s + " ".repeat(width - visible);
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
