package ru.it_spectrum.ai.rag.mcp.embedding;

import java.io.IOException;
import java.util.List;

/**
 * A named model with the parameters it is run with: what the indexer embeds records with and
 * search embeds a query with. Adds the query and passage prefixes the model family expects.
 *
 * @param name       the name under {@code models} and the directory under {@code <data-dir>/models/}
 * @param parameters how the model is run
 * @param embedder   the loaded model
 */
public record VectorModel(String name, ModelParameters parameters, Embedder embedder) {

    public ModelInfo info() {
        return embedder.info();
    }

    /** The text a record's passage is embedded as: the passage prefix and the passage. */
    public String passageInput(String passage) {
        return parameters.passagePrefix() + passage;
    }

    /** Vectors of passages already prefixed by {@link #passageInput}. */
    public Embeddings embedInputs(List<String> inputs) throws IOException {
        return embedder.embed(inputs);
    }

    /** The vector of a search query. */
    public float[] query(String query) throws IOException {
        return embedder.embed(List.of(parameters.queryPrefix() + query)).vectors()[0];
    }

    /** Identifies the vectors this model gives; part of every cache key. */
    public String fingerprint() {
        return info().fingerprint(parameters.maxTokens());
    }
}
