package ru.it_spectrum.ai.rag.mcp.embedding;

import java.io.IOException;
import java.util.List;

/**
 * A loaded embedding model: turns texts into unit-length vectors. The in-process ONNX model is
 * one implementation (docs/decisions.md D11); a remote endpoint would be another.
 *
 * <p>An embedder embeds the texts it is given as they are. The prefixes a model family expects
 * ({@code query: }, {@code passage: }) are added by {@link VectorModel}.
 *
 * <p>Implementations are safe for use by several threads.
 */
public interface Embedder extends AutoCloseable {

    /** What was loaded: the model's type, vector size and the fingerprints of its files. */
    ModelInfo info();

    /**
     * @param texts texts to embed; may be empty
     * @return one unit-length vector per text, in order, and which texts were cut to the model's
     *         input limit
     * @throws IOException when the model fails to run
     */
    Embeddings embed(List<String> texts) throws IOException;

    @Override
    void close();
}
