package ru.it_spectrum.ai.rag.mcp.collections;

import java.util.List;

/**
 * One collection as {@code collections.json} describes it.
 *
 * @param vectors     the model the records are embedded with, or {@code null} for a collection
 *                    searched lexically only
 * @param configError why this entry cannot be built, or {@code null}. A broken entry does not
 *                    stop the other collections from loading
 */
public record CollectionDefinition(String name, String description, List<SourceDefinition> sources,
                                   ModelDefinition vectors, String configError) {

    public CollectionDefinition(String name, String description, List<SourceDefinition> sources, String configError) {
        this(name, description, sources, null, configError);
    }

    public boolean usable() {
        return configError == null;
    }

    /** This entry as a broken one: a source of it does not fit its steps. */
    public CollectionDefinition withError(String error) {
        return new CollectionDefinition(name, description, sources, vectors, error);
    }

    static CollectionDefinition broken(String name, String description, String error) {
        return new CollectionDefinition(name, description, List.of(), null, error);
    }
}
