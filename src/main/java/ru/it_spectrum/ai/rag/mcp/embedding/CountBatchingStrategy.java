package ru.it_spectrum.ai.rag.mcp.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Batches of at most a number of documents, in order (docs/decisions.md D44). Spring AI's
 * {@code TokenCountBatchingStrategy} counts OpenAI tokens, which says nothing about the input of
 * an in-process model; the model itself cuts each text to its input limit.
 *
 * @param size documents per batch; at least 1
 */
public record CountBatchingStrategy(int size) implements BatchingStrategy {

    public CountBatchingStrategy {
        if (size < 1) {
            throw new IllegalArgumentException("A batch holds at least one document");
        }
    }

    @Override
    public List<List<Document>> batch(List<Document> documents) {
        List<List<Document>> batches = new ArrayList<>();
        for (int from = 0; from < documents.size(); from += size) {
            batches.add(List.copyOf(documents.subList(from, Math.min(documents.size(), from + size))));
        }
        return batches;
    }
}
