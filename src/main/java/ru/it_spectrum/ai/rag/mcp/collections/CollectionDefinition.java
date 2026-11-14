package ru.it_spectrum.ai.rag.mcp.collections;

import java.util.List;

/**
 * One collection as {@code collections.json} describes it.
 *
 * @param configError why this entry cannot be built, or {@code null}. A broken entry does not
 *                    stop the other collections from loading
 */
public record CollectionDefinition(String name, String description, List<SourceDefinition> sources,
                                   String configError) {

    public boolean usable() {
        return configError == null;
    }

    /** This entry as a broken one: a source of it does not fit its steps. */
    public CollectionDefinition withError(String error) {
        return new CollectionDefinition(name, description, sources, error);
    }

    static CollectionDefinition broken(String name, String description, String error) {
        return new CollectionDefinition(name, description, List.of(), error);
    }
}
