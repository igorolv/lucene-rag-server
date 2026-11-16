package ru.it_spectrum.ai.rag.mcp.definition;

import java.util.List;

/**
 * What the indexes file and the files it imports give.
 *
 * @param indexes        the indexes of all files, those of the main file first; a wrong
 *                       entry is a broken definition
 * @param importProblems why an entry of {@code import} gave no indexes, or left one out: a
 *                       file that is missing or cannot be used, an index name taken already.
 *                       Such a problem belongs to no index and does not stop the others
 * @param warnings       keys of the files that are not read, each with its file, where it is and
 *                       the known key nearest to it (docs/decisions.md D95); they change nothing
 */
public record LoadedIndexes(List<IndexDefinition> indexes, List<String> importProblems, List<String> warnings) {

    public static final LoadedIndexes EMPTY = new LoadedIndexes(List.of(), List.of(), List.of());

    public LoadedIndexes {
        indexes = List.copyOf(indexes);
        importProblems = List.copyOf(importProblems);
        warnings = List.copyOf(warnings);
    }
}
