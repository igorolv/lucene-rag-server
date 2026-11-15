package ru.it_spectrum.ai.rag.mcp.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelCatalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code rag-model} against a stub of Hugging Face on the loopback interface: its API for
 * revisions and file lists, and downloads it redirects as Hugging Face does. No network is needed
 * (docs/decisions.md D70, D71).
 */
class RagModelCommandTest {

    static final byte[] MODEL = "not really a model".getBytes(StandardCharsets.UTF_8);
    static final byte[] DATA = "weights kept outside the graph".getBytes(StandardCharsets.UTF_8);
    static final byte[] CONSTANT = "a small external tensor".getBytes(StandardCharsets.UTF_8);
    static final byte[] TOKENIZER = "{\"tokenizer\": true}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dataDir;

    HttpServer server;
    String base;
    final Map<String, byte[]> served = new ConcurrentHashMap<>();
    final AtomicInteger downloads = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/resolve/") && !path.startsWith("/cdn/")) {
                exchange.getResponseHeaders().add("Location", "/cdn" + path);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] body = served.get(path.startsWith("/cdn/") ? path.substring("/cdn".length()) : path);
            if (body == null) {
                exchange.sendResponseHeaders(path.startsWith("/api/") ? 401 : 404, -1);
                exchange.close();
                return;
            }
            if (path.startsWith("/cdn/")) {
                downloads.incrementAndGet();
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String hash(String algorithm, byte[] prefix, byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        digest.update(prefix);
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hash("SHA-256", new byte[0], bytes);
    }

    private static String gitBlob(byte[] bytes) throws Exception {
        return hash("SHA-1", ("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8), bytes);
    }

    /** A file entry of the Hugging Face tree API: large files carry the SHA-256 of their LFS object. */
    private static String entry(String path, byte[] bytes, boolean lfs) throws Exception {
        return "{\"type\":\"file\",\"path\":\"" + path + "\",\"size\":" + bytes.length + ",\"oid\":\""
                + gitBlob(bytes) + "\"" + (lfs ? ",\"lfs\":{\"oid\":\"" + sha256(bytes) + "\",\"size\":"
                + bytes.length + "}" : "") + "}";
    }

    private ModelCatalog.Model catalogModel() throws Exception {
        return new ModelCatalog.Model("tiny", "test/tiny-model", "abc123", "MIT", 8, "a test model", List.of(
                new ModelCatalog.File("onnx/model.onnx", "model.onnx", MODEL.length, sha256(MODEL)),
                new ModelCatalog.File("onnx/tokenizer.json", "tokenizer.json", TOKENIZER.length, sha256(TOKENIZER))));
    }

    private void serveCatalogModel(byte[] model) {
        served.put("/test/tiny-model/resolve/abc123/onnx/model.onnx", model);
        served.put("/test/tiny-model/resolve/abc123/onnx/tokenizer.json", TOKENIZER);
    }

    /** A repository whose graph keeps weights in two files beside it and whose tokenizer is at the root. */
    private void serveRepository(String pooling) throws Exception {
        served.put("/api/models/test/bge-like/revision/main", "{\"sha\":\"def456\"}".getBytes(StandardCharsets.UTF_8));
        served.put("/api/models/test/bge-like/tree/def456/onnx", ("[" + entry("onnx/model.onnx", MODEL, true) + ","
                + entry("onnx/model.onnx_data", DATA, true) + "," + entry("onnx/Constant_7_attr__value", CONSTANT, false)
                + "," + entry("onnx/config.json", "{}".getBytes(StandardCharsets.UTF_8), false) + ","
                + entry("onnx/model_O4.onnx", MODEL, true) + "]").getBytes(StandardCharsets.UTF_8));
        served.put("/api/models/test/bge-like/tree/def456", ("[{\"type\":\"directory\",\"path\":\"onnx\"},"
                + entry("tokenizer.json", TOKENIZER, false) + "]").getBytes(StandardCharsets.UTF_8));
        served.put("/test/bge-like/resolve/def456/onnx/model.onnx", MODEL);
        served.put("/test/bge-like/resolve/def456/onnx/model.onnx_data", DATA);
        served.put("/test/bge-like/resolve/def456/onnx/Constant_7_attr__value", CONSTANT);
        served.put("/test/bge-like/resolve/def456/tokenizer.json", TOKENIZER);
        if (pooling != null) {
            served.put("/test/bge-like/resolve/def456/1_Pooling/config.json", pooling.getBytes(StandardCharsets.UTF_8));
            served.put("/test/bge-like/resolve/def456/sentence_bert_config.json",
                    "{\"max_seq_length\": 256}".getBytes(StandardCharsets.UTF_8));
        }
    }

    private String fetchCatalog(ModelCatalog.Model model, int expectedCode) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = RagModelCommand.fetch(model.name(), ModelCatalog.source(model), model.collectionsEntry(), false,
                dataDir.resolve("models"), base, RagModelCommand.http(),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertThat(code).as(out).isEqualTo(expectedCode);
        return out;
    }

    private String run(int expectedCode, String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = RagModelCommand.run(args, new RagMcpProperties(dataDir.toString(), null),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertThat(code).as(out).isEqualTo(expectedCode);
        return out;
    }

    private List<String> leftovers(String model) throws IOException {
        try (Stream<Path> files = Files.list(dataDir.resolve("models").resolve(model))) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(".")).toList();
        }
    }

    @Test
    void theFilesAreDownloadedVerifiedAndNotDownloadedAgain() throws Exception {
        serveCatalogModel(MODEL);

        String first = fetchCatalog(catalogModel(), 0);
        assertThat(Files.readAllBytes(dataDir.resolve("models/tiny/model.onnx"))).isEqualTo(MODEL);
        assertThat(Files.readAllBytes(dataDir.resolve("models/tiny/tokenizer.json"))).isEqualTo(TOKENIZER);
        assertThat(first).contains("model.onnx: ", "SHA-256 verified", "\"tiny\": { \"type\": \"onnx\"",
                "RESULT: OK (tiny fetched)").doesNotContain("model card");
        assertThat(Files.readString(dataDir.resolve("models/tiny/source.json")))
                .contains("\"repository\" : \"test/tiny-model\"", "\"revision\" : \"abc123\"");
        assertThat(downloads.get()).isEqualTo(2);

        String second = fetchCatalog(catalogModel(), 0);
        assertThat(second).contains("model.onnx: already there", "tokenizer.json: already there");
        assertThat(downloads.get()).isEqualTo(2);
        assertThat(leftovers("tiny")).isEmpty();
    }

    @Test
    void aFileThatIsNotThePublishedOneIsNotKept() throws Exception {
        serveCatalogModel("something else".getBytes(StandardCharsets.UTF_8));

        String out = fetchCatalog(catalogModel(), 4);

        assertThat(out).contains("is not the published file");
        assertThat(dataDir.resolve("models/tiny/model.onnx")).doesNotExist();
        assertThat(leftovers("tiny")).isEmpty();
    }

    @Test
    void aMissingFileIsReported() throws Exception {
        String out = fetchCatalog(catalogModel(), 4);

        assertThat(out).contains("answered 404");
        assertThat(leftovers("tiny")).isEmpty();
    }

    @Test
    void aRepositoryIsFetchedWithTheFilesBesideItsGraphAndItsPooling() throws Exception {
        serveRepository("{\"pooling_mode_cls_token\": true, \"pooling_mode_mean_tokens\": false}");

        String out = run(0, "fetch", "bge-like", "--repo", "test/bge-like", "--from", base);

        Path dir = dataDir.resolve("models/bge-like");
        assertThat(Files.readAllBytes(dir.resolve("model.onnx"))).isEqualTo(MODEL);
        assertThat(Files.readAllBytes(dir.resolve("model.onnx_data"))).isEqualTo(DATA);
        assertThat(Files.readAllBytes(dir.resolve("Constant_7_attr__value"))).isEqualTo(CONSTANT);
        assertThat(Files.readAllBytes(dir.resolve("tokenizer.json"))).isEqualTo(TOKENIZER);
        assertThat(dir.resolve("config.json")).doesNotExist();
        assertThat(dir.resolve("model_O4.onnx")).doesNotExist();
        assertThat(out).contains("revision def456", "model.onnx_data: ", "SHA-256 verified",
                "Constant_7_attr__value: ", "git object id verified", "pooling: cls, as the repository says", "input length: 256 tokens",
                "\"maxTokens\": 256, \"pooling\": \"cls\"",
                "set queryPrefix and passagePrefix as the model card of test/bge-like says",
                "RESULT: OK (bge-like fetched)");
        assertThat(Files.readString(dir.resolve("source.json"))).contains("\"revision\" : \"def456\"",
                "\"pooling\" : \"cls\"", "\"gitBlob\" : \"" + gitBlob(CONSTANT) + "\"");
        assertThat(leftovers("bge-like")).isEmpty();
    }

    @Test
    void aRepositoryWithoutPoolingOrWithAnUnsupportedOneSaysSo() throws Exception {
        serveRepository(null);
        assertThat(run(0, "fetch", "plain", "--repo", "test/bge-like", "--from", base))
                .contains("mean is assumed, check the model card", "512 is assumed", "\"maxTokens\": 512")
                .doesNotContain("\"pooling\"");

        serveRepository("{\"pooling_mode_max_tokens\": true}");
        assertThat(run(0, "fetch", "maxed", "--repo", "test/bge-like", "--from", base))
                .contains("uses max_tokens pooling, which this server does not support");
    }

    @Test
    void aRepositoryWithoutTheGraphNamesTheGraphsItHasOrSaysToConvert() throws Exception {
        serveRepository(null);
        assertThat(run(4, "fetch", "x", "--repo", "test/bge-like", "--file", "onnx/other.onnx", "--from", base))
                .contains("No onnx/other.onnx in test/bge-like; ONNX files there: onnx/model.onnx, onnx/model_O4.onnx");

        served.put("/api/models/test/torch-only/revision/main", "{\"sha\":\"0f0\"}".getBytes(StandardCharsets.UTF_8));
        served.put("/api/models/test/torch-only/tree/0f0/onnx", "[]".getBytes(StandardCharsets.UTF_8));
        assertThat(run(4, "fetch", "x", "--repo", "test/torch-only", "--from", base))
                .contains("test/torch-only has no ONNX export in onnx/", "optimum-cli export onnx");

        assertThat(run(4, "fetch", "x", "--repo", "test/absent", "--from", base))
                .contains("Hugging Face does not have repository test/absent at revision main (HTTP 401)");
    }

    @Test
    void listShowsTheCatalogAndWhatIsInPlace() {
        assertThat(run(0, "list")).contains("e5-small", "384 dimensions", "not fetched",
                "intfloat/multilingual-e5-small", "RESULT: OK (2 models)");
    }

    @Test
    void anUnknownModelWithoutARepositoryIsAUsageError() {
        assertThat(run(2, "fetch", "gpt")).contains("unknown model 'gpt'; known: e5-small, e5-base",
                "name its Hugging Face repository with --repo");
        assertThat(run(2, "fetch", "../up", "--repo", "a/b")).contains("model name '../up' must be");
        assertThat(run(2, "fetch", "e5-small", "--file", "x.onnx")).contains("Usage:");
    }
}
