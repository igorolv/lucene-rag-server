package ru.it_spectrum.ai.rag.mcp.definition;

import java.util.List;

/**
 * One index as the indexes file ({@code collections.json}) describes it.
 *
 * @param vectors     the model the records are embedded with, or {@code null} for an index
 *                    searched lexically only
 * @param configError why this entry cannot be built, or {@code null}. A broken entry does not
 *                    stop the other indexes from loading
 */
public record IndexDefinition(String name, String description, List<SourceDefinition> sources,
                                   ModelDefinition vectors, String configError) {

    public IndexDefinition(String name, String description, List<SourceDefinition> sources, String configError) {
        this(name, description, sources, null, configError);
    }

    public boolean usable() {
        return configError == null;
    }

    /** This entry as a broken one: a source of it does not fit its steps. */
    public IndexDefinition withError(String error) {
        return new IndexDefinition(name, description, sources, vectors, error);
    }

    static IndexDefinition broken(String name, String description, String error) {
        return new IndexDefinition(name, description, List.of(), null, error);
    }
}
