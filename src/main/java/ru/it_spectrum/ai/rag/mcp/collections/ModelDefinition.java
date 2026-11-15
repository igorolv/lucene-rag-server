package ru.it_spectrum.ai.rag.mcp.collections;

/**
 * One entry of the top-level {@code models} object of {@code collections.json}: an embedding model
 * a collection names in {@code vectors.model}. Its files are in {@code <data-dir>/models/<name>/}.
 *
 * @param name          the key under {@code models}
 * @param type          how the model is run; {@code onnx} when absent
 * @param queryPrefix   put before a search query; empty when absent
 * @param passagePrefix put before the text of a record; empty when absent
 * @param maxTokens     input limit in tokens; 512 when absent
 * @param pooling       {@code mean} or {@code cls}; {@code mean} when absent
 */
public record ModelDefinition(String name, String type, String queryPrefix, String passagePrefix, int maxTokens,
                              String pooling) {

    public static final String DEFAULT_TYPE = "onnx";
    public static final int DEFAULT_MAX_TOKENS = 512;
    public static final String DEFAULT_POOLING = "mean";

    /** A definition with mean pooling. */
    public ModelDefinition(String name, String type, String queryPrefix, String passagePrefix, int maxTokens) {
        this(name, type, queryPrefix, passagePrefix, maxTokens, DEFAULT_POOLING);
    }
}
