package ru.it_spectrum.ai.rag.mcp.ingest;

import java.util.List;
import java.util.Map;

/**
 * The third of the three shapes that travel between steps: one piece of a document as named
 * values, before it is laid out as a record.
 *
 * @param ancestors headings above the unit, outermost first; empty for a row
 * @param intro     the unit is the text before the first heading
 * @param values    the values of the unit for templates, name to values
 */
public record Unit(List<String> ancestors, boolean intro, Map<String, List<String>> values) {

    /** The heading of the unit; the document title when the unit has none. Tree formats. */
    public static final String HEADING = "heading";
    /** Prefix of the named groups of {@code split.headingPattern}: {@code heading.<group>}. */
    public static final String HEADING_GROUP = "heading.";
    /** The headings above the unit, joined with the breadcrumb separator. Tree formats. */
    public static final String PARENTS = "parents";
    /** The text of the unit: the sections of a tree format, the file or list item of YAML. */
    public static final String BODY = "body";
    /** Position of the unit among the units of its item, from 1. Row formats. */
    public static final String ROW = "row";

    /** First value of a name, or an empty string. */
    public String value(String name) {
        List<String> found = values.get(name);
        return found == null || found.isEmpty() ? "" : found.getFirst();
    }

    public String heading() {
        return value(HEADING);
    }

    public String text() {
        return value(BODY);
    }
}
