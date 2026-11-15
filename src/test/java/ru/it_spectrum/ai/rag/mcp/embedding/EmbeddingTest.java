package ru.it_spectrum.ai.rag.mcp.embedding;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        float[][] pooled = OnnxEmbeddingModel.pooled(states, mask);

        assertThat(pooled[0][0]).isCloseTo(1f, within(1e-6f));
        assertThat(pooled[0][1]).isCloseTo(0f, within(1e-6f));
    }

    @Test
    void clsPoolingTakesTheFirstTokenAndNormalizes() {
        float[][][] states = {{{3, 4}, {100, 0}}};

        float[][] pooled = OnnxEmbeddingModel.first(states);

        assertThat(pooled[0][0]).isCloseTo(0.6f, within(1e-6f));
        assertThat(pooled[0][1]).isCloseTo(0.8f, within(1e-6f));
        assertThat(states[0][0][0]).isEqualTo(3f);
    }

    @Test
    void meanPoolingKeepsTheCacheKeyItHadAndClsGetsItsOwn() {
        ModelInfo info = new ModelInfo("onnx", 384, Map.of("model.onnx", "aa"));
        String mean = new VectorModel("m", new ModelParameters("onnx", "", "", 512), null, info).fingerprint();
        String cls = new VectorModel("m", new ModelParameters("onnx", "", "", 512, "cls"), null, info).fingerprint();

        assertThat(mean).isEqualTo(info.fingerprint(512));
        assertThat(cls).isNotEqualTo(mean).endsWith("/pooling=cls");
        assertThatThrownBy(() -> new ModelParameters("onnx", "", "", 512, "max"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mean or cls");
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
        EmbeddingModels models = FakeEmbeddingModel.models(dir, "fake");

        assertThatThrownBy(() -> models.load("fake", new ModelParameters("remote", "", "", 512)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supported: fake");
        assertThatThrownBy(() -> models.load("absent", new ModelParameters(FakeEmbeddingModel.TYPE, "", "", 512)))
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
        try (OnnxEmbeddingModel model = OnnxEmbeddingModel.open(modelDir, 512, nativeCache)) {
            List<float[]> embedded = model.embed(List.of(
                    "passage: Отменить можно только платёж, который ещё не привязан к счёту.",
                    "passage: Webhook deliveries are retried with exponential backoff."));
            float[][] passages = {embedded.get(0), embedded.get(1)};
            float[] query = model.embed("query: как отменить оплату");

            assertThat(model.info().dimension()).isEqualTo(passages[0].length);
            assertThat(model.dimensions()).isEqualTo(passages[0].length);
            assertThat(dot(query, passages[0])).isGreaterThan(dot(query, passages[1]));
            assertThat(dot(passages[0], passages[0])).isCloseTo(1f, within(1e-4f));
            VectorModel named = new VectorModel("e5", new ModelParameters("onnx", "", "", 512), model, model.info());
            Embeddings longText = named.embedInputs(List.of("passage: short", "passage: " + "слово ".repeat(2000)));
            assertThat(longText.cut()).containsExactly(false, true);
        }
    }

    /** The real model reports each call as a Spring AI embedding operation (docs/decisions.md D46). */
    @Test
    @EnabledIfEnvironmentVariable(named = "RAG_MCP_TEST_MODEL_DIR", matches = ".+")
    void theOnnxModelIsObservedAsASpringAiEmbeddingModel() throws IOException {
        Path modelDir = Path.of(System.getenv("RAG_MCP_TEST_MODEL_DIR"));
        Path nativeCache = Path.of(System.getProperty("java.io.tmpdir"), "lucene-rag-server-test", "djl");
        List<Observation.Context> seen = new ArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                seen.add(context);
            }
        });
        try (OnnxEmbeddingModel model = OnnxEmbeddingModel.open(modelDir, 512, nativeCache, "e5-small", registry)) {
            model.embed(List.of("passage: one", "passage: two"));
        }

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().getName()).isEqualTo("gen_ai.client.operation");
        assertThat(seen.getFirst().getLowCardinalityKeyValues().stream().map(kv -> kv.getKey() + "=" + kv.getValue()))
                .contains("gen_ai.system=onnx", "gen_ai.request.model=e5-small");
    }

    @Test
    void aModelIsUsedThroughSpringAiAndReportsWhatItCut() throws IOException {
        VectorModel model = FakeEmbeddingModel.models(dir, "fake")
                .load("fake", new ModelParameters(FakeEmbeddingModel.TYPE, "query: ", "passage: ", 4));

        Embeddings embeddings = model.embedInputs(List.of("one two three", "one two three four five six"));

        assertThat(model.model().dimensions()).isEqualTo(model.info().dimension());
        assertThat(embeddings.vectors().length).isEqualTo(2);
        assertThat(embeddings.cut()).containsExactly(false, true);
        assertThat(model.query("one")).hasSize(FakeEmbeddingModel.DIMENSION);
        assertThat(model.embedInputs(List.of()).vectors().length).isZero();
    }

    private static float dot(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}
