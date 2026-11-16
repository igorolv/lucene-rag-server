package ru.it_spectrum.ai.rag.mcp.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;
import ru.it_spectrum.ai.rag.mcp.search.SearchMode;
import ru.it_spectrum.ai.rag.mcp.search.SearchPage;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A remote embedding model of an OpenAI-compatible endpoint, configured by Spring AI's properties
 * and used as a model of type {@code bean} (docs/decisions.md D43, D67). The endpoint is a stub on
 * the loopback interface that answers {@code /v1/embeddings} as the OpenAI API does, so the test
 * needs no network.
 */
@SpringBootTest(properties = {
        "spring.ai.mcp.server.enabled=false",
        "spring.ai.mcp.server.annotation-scanner.enabled=false",
        "logging.config=classpath:logback-cli.xml",
        "spring.ai.model.embedding=openai",
        "spring.ai.openai.api-key=",
        "spring.ai.openai.embedding.model=stub-embedding"})
class OpenAiModelTest {

    static final String BEAN = "openAiEmbeddingModel";
    static final int DIMENSION = 16;
    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final AtomicInteger requests = new AtomicInteger();
    static final HttpServer endpoint = start();

    @TempDir
    Path dataDir;

    @Autowired
    EmbeddingModels models;

    @DynamicPropertySource
    static void endpoint(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url",
                () -> "http://127.0.0.1:" + endpoint.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stop() {
        endpoint.stop(0);
    }

    /** An OpenAI-style embeddings endpoint: a vector of letter counts per input, as floats or base64. */
    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/embeddings", exchange -> {
                requests.incrementAndGet();
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
                boolean base64 = "base64".equals(request.path("encoding_format").asString(""));
                JsonNode input = request.get("input");
                List<String> texts = input.isArray()
                        ? input.valueStream().map(JsonNode::asString).toList() : List.of(input.asString());
                ObjectNode response = JSON.createObjectNode().put("object", "list").put("model", "stub-embedding");
                ArrayNode data = response.putArray("data");
                for (int i = 0; i < texts.size(); i++) {
                    float[] vector = vector(texts.get(i));
                    ObjectNode item = data.addObject().put("object", "embedding").put("index", i);
                    if (base64) {
                        ByteBuffer bytes = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                        for (float v : vector) {
                            bytes.putFloat(v);
                        }
                        item.put("embedding", Base64.getEncoder().encodeToString(bytes.array()));
                    } else {
                        ArrayNode values = item.putArray("embedding");
                        for (float v : vector) {
                            values.add(v);
                        }
                    }
                }
                response.putObject("usage").put("prompt_tokens", 1).put("total_tokens", 1);
                byte[] body = JSON.writeValueAsBytes(response);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static float[] vector(String text) {
        float[] vector = new float[DIMENSION];
        for (char c : text.toLowerCase().toCharArray()) {
            if (Character.isLetter(c)) {
                vector[c % DIMENSION] += 1;
            }
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = norm == 0 ? 0 : (float) (vector[i] / Math.sqrt(norm));
        }
        return vector;
    }

    @Test
    void aCollectionIsBuiltAndSearchedWithTheRemoteModel() throws IOException {
        String root = TestKnowledge.root().toString().replace('\\', '/');
        Path file = dataDir.resolve("collections.json");
        Files.writeString(file, """
                {"models": {"%s": {"type": "bean", "queryPrefix": "query: ", "passagePrefix": "passage: "}},
                 "collections": {"docs@billing": {"vectors": {"model": "%s"},
                                                  "sources": [{"path": "%s/articles"}]}}}
                """.formatted(BEAN, BEAN, root), StandardCharsets.UTF_8);
        IndexDefinition definition = StepRegistry.builtIn().checked(IndexesLoader.load(file, n -> null))
                .getFirst();

        int before = requests.get();
        IndexBuilder.build(definition, StepRegistry.builtIn(), models, dataDir, "test");
        assertThat(requests.get()).isGreaterThan(before);

        IndexStore store = new IndexStore(dataDir);
        Manifest.Vectors vectors = store.manifest("docs@billing").orElseThrow().vectors();
        assertThat(vectors.type()).isEqualTo(BeanModelType.NAME);
        assertThat(vectors.dimension()).isEqualTo(DIMENSION);
        assertThat(vectors.files()).containsEntry(BeanModelType.IDENTITY_BEAN, BEAN)
                .containsEntry(BeanModelType.IDENTITY_MODEL, "stub-embedding");

        SearchPage page = new SearchService(store, new RagMcpProperties(dataDir.toString(), null), models)
                .search(List.of("docs@billing"), "возврат платежа", null, null, null, "vector");
        assertThat(page.mode()).isEqualTo(SearchMode.VECTOR);
        assertThat(page.hits()).isNotEmpty();
        assertThat(page.notice()).isNull();
    }
}
