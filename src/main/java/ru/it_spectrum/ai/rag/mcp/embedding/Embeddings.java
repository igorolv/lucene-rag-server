package ru.it_spectrum.ai.rag.mcp.embedding;

import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Vectors of a batch of texts.
 *
 * @param vectors one vector per text, in the order of the texts
 * @param cut     per text: it was longer than the model's input limit and only its beginning was
 *                embedded
 */
public record Embeddings(float[][] vectors, boolean[] cut) {

    /**
     * Key of the response metadata under which a Spring AI {@code EmbeddingModel} of this server
     * lists the indexes of the inputs it cut to its input limit. A model that does not report it,
     * such as a remote one, is taken to have cut nothing.
     */
    public static final String TRUNCATED = "truncated";

    /** How many texts were cut. */
    public int truncated() {
        int count = 0;
        for (boolean c : cut) {
            if (c) {
                count++;
            }
        }
        return count;
    }

    /** Response metadata naming the model and the inputs that were cut. */
    public EmbeddingResponseMetadata metadata(String model) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < cut.length; i++) {
            if (cut[i]) {
                indexes.add(i);
            }
        }
        return new EmbeddingResponseMetadata(model, new EmptyUsage(), Map.of(TRUNCATED, List.copyOf(indexes)));
    }

    /**
     * The vectors of a response to {@code count} inputs, placed by the index of each result.
     *
     * @throws IOException when the response does not hold one vector per input
     */
    public static Embeddings of(EmbeddingResponse response, int count) throws IOException {
        float[][] vectors = new float[count][];
        List<Embedding> results = response.getResults();
        for (int position = 0; position < results.size(); position++) {
            Embedding result = results.get(position);
            int index = result.getIndex() == null ? position : result.getIndex();
            if (index < 0 || index >= count) {
                throw new IOException("The model returned a vector with index " + index + " for " + count + " texts");
            }
            vectors[index] = result.getOutput();
        }
        for (float[] vector : vectors) {
            if (vector == null) {
                throw new IOException("The model returned " + results.size() + " vectors for " + count + " texts");
            }
        }
        boolean[] cut = new boolean[count];
        Object truncated = response.getMetadata() == null ? null : response.getMetadata().get(TRUNCATED);
        if (truncated instanceof Collection<?> indexes) {
            for (Object index : indexes) {
                if (index instanceof Number number && number.intValue() >= 0 && number.intValue() < count) {
                    cut[number.intValue()] = true;
                }
            }
        }
        return new Embeddings(vectors, cut);
    }
}
