package ru.it_spectrum.ai.rag.mcp.ingest.map;

import ru.it_spectrum.ai.rag.mcp.collections.MapSettings;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.Format;
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;
import ru.it_spectrum.ai.rag.mcp.ingest.ValueNames;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the {@code map} block of a source when the collections file is loaded, so a misspelled
 * value name is a configuration error and not a silently empty field.
 *
 * <p>Values become available in the order records are laid out — document key, title,
 * breadcrumb, text, id, fields — and a template may use only what exists by then. The names
 * every source has are listed here; the names of a format come from its {@link ValueNames}.
 */
final class TemplateCheck {

    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern PATH_SEGMENT = Pattern.compile("path\\[\\d+]");
    private static final Pattern HEADING_GROUP = Pattern.compile("heading\\.([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern PATH_GROUP = Pattern.compile("path\\.([A-Za-z][A-Za-z0-9]*)");

    private static final Set<String> DOCUMENT = Set.of("path", "path.name", "path.dir", "document.path",
            "document.title");
    private static final Set<String> TITLE = Set.of("document.key");
    private static final Set<String> TREE_TITLE = Set.of(Unit.HEADING, Unit.PARENTS);
    private static final Set<String> BREADCRUMB = Set.of("title", "slug");
    private static final Set<String> TEXT = Set.of("breadcrumb");
    private static final Set<String> TREE_TEXT = Set.of(Unit.BODY);

    private TemplateCheck() {
    }

    /** @throws IllegalArgumentException naming the template and the value that is not available in it */
    static void check(MapSettings map, SplitSettings split, Format format) {
        Check check = new Check(format.valueNames(),
                split.headingPattern() == null ? Set.of() : split.compiledHeadingPattern().namedGroups().keySet(),
                map.pathPattern() == null ? Set.of() : map.compiledPathPattern().namedGroups().keySet());
        check.template("map.document", map.document(), 0);
        check.template("map.title", map.title(), 1);
        if (map.breadcrumb() != null) {
            for (String part : map.breadcrumb()) {
                check.template("map.breadcrumb", part, 2);
            }
        }
        if (map.text() == null && format.mapDefaults().text() == null) {
            throw new IllegalArgumentException("map.text is required for " + format.valueNames().label());
        }
        check.template("map.text", map.text(), 3);
        check.template("map.id", map.id(), 3);
        if (map.fields() != null) {
            for (var field : map.fields().entrySet()) {
                if (!FIELD_NAME.matcher(field.getKey()).matches()) {
                    throw new IllegalArgumentException("map.fields: the field name \"" + field.getKey()
                            + "\" may contain only letters, digits, '.', '_' and '-'");
                }
                check.template("map.fields." + field.getKey(), field.getValue(), 3);
            }
        }
        if (map.keys() != null) {
            for (String name : map.keys()) {
                if (!FIELD_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("map.keys: \"" + name + "\" is not a field name");
                }
            }
        }
        if (map.inheritFields() != null) {
            for (String name : map.inheritFields()) {
                if (!MapSettings.ALL_FIELDS.equals(name) && !FIELD_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("map.inheritFields: \"" + name + "\" is not a field name");
                }
            }
        }
    }

    private record Check(ValueNames names, Set<String> headingGroups, Set<String> pathGroups) {

        void template(String where, String template, int stage) {
            if (template == null) {
                return;
            }
            final List<String> used;
            try {
                used = Template.names(template);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage());
            }
            for (String name : used) {
                var group = HEADING_GROUP.matcher(name);
                if (names.tree() && group.matches()) {
                    if (stage < 1) {
                        throw new IllegalArgumentException(where + ": {" + name + "} is not available here");
                    }
                    if (!headingGroups.contains(group.group(1))) {
                        throw new IllegalArgumentException(where + ": {" + name + "} needs a named group (?<"
                                + group.group(1) + ">…) in split.headingPattern");
                    }
                    continue;
                }
                var pathGroup = PATH_GROUP.matcher(name);
                if (pathGroup.matches() && !DOCUMENT.contains(name)) {
                    if (!pathGroups.contains(pathGroup.group(1))) {
                        throw new IllegalArgumentException(where + ": {" + name + "} needs a named group (?<"
                                + pathGroup.group(1) + ">…) in map.pathPattern");
                    }
                    continue;
                }
                boolean known = DOCUMENT.contains(name) || PATH_SEGMENT.matcher(name).matches()
                        || names.documentNames().contains(name) || prefixed(names.documentPrefixes(), name)
                        || stage >= 1 && unitValue(name)
                        || stage >= 2 && BREADCRUMB.contains(name)
                        || stage >= 3 && (TEXT.contains(name) || names.tree() && TREE_TEXT.contains(name));
                if (!known) {
                    boolean exists = unitValue(name) || BREADCRUMB.contains(name) || TEXT.contains(name)
                            || names.tree() && TREE_TEXT.contains(name);
                    throw new IllegalArgumentException(where + ": {" + name + "} is "
                            + (exists ? "not available here" : "not a value of " + names.label()));
                }
            }
        }

        /** A value that exists once a unit is taken: from the title on. */
        private boolean unitValue(String name) {
            return TITLE.contains(name)
                    || (names.tree() ? TREE_TITLE.contains(name)
                    : names.unitNames().contains(name) || prefixed(names.unitPrefixes(), name));
        }

        private static boolean prefixed(Set<String> prefixes, String name) {
            for (String prefix : prefixes) {
                if (name.startsWith(prefix) && FIELD_NAME.matcher(name.substring(prefix.length())).matches()) {
                    return true;
                }
            }
            return false;
        }
    }
}
