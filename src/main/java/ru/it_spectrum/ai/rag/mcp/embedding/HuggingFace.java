package ru.it_spectrum.ai.rag.mcp.embedding;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The files of an ONNX model on Hugging Face, or on a mirror that serves the same paths, for
 * {@code rag-model fetch} (docs/decisions.md D70, D71). Hugging Face publishes the SHA-256 of large
 * files and the git object id of small ones; a download that matches neither is not kept.
 */
public final class HuggingFace {

    /**
     * What to download.
     *
     * @param repository the repository, {@code owner/name}
     * @param revision   the commit the files are taken from
     * @param files      the files and their local names
     * @param pooling    {@code mean} or {@code cls} when the repository says so; {@code null} when it
     *                   does not, and the name of an unsupported mode prefixed by {@code !}
     * @param maxTokens  the input length the model is trained with, when the repository says so
     *                   ({@code max_seq_length} of {@code sentence_bert_config.json}); {@code null}
     *                   when it does not
     */
    public record Source(String repository, String revision, List<ModelCatalog.File> files, String pooling,
                         Integer maxTokens) {

        public Source(String repository, String revision, List<ModelCatalog.File> files, String pooling) {
            this(repository, revision, files, pooling, null);
        }
    }

    /** The default file of the graph in a repository. */
    public static final String DEFAULT_FILE = "onnx/model.onnx";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private HuggingFace() {
    }

    /**
     * Finds the files of a model in a repository at a revision: the graph, the files of its
     * directory it may read weights from, and {@code tokenizer.json} next to the graph or at the root.
     *
     * @param file      path of the graph in the repository; {@link #DEFAULT_FILE} when {@code null}
     * @param tokenizer path of {@code tokenizer.json}; found when {@code null}
     * @throws IOException with what to do when the repository, the revision or a file is not there
     */
    public static Source resolve(HttpClient http, String base, String repository, String revision, String file,
                                 String tokenizer) throws IOException, InterruptedException {
        String graph = file == null ? DEFAULT_FILE : file;
        String rev = revision == null ? "main" : revision;
        JsonNode info = getJson(http, root(base) + "/api/models/" + repository + "/revision/" + encode(rev),
                "repository " + repository + " at revision " + rev);
        String sha = info.path("sha").asString("");
        if (sha.isEmpty()) {
            throw new IOException("Hugging Face gave no commit for " + repository + " at " + rev);
        }
        String dir = graph.contains("/") ? graph.substring(0, graph.lastIndexOf('/')) : "";
        List<JsonNode> entries = tree(http, base, repository, sha, dir);
        Optional<JsonNode> graphEntry = find(entries, graph);
        if (graphEntry.isEmpty()) {
            List<String> onnx = entries.stream().map(e -> e.path("path").asString())
                    .filter(p -> p.endsWith(".onnx")).toList();
            throw new IOException(onnx.isEmpty()
                    ? repository + " has no ONNX export in " + (dir.isEmpty() ? "its root" : dir + "/")
                    + ". Convert the model, for example with: optimum-cli export onnx --model " + repository
                    + " <out-dir>; then put model.onnx, its other files and tokenizer.json into"
                    + " <data-dir>/models/<model>/, or name the graph with --file"
                    : "No " + graph + " in " + repository + "; ONNX files there: " + String.join(", ", onnx)
                    + ". Name one with --file");
        }
        List<ModelCatalog.File> files = new ArrayList<>();
        files.add(file(graphEntry.get(), OnnxEmbeddingModel.MODEL_FILE));
        for (JsonNode entry : entries) {
            String name = name(entry.path("path").asString());
            if (entry.path("type").asString().equals("file") && companion(name)) {
                files.add(file(entry, name));
            }
        }
        JsonNode tokenizerEntry;
        if (tokenizer != null) {
            String tokenizerDir = tokenizer.contains("/") ? tokenizer.substring(0, tokenizer.lastIndexOf('/')) : "";
            tokenizerEntry = find(tokenizerDir.equals(dir) ? entries : tree(http, base, repository, sha, tokenizerDir),
                    tokenizer).orElseThrow(() -> new IOException("No " + tokenizer + " in " + repository));
        } else {
            Optional<JsonNode> near = find(entries, (dir.isEmpty() ? "" : dir + "/") + OnnxEmbeddingModel.TOKENIZER_FILE);
            tokenizerEntry = near.isPresent() ? near.get()
                    : find(dir.isEmpty() ? entries : tree(http, base, repository, sha, ""),
                    OnnxEmbeddingModel.TOKENIZER_FILE).orElseThrow(() -> new IOException("No tokenizer.json in "
                    + repository + " next to " + graph + " or at its root; name one with --tokenizer"));
        }
        files.add(file(tokenizerEntry, OnnxEmbeddingModel.TOKENIZER_FILE));
        return new Source(repository, sha, List.copyOf(files), pooling(http, base, repository, sha),
                maxTokens(http, base, repository, sha));
    }

