package ru.it_spectrum.ai.rag.mcp.source;

import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a section tree into units by {@link SplitSettings}. Shared by every tree format; what a
 * unit becomes is decided afterwards by {@link RecordMapper}.
 *
 * <ol>
 *   <li>Each section at the split level becomes one unit together with everything nested in it.
 *       A section above that level contributes only its own text, and the text before the first
 *       heading is a unit of its own. With the {@code document} level the whole file is one
 *       unit.</li>
 *   <li>With a heading pattern only the sections at the split level whose heading contains the
 *       pattern become units; the rest of the file is left out.</li>
 *   <li>A unit shorter than {@code minChars} is appended to the previous unit under the same
 *       parent when the result still fits {@code maxChars}.</li>
 * </ol>
 *
 * Splitting a long unit by {@code maxChars} happens after mapping, on the final text
 * ({@link #split}).
 */
public final class Chunker {

    private Chunker() {
    }

    /**
     * One piece of a document.
     *
     * @param ancestors headings above the unit, outermost first
     * @param level     heading level of the unit; 0 for the introduction and for a whole document
     * @param heading   heading of the unit; the document title when the unit has none
     * @param groups    named groups of the heading pattern that matched the heading
     * @param text      text of the unit, nested sections included with their headings
     * @param intro     the unit is the text before the first heading
     */
    public record Unit(List<String> ancestors, int level, String heading, Map<String, String> groups,
                       String text, boolean intro) { }

    public static List<Unit> units(SourceDocument document, SplitSettings settings) {
        List<Draft> drafts = new ArrayList<>();
        if (settings.level() == SplitSettings.DOCUMENT) {
            StringBuilder text = new StringBuilder(document.preamble());
            for (Section section : document.sections()) {
                render(section, text);
            }
            if (!text.toString().isBlank()) {
                drafts.add(new Draft(List.of(), 0, document.title(), Map.of(), text.toString().strip(), false));
            }
        } else {
            Pattern pattern = settings.compiledHeadingPattern();
            if (pattern == null && !document.preamble().isBlank()) {
                drafts.add(new Draft(List.of(), 0, document.title(), Map.of(), document.preamble(), true));
            }
            for (Section section : document.sections()) {
                collect(section, List.of(), settings.level(), pattern, drafts);
            }
            drafts = merge(drafts, settings);
        }
        return drafts.stream().map(d -> new Unit(d.ancestors, d.level, d.heading, d.groups, d.text.toString(),
                d.intro)).toList();
    }

    private static void collect(Section section, List<String> ancestors, int splitLevel, Pattern pattern,
                                List<Draft> drafts) {
        if (section.level() >= splitLevel) {
            Map<String, String> groups = Map.of();
            if (pattern != null) {
                Matcher matcher = pattern.matcher(section.title());
                if (!matcher.find()) {
                    return;
                }
                groups = new LinkedHashMap<>();
                for (String name : pattern.namedGroups().keySet()) {
                    String value = matcher.group(name);
                    if (value != null) {
                        groups.put(name, value.strip());
                    }
                }
            }
            StringBuilder text = new StringBuilder(section.text());
            for (Section child : section.children()) {
                render(child, text);
            }
            if (!text.toString().isBlank()) {
                drafts.add(new Draft(ancestors, section.level(), section.title(), groups,
                        text.toString().strip(), false));
            }
            return;
        }
        if (pattern == null && !section.text().isBlank()) {
            drafts.add(new Draft(ancestors, section.level(), section.title(), Map.of(), section.text(), false));
        }
        List<String> path = new ArrayList<>(ancestors);
        path.add(section.title());
        for (Section child : section.children()) {
            collect(child, List.copyOf(path), splitLevel, pattern, drafts);
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

    private static List<Draft> merge(List<Draft> drafts, SplitSettings settings) {
        List<Draft> merged = new ArrayList<>();
        for (Draft draft : drafts) {
            Draft previous = merged.isEmpty() ? null : merged.getLast();
            if (previous != null && draft.text.length() < settings.minChars()
                    && previous.ancestors.equals(draft.ancestors)
                    && previous.text.length() + draft.text.length() + draft.heading.length() + 8
                    <= settings.maxChars()) {
                previous.text.append("\n\n").append("#".repeat(Math.max(1, draft.level))).append(' ')
                        .append(draft.heading).append("\n\n").append(draft.text);
            } else {
                merged.add(draft);
            }
        }
        return merged;
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

    private static final class Draft {
        final List<String> ancestors;
        final int level;
        final String heading;
        final Map<String, String> groups;
        final StringBuilder text;
        final boolean intro;

        Draft(List<String> ancestors, int level, String heading, Map<String, String> groups, String text,
              boolean intro) {
            this.ancestors = ancestors;
            this.level = level;
            this.heading = heading;
            this.groups = groups;
            this.text = new StringBuilder(text);
            this.intro = intro;
        }
    }
}
