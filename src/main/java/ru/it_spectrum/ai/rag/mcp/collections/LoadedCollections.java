package ru.it_spectrum.ai.rag.mcp.collections;

import java.util.List;

/**
 * What the collections file and the files it imports give.
 *
 * @param collections    the collections of all files, those of the main file first; a wrong
 *                       entry is a broken definition
 * @param importProblems why an entry of {@code import} gave no collections, or left one out: a
 *                       file that is missing or cannot be used, a collection name taken already.
 *                       Such a problem belongs to no collection and does not stop the others
 */
public record LoadedCollections(List<CollectionDefinition> collections, List<String> importProblems) {

    public static final LoadedCollections EMPTY = new LoadedCollections(List.of(), List.of());

    public LoadedCollections {
        collections = List.copyOf(collections);
        importProblems = List.copyOf(importProblems);
    }
}