    /**
     * Downloads a file to a temporary name next to its place and moves it there when its size and
     * hash are the published ones.
     */
    public static void download(HttpClient http, String url, ModelCatalog.File file, Path target)
            throws IOException, InterruptedException {
        Path part = target.resolveSibling("." + target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest gitBlob = digest("SHA-1");
        gitBlob.update(("blob " + file.size() + "\0").getBytes(StandardCharsets.UTF_8));
        long size;
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("GET " + url + " answered " + response.statusCode());
            }
            try (InputStream in = new DigestInputStream(new DigestInputStream(body, sha256), gitBlob);
                 OutputStream written = Files.newOutputStream(part)) {
                size = in.transferTo(written);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e.getMessage() != null && e.getMessage().startsWith("GET ") ? e
                    : new IOException("GET " + url + " failed: " + e, e);
        }
        String hash = HexFormat.of().formatHex(sha256.digest());
        String blob = HexFormat.of().formatHex(gitBlob.digest());
        boolean matches = size == file.size() && (file.sha256() != null ? hash.equals(file.sha256())
                : blob.equals(file.gitBlob()));
        if (!matches) {
            Files.deleteIfExists(part);
            throw new IOException(file.name() + " from " + url + " is not the published file (" + size
                    + " bytes, SHA-256 " + hash + "; expected " + file.size() + " bytes, "
                    + (file.sha256() != null ? "SHA-256 " + file.sha256() : "git object " + file.gitBlob()) + ")");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Whether a file in place is the published one. */
    public static boolean inPlace(ModelCatalog.File file, Path target) throws IOException {
        if (!Files.isRegularFile(target) || Files.size(target) != file.size()) {
            return false;
        }
        MessageDigest sha256 = digest("SHA-256");
        MessageDigest gitBlob = digest("SHA-1");
        gitBlob.update(("blob " + file.size() + "\0").getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new DigestInputStream(new DigestInputStream(Files.newInputStream(target), sha256),
                gitBlob)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return file.sha256() != null ? file.sha256().equals(HexFormat.of().formatHex(sha256.digest()))
                : file.gitBlob().equals(HexFormat.of().formatHex(gitBlob.digest()));
    }

    /** The download address of a file: {@code <base>/<repository>/resolve/<revision>/<path>}. */
    public static String url(String base, String repository, String revision, String path) {
        return root(base) + "/" + repository + "/resolve/" + revision + "/" + path;
    }

    /**
     * Files next to the graph that it may read weights from: everything but other graphs, the
     * tokenizer and the configuration and text files exports carry.
     */
    static boolean companion(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.startsWith(".") && !lower.endsWith(".onnx") && !lower.endsWith(".json")
                && !lower.endsWith(".md") && !lower.endsWith(".txt") && !lower.endsWith(".model");
    }

    /** {@code max_seq_length} of the sentence-transformers configuration, when there is one. */
    private static Integer maxTokens(HttpClient http, String base, String repository, String sha)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create(url(base, repository, sha, "sentence_bert_config.json")))
                .timeout(Duration.ofMinutes(1)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        int length = JSON.readTree(response.body()).path("max_seq_length").asInt(0);
        return length > 0 ? length : null;
    }

    private static String pooling(HttpClient http, String base, String repository, String sha)
            throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create(url(base, repository, sha, "1_Pooling/config.json"))).timeout(Duration.ofMinutes(1))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode config = JSON.readTree(response.body());
        if (config.path("pooling_mode_cls_token").asBoolean(false)) {
            return ModelParameters.CLS;
        }
        if (config.path("pooling_mode_mean_tokens").asBoolean(false)) {
            return ModelParameters.MEAN;
        }
        for (var entry : config.properties()) {
            if (entry.getKey().startsWith("pooling_mode_") && entry.getValue().asBoolean(false)) {
                return "!" + entry.getKey().substring("pooling_mode_".length());
            }
        }
        return null;
    }

    private static List<JsonNode> tree(HttpClient http, String base, String repository, String sha, String dir)
            throws IOException, InterruptedException {
        JsonNode list = getJson(http, root(base) + "/api/models/" + repository + "/tree/" + sha
                + (dir.isEmpty() ? "" : "/" + dir), "the files of " + repository
                + (dir.isEmpty() ? "" : " in " + dir + "/"));
        List<JsonNode> entries = new ArrayList<>();
        list.forEach(entries::add);
        return entries;
    }

    private static Optional<JsonNode> find(List<JsonNode> entries, String path) {
        return entries.stream().filter(e -> e.path("type").asString().equals("file")
                && e.path("path").asString().equals(path)).findFirst();
    }

    private static ModelCatalog.File file(JsonNode entry, String name) {
        JsonNode lfs = entry.path("lfs");
        long size = lfs.isObject() ? lfs.path("size").asLong(entry.path("size").asLong()) : entry.path("size").asLong();
        return new ModelCatalog.File(entry.path("path").asString(), name, size,
                lfs.isObject() ? lfs.path("oid").asString() : null, entry.path("oid").asString());
    }

    private static JsonNode getJson(HttpClient http, String url, String what) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(1)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 404) {
            throw new IOException("Hugging Face does not have " + what + " (HTTP " + response.statusCode()
                    + "); private and gated repositories are not supported");
        }
        if (response.statusCode() != 200) {
            throw new IOException("GET " + url + " answered " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String root(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
