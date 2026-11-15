package ru.it_spectrum.ai.rag.mcp.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.io.IOException;
import java.util.List;

/**
 * A named model with the parameters it is run with: what the indexer embeds records with and
 * search embeds a query with. Adds the query and passage prefixes the model family expects, and
 * keeps what Spring AI's {@link EmbeddingModel} does not give: the identity of the loaded model
 * for the manifest and the cache.
 *
 * @param name       the name under {@code models} and the directory under {@code <data-dir>/models/}
 * @param parameters how the model is run
 * @param model      the loaded model (docs/decisions.md D29)
 * @param info       what identifies the loaded model
 */
public record VectorModel(String name, ModelParameters parameters, EmbeddingModel model, ModelInfo info) {

    /** The text a record's passage is embedded as: the passage prefix and the passage. */
    public String passageInput(String passage) {
        return parameters.passagePrefix() + passage;
    }

    /**
     * Vectors of passages already prefixed by {@link #passageInput}.
     *
     * @throws IOException when the model fails to run
     */
    public Embeddings embedInputs(List<String> inputs) throws IOException {
        if (inputs.isEmpty()) {
            return new Embeddings(new float[0][], new boolean[0]);
        }
        try {
            return Embeddings.of(model.call(new EmbeddingRequest(inputs, EmbeddingOptions.builder().build())),
                    inputs.size());
        } catch (RuntimeException e) {
            throw e.getCause() instanceof IOException io ? io
                    : new IOException("Model '" + name + "' failed to run: " + e.getMessage(), e);
        }
    }

    /** The vector of a search query. */
    public float[] query(String query) throws IOException {
        return embedInputs(List.of(parameters.queryPrefix() + query)).vectors()[0];
    }

    /** Identifies the vectors this model gives; part of every cache key. */
    public String fingerprint() {
        return info.fingerprint(parameters.maxTokens());
    }
}
