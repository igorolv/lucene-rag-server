package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;

/**
 * What the mapper needs to know about a lookup of a source to check templates before anything is
 * read: its name, its two templates and which values its units have.
 *
 * @param name   the prefix of the values the lookup gives
 * @param key    template over the values of a unit of the lookup
 * @param on     template over the values of a unit of the source
 * @param split  the {@code split} block of the lookup
 * @param format the format the lookup is read with
 */
public record JoinSpec(String name, String key, String on, SplitSettings split, Format format) {
}
