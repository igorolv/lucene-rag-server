package ru.it_spectrum.ai.rag.mcp.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;

import java.io.IOException;
import java.util.ArrayList;
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

    /** Documents per call of the model when documents are embedded (docs/decisions.md D44). */
    public static final BatchingStrategy BATCHES = new CountBatchingStrategy(64);

    /**
     * Vectors of documents, each embedded as its content formatted for {@link MetadataMode#EMBED},
     * in batches of {@link #BATCHES}. Not {@code EmbeddingModel.embed(documents, …)}: that keeps
     * neither the formatted content nor which inputs the model cut.
     *
     * @throws IOException when the model fails to run
     */
    public Embeddings embed(List<Document> documents) throws IOException {
        List<float[]> vectors = new ArrayList<>(documents.size());
        List<Boolean> cut = new ArrayList<>(documents.size());
        for (List<Document> batch : BATCHES.batch(documents)) {
            Embeddings embeddings = embedInputs(batch.stream().map(d -> d.getFormattedContent(MetadataMode.EMBED))
                    .toList());
            for (int i = 0; i < batch.size(); i++) {
                vectors.add(embeddings.vectors()[i]);
                cut.add(embeddings.cut()[i]);
            }
        }
        boolean[] cuts = new boolean[cut.size()];
        for (int i = 0; i < cuts.length; i++) {
            cuts[i] = cut.get(i);
        }
        return new Embeddings(vectors.toArray(float[][]::new), cuts);
    }

    /**
     * Vectors of texts as they are, prefixes included.
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
        // Mean pooling keeps the fingerprint it had before pooling was a parameter, so cached
        // vectors stay valid.
        String fingerprint = info.fingerprint(parameters.maxTokens());
        return ModelParameters.MEAN.equals(parameters.pooling()) ? fingerprint
                : fingerprint + "/pooling=" + parameters.pooling();
    }
}
