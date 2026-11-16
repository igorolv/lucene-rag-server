package ru.it_spectrum.ai.rag.mcp.ingest.map;

import ru.it_spectrum.ai.rag.mcp.definition.MapSettings;
import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.Format;
import ru.it_spectrum.ai.rag.mcp.ingest.JoinSpec;
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;
import ru.it_spectrum.ai.rag.mcp.ingest.ValueNames;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the {@code map} block of a source when the indexes file is loaded, so a misspelled
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
    private static final Pattern BODY_GROUP = Pattern.compile("body\\.([A-Za-z][A-Za-z0-9]*)");
    private static final Pattern PATH_GROUP = Pattern.compile("path\\.([A-Za-z][A-Za-z0-9]*)");

    /** First parts of the names the mapper and the splitter give, which a lookup may not be named as. */
    private static final Set<String> OWN_ROOTS = Set.of("path", "document", "title", "slug", "breadcrumb",
            Unit.HEADING, Unit.PARENTS, Unit.BODY, Unit.ROW, "column");

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
    static void check(MapSettings map, SplitSettings split, Format format, List<JoinSpec> joins) {
        ValueNames names = format.valueNames();
        if (map.bodyPattern() != null && !names.tree() && !names.unitNames().contains(Unit.BODY)) {
            throw new IllegalArgumentException("map.bodyPattern is not a setting of " + names.label()
                    + ": its units have no {body}");
        }
        Set<String> headingGroups = headingGroups(split);
        Set<String> pathGroups = map.pathPattern() == null ? Set.of()
                : map.compiledPathPattern().namedGroups().keySet();
        Set<String> bodyGroups = map.bodyPattern() == null ? Set.of()
                : map.compiledBodyPattern().namedGroups().keySet();
        Check alone = new Check(names, headingGroups, pathGroups, bodyGroups, split.tableRows(), Map.of());
        Map<String, Check> lookups = new LinkedHashMap<>();
        for (JoinSpec join : joins) {
            String where = "join." + join.name();
            if (OWN_ROOTS.contains(join.name()) || roots(names).contains(join.name())) {
                throw new IllegalArgumentException(where + ": the name \"" + join.name()
                        + "\" is taken by the values {" + join.name() + "…} of the source");
            }
            Check lookup = new Check(join.format().valueNames(), headingGroups(join.split()), Set.of(), Set.of(),
                    join.split().tableRows(), Map.of());
            lookup.lookupTemplate(where + ".key", join.key());
            alone.template(where + ".on", join.on(), 1);
            lookups.put(join.name(), lookup);
        }
        Check check = new Check(names, headingGroups, pathGroups, bodyGroups, split.tableRows(), lookups);
        // The document key is rendered per unit before the lookups (D91): unit values, not itself
        // and not the values of a lookup.
        alone.template("map.document", map.document(), 1);
        if (map.document() != null && map.document().contains("document.key")) {
            throw new IllegalArgumentException("map.document: {document.key} is not available here");
        }
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

    private static Set<String> headingGroups(SplitSettings split) {
        return split.headingPattern() == null ? Set.of() : split.compiledHeadingPattern().namedGroups().keySet();
    }

    /** The first parts of the names a format gives: {@code frontmatter} of {@code frontmatter.<key>}. */
    private static Set<String> roots(ValueNames names) {
        Set<String> roots = new HashSet<>();
        for (Set<String> set : List.of(names.documentNames(), names.documentPrefixes(), names.unitNames(),
                names.unitPrefixes())) {
            for (String name : set) {
                int dot = name.indexOf('.');
                roots.add(dot < 0 ? name : name.substring(0, dot));
            }
        }
        return roots;
    }

    /**
     * @param joins the lookups of the source by name, each as the check of the values its units
     *              have; empty for a lookup itself
     */
    private record Check(ValueNames names, Set<String> headingGroups, Set<String> pathGroups,
                         Set<String> bodyGroups, boolean tableRows, Map<String, Check> joins) {

        private static List<String> names(String where, String template) {
            try {
                return Template.names(template);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": " + e.getMessage());
            }
        }

        /** The {@code key} template of a lookup: only the values its format gives a unit. */
        void lookupTemplate(String where, String template) {
            for (String name : names(where, template)) {
                if (!formatValue(name)) {
                    throw new IllegalArgumentException(where + ": {" + name + "} is not a value of a unit of "
                            + names.label());
                }
            }
        }

        /** A value the format gives a unit or its document, whatever the mapper adds. */
        private boolean formatValue(String name) {
            var group = HEADING_GROUP.matcher(name);
            if (names.tree() && group.matches()) {
                return headingGroups.contains(group.group(1));
            }
            return names.documentNames().contains(name) || prefixed(names.documentPrefixes(), name)
                    || (names.tree() ? TREE_TITLE.contains(name) || TREE_TEXT.contains(name)
                    || tableRows && (Unit.ROW.equals(name) || prefixed(Set.of(Unit.COLUMN), name))
                    : names.unitNames().contains(name) || prefixed(names.unitPrefixes(), name));
        }

        void template(String where, String template, int stage) {
            if (template == null) {
                return;
            }
            for (String name : names(where, template)) {
                int dot = name.indexOf('.');
                Check lookup = dot > 0 ? joins.get(name.substring(0, dot)) : null;
                if (lookup != null) {
                    if (stage < 1) {
                        throw new IllegalArgumentException(where + ": {" + name + "} is not available here");
                    }
                    if (!lookup.formatValue(name.substring(dot + 1))) {
                        throw new IllegalArgumentException(where + ": {" + name + "} is not a value of the lookup \""
                                + name.substring(0, dot) + "\", " + lookup.names.label());
                    }
                    continue;
                }
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
                var bodyGroup = BODY_GROUP.matcher(name);
                if (bodyGroup.matches()) {
                    if (stage < 1) {
                        throw new IllegalArgumentException(where + ": {" + name + "} is not available here");
                    }
                    if (!bodyGroups.contains(bodyGroup.group(1))) {
                        throw new IllegalArgumentException(where + ": {" + name + "} needs a named group (?<"
                                + bodyGroup.group(1) + ">…) in map.bodyPattern");
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
                    || tableRows && (Unit.ROW.equals(name) || prefixed(Set.of(Unit.COLUMN), name))
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
