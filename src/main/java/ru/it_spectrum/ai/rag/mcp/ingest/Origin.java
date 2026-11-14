package ru.it_spectrum.ai.rag.mcp.ingest;

import ru.it_spectrum.ai.rag.mcp.collections.SourceDefinition;

import java.io.IOException;
import java.util.List;

/**
 * Step 1 of a source: what is read. An origin lists the items of a source in a stable order;
 * every later step works on one item at a time.
 */
public interface Origin {

    /** The name the registry finds the origin by. */
    String name();

    /**
     * @param defaultInclude glob of the format of the source, used when the source names no
     *                       {@code include}
     * @throws IOException when the source cannot be listed
     */
    List<SourceItem> items(SourceDefinition source, String defaultInclude) throws IOException;
}
