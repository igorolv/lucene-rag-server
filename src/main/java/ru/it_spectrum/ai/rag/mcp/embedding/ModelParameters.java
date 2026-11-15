package ru.it_spectrum.ai.rag.mcp.embedding;

/**
 * How a model is run, as {@code models.<name>} of {@code collections.json} sets it and the
 * manifest of a collection records it.
 *
 * @param type          implementation; {@value #ONNX} is the only one
 * @param queryPrefix   put before a search query, {@code "query: "} for E5; may be empty
 * @param passagePrefix put before a record's text, {@code "passage: "} for E5; may be empty
 * @param maxTokens     input limit in tokens; a longer text is cut
 */
public record ModelParameters(String type, String queryPrefix, String passagePrefix, int maxTokens) {

    public static final String ONNX = "onnx";
    public static final int DEFAULT_MAX_TOKENS = 512;

    public ModelParameters {
        type = type == null ? ONNX : type;
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
        passagePrefix = passagePrefix == null ? "" : passagePrefix;
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be at least 1");
        }
    }
}
