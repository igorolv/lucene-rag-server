package ru.it_spectrum.ai.rag.mcp.ingest.split;

import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.Section;
import ru.it_spectrum.ai.rag.mcp.ingest.SectionTree;
import ru.it_spectrum.ai.rag.mcp.ingest.Splitter;
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a section tree into units by {@link SplitSettings}. Shared by every tree format; what a
 * unit becomes is decided afterwards by the mapper.
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
 * Splitting a long unit by {@code maxChars} happens after mapping, on the final text.
 *
 * <p>A unit carries the values {@link Unit#HEADING}, {@link Unit#PARENTS}, {@link Unit#BODY} and
 * one {@code heading.<group>} per named group of the heading pattern that matched.
 */
@Component
public final class HeadingSplitter implements Splitter {

    @Override
    public List<Unit> split(SectionTree document, SplitSettings settings) {
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
        return drafts.stream().map(Draft::toUnit).toList();
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

        Unit toUnit() {
            Map<String, List<String>> values = new LinkedHashMap<>();
            values.put(Unit.HEADING, one(heading));
            groups.forEach((name, value) -> values.put(Unit.HEADING_GROUP + name, one(value)));
            values.put(Unit.PARENTS, one(String.join(ChunkRecord.BREADCRUMB_SEPARATOR, ancestors)));
            values.put(Unit.BODY, one(text.toString()));
            return new Unit(ancestors, intro, values);
        }

        private static List<String> one(String value) {
            return value == null || value.isEmpty() ? List.of() : List.of(value);
        }
    }
}
