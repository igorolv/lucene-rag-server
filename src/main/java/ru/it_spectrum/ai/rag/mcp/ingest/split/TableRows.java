package ru.it_spectrum.ai.rag.mcp.ingest.split;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rows of the Markdown tables in a text (GitHub table syntax): a header line, a delimiter line
 * of dashes and the body lines up to the first line that is blank or has no {@code |}. Tables in
 * fenced code blocks are not tables.
 */
final class TableRows {

    /**
     * One body row.
     *
     * @param header the cells of the header line
     * @param cells  the cells of the row, as many as written
     * @param number position of the row in its table, from 1
     * @param text   the header line, the delimiter line and the row: a table of one row
     */
    record Row(List<String> header, List<String> cells, int number, String text) {
    }

    private static final Pattern DELIMITER = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

    private TableRows() {
    }

    static List<Row> of(String text) {
        String[] lines = text.split("\n", -1);
        List<Row> rows = new ArrayList<>();
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            if (FENCE.matcher(lines[i]).find()) {
                fenced = !fenced;
                continue;
            }
            if (fenced || !lines[i].contains("|") || i + 1 >= lines.length || !DELIMITER.matcher(lines[i + 1]).matches()) {
                continue;
            }
            List<String> header = cells(lines[i]);
            String head = lines[i].strip() + "\n" + lines[i + 1].strip();
            int number = 0;
            int j = i + 2;
            for (; j < lines.length && !lines[j].isBlank() && lines[j].contains("|"); j++) {
                rows.add(new Row(header, cells(lines[j]), ++number, head + "\n" + lines[j].strip()));
            }
            i = j - 1;
        }
        return rows;
    }

    /** The cells of a table line: split at {@code |} not escaped by a backslash, outer pipes dropped. */
    static List<String> cells(String line) {
        String text = line.strip();
        if (text.startsWith("|")) {
            text = text.substring(1);
        }
        if (text.endsWith("|") && !text.endsWith("\\|")) {
            text = text.substring(0, text.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == '|') {
                cell.append('|');
                i++;
            } else if (c == '|') {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().strip());
        return cells;
    }
}
