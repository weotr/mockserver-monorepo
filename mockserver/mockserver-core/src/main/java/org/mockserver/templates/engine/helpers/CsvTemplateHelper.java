package org.mockserver.templates.engine.helpers;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV parsing and formatting helpers for templates. Implements a minimal
 * RFC 4180-ish dialect (no external dependency): comma-separated fields, fields
 * containing a comma, double-quote, or newline are wrapped in double quotes,
 * and embedded double-quotes are escaped by doubling.
 */
public class CsvTemplateHelper {

    /**
     * Parses CSV text into a list of rows, where each row is a list of field
     * values. Handles quoted fields containing commas, doubled quotes, and
     * newlines. Both {@code \n} and {@code \r\n} line endings are supported.
     * Returns an empty list for {@code null} or empty input.
     */
    public List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                } else if (c == '\r') {
                    // handle \r\n and bare \r as a line break
                    if (i + 1 < length && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                } else if (c == '\n') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                } else {
                    field.append(c);
                }
            }
        }
        // flush the final field / row, unless the input ended on a line break
        // (a trailing newline is a record terminator, not a separator that
        // introduces a spurious empty trailing row)
        if (field.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow);
        }
        return rows;
    }

    /**
     * Formats a list of field values into a single CSV line, quoting and
     * escaping fields as required. Returns an empty string for {@code null}
     * input.
     */
    public String row(List<?> fields) {
        if (fields == null) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(escapeField(fields.get(i)));
        }
        return line.toString();
    }

    private String escapeField(Object value) {
        String field = value == null ? "" : String.valueOf(value);
        boolean needsQuoting = field.indexOf(',') >= 0
            || field.indexOf('"') >= 0
            || field.indexOf('\n') >= 0
            || field.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    @Override
    public String toString() {
        return "CsvTemplateHelper";
    }
}
