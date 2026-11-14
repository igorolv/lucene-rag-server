package ru.it_spectrum.ai.rag.mcp.ingest.map;

import java.util.ArrayList;
import java.util.List;

/** Splits the text of a record that is longer than {@code split.maxChars}. */
public final class TextParts {

    private TextParts() {
    }

    /** Parts of a text no longer than {@code maxChars}: cut at blank lines, then line ends, then inside a line. */
    public static List<String> split(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> pieces = new ArrayList<>();
        for (String paragraph : text.split("\n[ \\t]*\n")) {
            if (paragraph.length() <= maxChars) {
                pieces.add(paragraph);
                continue;
            }
            StringBuilder block = new StringBuilder();
            for (String line : paragraph.split("\n")) {
                while (line.length() > maxChars) {
                    if (!block.isEmpty()) {
                        pieces.add(block.toString());
                        block.setLength(0);
                    }
                    pieces.add(line.substring(0, maxChars));
                    line = line.substring(maxChars);
                }
                if (!block.isEmpty() && block.length() + 1 + line.length() > maxChars) {
                    pieces.add(block.toString());
                    block.setLength(0);
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
}
