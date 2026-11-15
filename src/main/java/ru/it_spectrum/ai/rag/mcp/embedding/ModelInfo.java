package ru.it_spectrum.ai.rag.mcp.embedding;

import java.util.Map;
import java.util.TreeMap;

/**
 * What identifies a loaded model: two models with equal info give the same vectors.
 *
 * @param type      implementation, as in {@link ModelParameters#type()}
 * @param dimension length of a vector
 * @param files     SHA-256 of each model file, by file name
 */
public record ModelInfo(String type, int dimension, Map<String, String> files) {

    public ModelInfo {
        files = files == null ? Map.of() : Map.copyOf(new TreeMap<>(files));
    }

    /** One string for the model, its input limit and nothing else; part of every cache key. */
    public String fingerprint(int maxTokens) {
        StringBuilder result = new StringBuilder(type).append('/').append(dimension).append('/').append(maxTokens);
        new TreeMap<>(files).forEach((name, hash) -> result.append('/').append(name).append('=').append(hash));
        return result.toString();
    }
}
