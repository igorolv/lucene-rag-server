package ru.it_spectrum.ai.rag.mcp.embedding;

import java.io.IOException;
import java.nio.file.Path;

/**
 * One kind of embedding model a {@code models} entry can name by its {@code type}
 * (docs/decisions.md D43). Types are beans, as the steps of a source are (D19): the application
 * context gives every bean of this interface to {@link EmbeddingModels}, so a type is added
 * without changing the registry.
 */
public interface ModelType {

    /** The value of {@code type} in a {@code models} entry. */
    String name();

    /**
     * Whether a model of this type is read from {@code <data-dir>/models/<name>/}; the registry
     * then reports a missing directory before {@link #load} runs.
     */
    default boolean usesFiles() {
        return true;
    }

    /**
     * @param name       the key of the model under {@code models}
     * @param dir        {@code <data-dir>/models/<name>/}
     * @param parameters how the model is run
     * @throws IOException when the model cannot be loaded or is not usable
     */
    EmbeddingModels.Loaded load(String name, Path dir, ModelParameters parameters) throws IOException;
}
