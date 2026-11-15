package ru.it_spectrum.ai.rag.mcp.embedding;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A deterministic stand-in for a real model, for tests without model files: every word adds one
 * to the coordinate its first four letters hash to, so texts sharing word stems are close. Its
 * "model file" is {@value #FILE}, whose hash is the fingerprint; changing the file makes another
 * model.
 */
public final class FakeEmbeddingModel implements EmbeddingModel {

    public static final String TYPE = "fake";
    public static final String FILE = "model.onnx";
    public static final int DIMENSION = 64;

    private final int maxTokens;

    private FakeEmbeddingModel(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public static EmbeddingModels.Loaded load(Path dir, ModelParameters parameters) throws IOException {
        Path file = dir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Model file " + file + " is missing");
        }
        return new EmbeddingModels.Loaded(new FakeEmbeddingModel(parameters.maxTokens()),
                new ModelInfo(TYPE, DIMENSION, Map.of(FILE, OnnxEmbeddingModel.sha256(file))));
    }

    /** Models of {@code dataDir}, with a model named {@code name} of type {@value #TYPE}. */
    public static EmbeddingModels models(Path dataDir, String name) throws IOException {
        Path dir = dataDir.resolve(EmbeddingModels.MODELS_DIR).resolve(name);
        Files.createDirectories(dir);
        if (!Files.exists(dir.resolve(FILE))) {
            Files.writeString(dir.resolve(FILE), "fake model 1", StandardCharsets.UTF_8);
        }
        return new EmbeddingModels(dataDir.resolve(EmbeddingModels.MODELS_DIR), Map.of(TYPE, FakeEmbeddingModel::load));
    }

    @Override
    public int dimensions() {
        return DIMENSION;
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        float[][] vectors = new float[texts.size()][];
        boolean[] cut = new boolean[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            String[] words = texts.get(i).toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+");
            float[] vector = new float[DIMENSION];
            int tokens = 0;
            for (String word : words) {
                if (word.length() < 3) {
                    continue;
                }
                if (++tokens > maxTokens) {
                    cut[i] = true;
                    break;
                }
                String stem = word.substring(0, Math.min(4, word.length()));
                vector[Math.floorMod(stem.hashCode(), DIMENSION)] += 1;
            }
            vector[0] += 0.01f;
            OnnxEmbeddingModel.normalize(vector);
            vectors[i] = vector;
        }
        List<Embedding> results = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            results.add(new Embedding(vectors[i], i));
        }
        return new EmbeddingResponse(results, new Embeddings(vectors, cut).metadata(TYPE));
    }
}
