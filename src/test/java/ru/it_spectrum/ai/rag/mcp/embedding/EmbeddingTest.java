package ru.it_spectrum.ai.rag.mcp.embedding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class EmbeddingTest {

    @TempDir
    Path dir;

    @Test
    void poolingAveragesTheMaskedTokensAndNormalizes() {
        float[][][] states = {{{1, 0}, {3, 0}, {100, 100}}};
        long[][] mask = {{1, 1, 0}};

        float[][] pooled = OnnxEmbedder.pooled(states, mask);

        assertThat(pooled[0][0]).isCloseTo(1f, within(1e-6f));
        assertThat(pooled[0][1]).isCloseTo(0f, within(1e-6f));
    }

    @Test
    void theCacheKeepsVectorsAcrossOpenings() throws IOException {
        Path cacheDir = dir.resolve("cache");
        String key = EmbeddingCache.key("model-a", "passage: text");
        try (EmbeddingCache cache = EmbeddingCache.open(cacheDir)) {
            assertThat(cache.get(key)).isNull();
            cache.put(key, new float[]{0.6f, 0.8f}, true);
            assertThat(cache.get(key).vector()).containsExactly(0.6f, 0.8f);
        }
        try (EmbeddingCache cache = EmbeddingCache.open(cacheDir)) {
            assertThat(cache.get(key).vector()).containsExactly(0.6f, 0.8f);
            assertThat(cache.get(key).cut()).isTrue();
            assertThat(cache.get(EmbeddingCache.key("model-b", "passage: text"))).isNull();
        }
    }

    @Test
    void aSecondBuildRunsWithoutTheCacheWhileTheFirstHoldsIt() throws IOException {
        Path cacheDir = dir.resolve("cache");
        try (EmbeddingCache first = EmbeddingCache.open(cacheDir);
             EmbeddingCache second = EmbeddingCache.open(cacheDir)) {
            first.put("k", new float[]{1f}, false);
            second.put("k", new float[]{1f}, false);
            assertThat(second.get("k")).isNull();
        }
    }

    @Test
    void anUnknownTypeOrMissingFilesAreReported() throws IOException {
        EmbeddingModels models = FakeEmbedder.models(dir, "fake");

        assertThatThrownBy(() -> models.load("fake", new ModelParameters("remote", "", "", 512)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supported: fake");
        assertThatThrownBy(() -> models.load("absent", new ModelParameters(FakeEmbedder.TYPE, "", "", 512)))
                .isInstanceOf(IOException.class).hasMessageContaining("are not in");
        assertThatThrownBy(() -> EmbeddingModels.inProcess(dir).load("fake", new ModelParameters("onnx", "", "", 512)))
                .isInstanceOf(IOException.class).hasMessageContaining("tokenizer.json");
    }

    /**
     * The real model, when {@code RAG_MCP_TEST_MODEL_DIR} names a directory with
     * {@code model.onnx} and {@code tokenizer.json} of a multilingual E5 model.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RAG_MCP_TEST_MODEL_DIR", matches = ".+")
    void theOnnxModelPutsAQueryNearItsPassage() throws IOException {
        Path modelDir = Path.of(System.getenv("RAG_MCP_TEST_MODEL_DIR"));
        // Not under @TempDir: Windows cannot delete the tokenizer library while it is loaded.
        Path nativeCache = Path.of(System.getProperty("java.io.tmpdir"), "lucene-rag-server-test", "djl");
        try (OnnxEmbedder model = OnnxEmbedder.open(modelDir, 512, nativeCache)) {
            float[][] passages = model.embed(List.of(
                    "passage: Отменить можно только платёж, который ещё не привязан к счёту.",
                    "passage: Webhook deliveries are retried with exponential backoff.")).vectors();
            float[] query = model.embed(List.of("query: как отменить оплату")).vectors()[0];

            assertThat(model.info().dimension()).isEqualTo(passages[0].length);
            assertThat(dot(query, passages[0])).isGreaterThan(dot(query, passages[1]));
            assertThat(dot(passages[0], passages[0])).isCloseTo(1f, within(1e-4f));
            Embeddings longText = model.embed(List.of("passage: " + "слово ".repeat(2000)));
            assertThat(longText.truncated()).isEqualTo(1);
        }
    }

    private static float dot(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
