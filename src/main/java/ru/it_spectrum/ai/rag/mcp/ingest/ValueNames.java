package ru.it_spectrum.ai.rag.mcp.ingest;

import java.util.Set;

/**
 * The names of the values a format gives to templates, so a {@code map} block can be checked
 * when the indexes file is loaded. The values every source has (the path, the document key,
 * the title and so on) belong to the mapper and are not listed here; the unit values of a tree
 * format are the constants of {@link Unit}.
 *
 * @param label            how the format is named in a message: "a Markdown source"
 * @param tree             the format gives a {@link SectionTree}, so the unit values are those
 *                         of the splitter
 * @param documentNames    document values, available to every template
 * @param documentPrefixes prefixes of open sets of document values: {@code frontmatter.}
 * @param unitNames        unit values of a row format, available from the title on
 * @param unitPrefixes     prefixes of open sets of unit values of a row format: {@code column.}
 */
public record ValueNames(String label, boolean tree, Set<String> documentNames, Set<String> documentPrefixes,
                         Set<String> unitNames, Set<String> unitPrefixes) {
}
