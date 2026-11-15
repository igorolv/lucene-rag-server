package ru.it_spectrum.ai.rag.mcp.embedding;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The model type {@value ModelParameters#ONNX}: {@link OnnxEmbeddingModel} run in-process from the
 * files in {@code <data-dir>/models/<name>/} (docs/decisions.md D11, D24).
 *
 * @param nativeCache where DJL unpacks its native tokenizer library
 */
public record OnnxModelType(Path nativeCache) implements ModelType {

    @Override
    public String name() {
        return ModelParameters.ONNX;
    }

    @Override
    public EmbeddingModels.Loaded load(String name, Path dir, ModelParameters parameters) throws IOException {
        OnnxEmbeddingModel model = OnnxEmbeddingModel.open(dir, parameters.maxTokens(), nativeCache);
        return new EmbeddingModels.Loaded(model, model.info());
    }
}
