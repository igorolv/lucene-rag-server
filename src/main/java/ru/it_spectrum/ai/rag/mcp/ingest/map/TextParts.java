package ru.it_spectrum.ai.rag.mcp.ingest.map;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits the text of a record that is longer than {@code split.maxChars}.
 *
 * <p>A Markdown table cut between two parts gives the second part its header line and delimiter
 * line again, so that the rows of every part keep the names of their columns. A header longer than
 * half of {@code maxChars} is not repeated: the part would hold little else.
 */
public final class TextParts {

    /** The delimiter line under the header of a Markdown table, as {@code TableRows} reads it. */
    private static final Pattern DELIMITER = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");

    private TextParts() {
    }

    /** Parts of a text no longer than {@code maxChars}: cut at blank lines, then line ends, then inside a line. */
    public static List<String> split(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        boolean fenced = false;
        for (String paragraph : text.split("\n[ \\t]*\n")) {
            if (paragraph.length() <= maxChars) {
                pieces.add(paragraph);
                fenced ^= fenceCount(paragraph) % 2 == 1;
                continue;
            }
            String[] lines = paragraph.split("\n");
            StringBuilder block = new StringBuilder();
            String header = null;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.stripLeading().startsWith("```")) {
                    fenced = !fenced;
                    header = null;
                } else if (fenced || !line.contains("|")) {
                    header = null;
                } else if (i + 1 < lines.length && DELIMITER.matcher(lines[i + 1]).matches()) {
                    String candidate = line + "\n" + lines[i + 1];
                    header = candidate.length() <= maxChars / 2 ? candidate : null;
                }
                boolean row = header != null && !line.equals(header.substring(0, header.indexOf('\n')))
                        && !DELIMITER.matcher(line).matches();
                while (line.length() > maxChars) {
                    if (!block.isEmpty()) {
                        pieces.add(block.toString());
                        block.setLength(0);
                    }
                    pieces.add(line.substring(0, maxChars));
                    line = line.substring(maxChars);
                    row = false;
                }
                if (!block.isEmpty() && block.length() + 1 + line.length() > maxChars) {
                    pieces.add(block.toString());
                    block.setLength(0);
                    if (row && header.length() + 1 + line.length() <= maxChars) {
                        block.append(header);
                    }
                }
                if (!block.isEmpty()) {
                    block.append('\n');
                }
                block.append(line);
            }
            if (!block.isEmpty()) {
                pieces.add(block.toString());
            }
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (!current.isEmpty() && current.length() + 2 + piece.length() > maxChars) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(piece);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static int fenceCount(String paragraph) {
        int count = 0;
        for (String line : paragraph.split("\n")) {
            if (line.stripLeading().startsWith("```")) {
                count++;
            }
        }
        return count;
    }
}
