package ru.it_spectrum.ai.rag.mcp.embedding;

import io.micrometer.observation.ObservationRegistry;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The model type {@value ModelParameters#ONNX}: {@link OnnxEmbeddingModel} run in-process from the
 * files in {@code <data-dir>/models/<name>/} (docs/decisions.md D11, D24).
 *
 * @param nativeCache  where DJL unpacks its native tokenizer library
 * @param observations where the calls of a model are reported (D46)
 */
public record OnnxModelType(Path nativeCache, ObservationRegistry observations) implements ModelType {

    /** A type whose models report nothing. */
    public OnnxModelType(Path nativeCache) {
        this(nativeCache, ObservationRegistry.NOOP);
    }

    @Override
    public String name() {
        return ModelParameters.ONNX;
    }

    @Override
    public EmbeddingModels.Loaded load(String name, Path dir, ModelParameters parameters) throws IOException {
        OnnxEmbeddingModel model = OnnxEmbeddingModel.open(dir, parameters.maxTokens(), nativeCache, name,
                observations);
        return new EmbeddingModels.Loaded(model, model.info());
    }
}
