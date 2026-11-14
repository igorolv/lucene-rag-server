package ru.it_spectrum.ai.rag.mcp.source;

import ru.it_spectrum.ai.rag.mcp.collections.ChunkSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cuts a section tree into records. Shared by every tree format.
 *
 * <ol>
 *   <li>Each section at the {@code splitBy} level becomes one unit together with everything
 *       nested in it. A section above that level contributes only its own text, and the text
 *       before the first heading is a unit of its own.</li>
 *   <li>A unit shorter than {@code minChars} is appended to the previous unit under the same
 *       parent when the result still fits {@code maxChars}.</li>
 *   <li>A unit longer than {@code maxChars} is split at blank lines, then at line ends, and only
 *       then inside a line.</li>
 * </ol>
 */
public final class Chunker {

    private Chunker() {
    }

    public static List<ChunkRecord> chunk(SourceDocument document, ChunkSettings settings) {
        List<Unit> units = new ArrayList<>();
        if (!document.preamble().isBlank()) {
            units.add(new Unit(List.of(), 1, document.title(), "intro", document.preamble()));
        }
        for (Section section : document.sections()) {
            collect(section, List.of(), settings.splitBy(), units);
        }
        units = merge(units, settings);

        List<ChunkRecord> records = new ArrayList<>();
        Map<String, Integer> used = new HashMap<>();
        for (Unit unit : units) {
            String slug = unit.slug.isEmpty() ? "section" : unit.slug;
            int seen = used.merge(slug, 1, Integer::sum);
            String base = document.key() + "#" + (seen == 1 ? slug : slug + "-" + seen);
            String breadcrumb = breadcrumb(document.title(), unit);
            List<String> parts = split(unit.text.toString(), settings.maxChars());
            for (int i = 0; i < parts.size(); i++) {
                records.add(new ChunkRecord(i == 0 ? base : base + "~" + (i + 1), document.key(), unit.title,
                        breadcrumb, parts.get(i), document.path(), records.size(), document.fields()));
            }
        }
        return records;
    }

    private static void collect(Section section, List<String> ancestors, int splitBy, List<Unit> units) {
        if (section.level() >= splitBy) {
            StringBuilder text = new StringBuilder(section.text());
            for (Section child : section.children()) {
                render(child, text);
            }
            if (!text.toString().isBlank()) {
                units.add(new Unit(ancestors, section.level(), section.title(), slug(section.title()),
                        text.toString().strip()));
            }
            return;
        }
        if (!section.text().isBlank()) {
            units.add(new Unit(ancestors, section.level(), section.title(), slug(section.title()), section.text()));
        }
        List<String> path = new ArrayList<>(ancestors);
        path.add(section.title());
        for (Section child : section.children()) {
            collect(child, List.copyOf(path), splitBy, units);
        }
    }

    private static void render(Section section, StringBuilder out) {
        if (!out.isEmpty()) {
            out.append("\n\n");
        }
        out.append("#".repeat(section.level())).append(' ').append(section.title());
        if (!section.text().isBlank()) {
            out.append("\n\n").append(section.text());
        }
        for (Section child : section.children()) {
            render(child, out);
        }
    }

    private static List<Unit> merge(List<Unit> units, ChunkSettings settings) {
        List<Unit> merged = new ArrayList<>();
        for (Unit unit : units) {
            Unit previous = merged.isEmpty() ? null : merged.getLast();
            if (previous != null && unit.text.length() < settings.minChars()
                    && previous.ancestors.equals(unit.ancestors)
                    && previous.text.length() + unit.text.length() + unit.title.length() + 8 <= settings.maxChars()) {
                previous.text.append("\n\n").append("#".repeat(unit.level)).append(' ')
                        .append(unit.title).append("\n\n").append(unit.text);
            } else {
                merged.add(unit);
            }
        }
        return merged;
    }

    private static String breadcrumb(String documentTitle, Unit unit) {
        List<String> parts = new ArrayList<>();
        parts.add(documentTitle);
        for (String ancestor : unit.ancestors) {
            if (!ancestor.equals(parts.getLast())) {
                parts.add(ancestor);
            }
        }
        if (!unit.title.equals(parts.getLast())) {
            parts.add(unit.title);
        }
        return String.join(ChunkRecord.BREADCRUMB_SEPARATOR, parts);
    }

    /** Package-private for tests. */
    static List<String> split(String text, int maxChars) {
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

    /** Lower-case letters and digits of the title joined by {@code -}; works for any script. */
    static String slug(String title) {
        StringBuilder out = new StringBuilder();
        boolean gap = false;
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                if (gap && !out.isEmpty()) {
                    out.append('-');
                }
                out.appendCodePoint(Character.toLowerCase(cp));
                gap = false;
            } else {
                gap = true;
            }
        }
        String slug = out.toString().toLowerCase(Locale.ROOT);
        return slug.length() > 80 ? slug.substring(0, 80) : slug;
    }

    private static final class Unit {
        final List<String> ancestors;
        final int level;
        final String title;
        final String slug;
        final StringBuilder text;

        Unit(List<String> ancestors, int level, String title, String slug, String text) {
            this.ancestors = ancestors;
            this.level = level;
            this.title = title;
            this.slug = slug;
            this.text = new StringBuilder(text);
        }
    }
}
