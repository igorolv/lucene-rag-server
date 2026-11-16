package ru.it_spectrum.ai.rag.mcp.embedding;

/**
 * The model an index was built with cannot be used: its files are missing, cannot be loaded or
 * differ from the files the index was built with. Vector and hybrid search fail with it;
 * lexical search does not need the model.
 */
public class ModelUnavailableException extends IllegalStateException {

    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
