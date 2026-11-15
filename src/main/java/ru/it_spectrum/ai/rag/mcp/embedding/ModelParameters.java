package ru.it_spectrum.ai.rag.mcp.embedding;

/**
 * How a model is run, as {@code models.<name>} of {@code collections.json} sets it and the
 * manifest of a collection records it.
 *
 * @param type          the {@link ModelType}: {@value #ONNX} (files run in-process) or
 *                      {@code bean} (a Spring AI bean named as the model, D43)
 * @param queryPrefix   put before a search query, {@code "query: "} for E5; may be empty
 * @param passagePrefix put before a record's text, {@code "passage: "} for E5; may be empty
 * @param maxTokens     input limit in tokens; a longer text is cut
 * @param pooling       how an {@value #ONNX} model makes one vector of its token states:
 *                      {@value #MEAN} (their average, as E5 is trained) or {@value #CLS} (the state
 *                      of the first token, as BGE is trained); other types ignore it
 */
public record ModelParameters(String type, String queryPrefix, String passagePrefix, int maxTokens, String pooling) {

    public static final String ONNX = "onnx";
    public static final String MEAN = "mean";
    public static final String CLS = "cls";
    public static final int DEFAULT_MAX_TOKENS = 512;

    public ModelParameters {
        type = type == null ? ONNX : type;
        queryPrefix = queryPrefix == null ? "" : queryPrefix;
        passagePrefix = passagePrefix == null ? "" : passagePrefix;
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be at least 1");
        }
        pooling = pooling == null ? MEAN : pooling;
        if (!pooling.equals(MEAN) && !pooling.equals(CLS)) {
            throw new IllegalArgumentException("pooling must be " + MEAN + " or " + CLS + ", not '" + pooling + "'");
        }
    }

    /** Parameters with mean pooling. */
    public ModelParameters(String type, String queryPrefix, String passagePrefix, int maxTokens) {
        this(type, queryPrefix, passagePrefix, maxTokens, MEAN);
    }
}
