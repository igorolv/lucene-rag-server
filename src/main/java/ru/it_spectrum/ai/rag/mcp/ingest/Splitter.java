package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;

import java.util.List;

/**
 * Step 4 of a source, for tree formats only: where a section tree is cut into units. The values
 * of a unit are named by the constants of {@link Unit}.
 */
public interface Splitter {

    List<Unit> split(SectionTree tree, SplitSettings settings);
}
