package ru.it_spectrum.ai.rag.mcp.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * A transformer encoder run in-process by ONNX Runtime, read from a directory with
 * {@code model.onnx} and the Hugging Face {@code tokenizer.json} that belongs to it — the layout of
 * the {@code onnx/} folder of the multilingual E5 models (docs/decisions.md D11).
 *
 * <p>A text is tokenized, cut to {@code maxTokens}, run through the graph, and the vector is the
 * mean of the token states under the attention mask, scaled to unit length: the pooling E5 is
 * trained with. A graph that already returns one vector per text ({@code sentence_embedding}) is
 * only normalized.
 *
 * <p>DJL's tokenizer library would otherwise probe a cloud metadata address for telemetry and keep
 * its native library under the home directory; both are switched off or redirected before it is
 * first used, unless the operator set those properties.
 *
 * <p>It is a Spring AI {@link EmbeddingModel} (docs/decisions.md D29) and not Spring AI's
 * {@code TransformersEmbeddingModel}, for the reasons of D28. The response says which inputs were
 * cut to the input limit under {@link Embeddings#TRUNCATED}. Safe for use by several threads.
 */
public final class OnnxEmbeddingModel implements EmbeddingModel, AutoCloseable {

    public static final String MODEL_FILE = "model.onnx";
    public static final String TOKENIZER_FILE = "tokenizer.json";

    /** Texts per run of the graph; texts of similar length are run together. */
    private static final int BATCH = 16;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final Set<String> inputs;
    private final String output;
    private final ModelInfo info;

    private OnnxEmbeddingModel(OrtEnvironment environment, OrtSession session, HuggingFaceTokenizer tokenizer,
                         String output, Map<String, String> files) throws IOException {
        this.environment = environment;
        this.session = session;
        this.tokenizer = tokenizer;
        this.output = output;
        this.inputs = session.getInputNames();
        int dimension = declaredDimension(session, output);
        ModelInfo probe = new ModelInfo(ModelParameters.ONNX, dimension, files);
        this.info = dimension > 0 ? probe
                : new ModelInfo(ModelParameters.ONNX, run(List.of("probe"))[0].length, files);
    }

    /**
     * @param dir         directory with {@value #MODEL_FILE} and {@value #TOKENIZER_FILE}
     * @param maxTokens   input limit; a longer text is cut
     * @param nativeCache where DJL unpacks its native tokenizer library, unless set otherwise
     * @throws IOException when a file is missing or the model cannot be loaded
     */
    public static OnnxEmbeddingModel open(Path dir, int maxTokens, Path nativeCache) throws IOException {
        Path model = dir.resolve(MODEL_FILE);
        Path tokenizerFile = dir.resolve(TOKENIZER_FILE);
        for (Path file : List.of(model, tokenizerFile)) {
            if (!Files.isRegularFile(file)) {
                throw new IOException("Model file " + file + " is missing");
            }
        }
        Map<String, String> files = new LinkedHashMap<>();
        files.put(MODEL_FILE, sha256(model));
        files.put(TOKENIZER_FILE, sha256(tokenizerFile));
        quietDjl(nativeCache);
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerFile)
                .optAddSpecialTokens(true)
                .optTruncation(true)
                .optMaxLength(maxTokens)
                .optPadding(true)
                .build();
        OrtSession session = null;
        try {
            OrtEnvironment environment = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR,
                    "lucene-rag-server");
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
                session = environment.createSession(model.toString(), options);
            }
            Set<String> outputs = session.getOutputNames();
            String output = outputs.contains("last_hidden_state") ? "last_hidden_state"
                    : outputs.contains("sentence_embedding") ? "sentence_embedding" : outputs.iterator().next();
            Set<String> inputs = session.getInputNames();
            if (!inputs.contains("input_ids") || !inputs.contains("attention_mask")) {
                throw new IOException("The model " + model + " has inputs " + inputs
                        + "; input_ids and attention_mask are required");
            }
            return new OnnxEmbeddingModel(environment, session, tokenizer, output, files);
        } catch (OrtException | IOException | RuntimeException e) {
            tokenizer.close();
            if (session != null) {
                try {
                    session.close();
                } catch (OrtException ignored) {
                    // the load already failed
                }
            }
            throw e instanceof IOException io ? io
                    : new IOException("Cannot load the model " + model + ": " + e.getMessage(), e);
        }
    }

    /** What was loaded: the type, the vector size and the fingerprints of the model files. */
    public ModelInfo info() {
        return info;
    }

    @Override
    public int dimensions() {
        return info.dimension();
    }

    /**
     * @throws UncheckedIOException when the model fails to run
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        final Embeddings embeddings;
        try {
            embeddings = embedTexts(request.getInstructions());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Embedding> results = new ArrayList<>(embeddings.vectors().length);
        for (int i = 0; i < embeddings.vectors().length; i++) {
            results.add(new Embedding(embeddings.vectors()[i], i));
        }
        return new EmbeddingResponse(results, embeddings.metadata(MODEL_FILE));
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    private Embeddings embedTexts(List<String> texts) throws IOException {
        float[][] vectors = new float[texts.size()][];
        boolean[] cut = new boolean[texts.size()];
        // Texts of similar length in one batch pad less.
        List<Integer> order = IntStream.range(0, texts.size()).boxed()
                .sorted(Comparator.comparingInt(i -> texts.get(i).length())).toList();
        for (int from = 0; from < order.size(); from += BATCH) {
            List<Integer> batch = order.subList(from, Math.min(order.size(), from + BATCH));
            List<String> batchTexts = new ArrayList<>(batch.size());
            batch.forEach(i -> batchTexts.add(texts.get(i)));
            Encoding[] encodings = tokenizer.batchEncode(batchTexts);
            float[][] result = run(encodings);
            for (int j = 0; j < batch.size(); j++) {
                vectors[batch.get(j)] = result[j];
                cut[batch.get(j)] = encodings[j].exceedMaxLength();
            }
        }
        return new Embeddings(vectors, cut);
    }

    private float[][] run(List<String> texts) throws IOException {
        return run(tokenizer.batchEncode(texts));
    }

    private float[][] run(Encoding[] encodings) throws IOException {
        int n = encodings.length;
        long[][] ids = new long[n][];
        long[][] mask = new long[n][];
        long[][] types = new long[n][];
        for (int i = 0; i < n; i++) {
            ids[i] = encodings[i].getIds();
            mask[i] = encodings[i].getAttentionMask();
            types[i] = encodings[i].getTypeIds();
        }
        Map<String, OnnxTensor> feed = new LinkedHashMap<>();
        try {
            feed.put("input_ids", OnnxTensor.createTensor(environment, ids));
            feed.put("attention_mask", OnnxTensor.createTensor(environment, mask));
            if (inputs.contains("token_type_ids")) {
                feed.put("token_type_ids", OnnxTensor.createTensor(environment, types));
            }
            try (OrtSession.Result result = session.run(feed, Set.of(output))) {
                OnnxValue value = result.get(output).orElseThrow(() -> new IOException("The model gave no " + output));
                Object raw = value.getValue();
                if (raw instanceof float[][][] states) {
                    return pooled(states, mask);
                }
                if (raw instanceof float[][] pooled) {
                    for (float[] vector : pooled) {
                        normalize(vector);
                    }
                    return pooled;
                }
                throw new IOException("The model output " + output + " is not a float tensor of rank 2 or 3");
            }
        } catch (OrtException e) {
            throw new IOException("The model failed to run: " + e.getMessage(), e);
        } finally {
            feed.values().forEach(OnnxTensor::close);
        }
    }

    /** Mean of the token states under the attention mask, scaled to unit length. */
    static float[][] pooled(float[][][] states, long[][] mask) {
        float[][] result = new float[states.length][];
        for (int i = 0; i < states.length; i++) {
            int dimension = states[i][0].length;
            float[] sum = new float[dimension];
            int count = 0;
            for (int t = 0; t < states[i].length; t++) {
                if (mask[i][t] == 0) {
                    continue;
                }
                count++;
                for (int d = 0; d < dimension; d++) {
                    sum[d] += states[i][t][d];
                }
            }
            for (int d = 0; d < dimension; d++) {
                sum[d] /= Math.max(1, count);
            }
            normalize(sum);
            result[i] = sum;
        }
        return result;
    }

    static void normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int d = 0; d < vector.length; d++) {
                vector[d] = (float) (vector[d] / norm);
            }
        }
    }

    private static int declaredDimension(OrtSession session, String output) {
        try {
            NodeInfo node = session.getOutputInfo().get(output);
            if (node != null && node.getInfo() instanceof TensorInfo tensor) {
                long[] shape = tensor.getShape();
                long last = shape.length == 0 ? -1 : shape[shape.length - 1];
                return last > 0 ? (int) last : -1;
            }
        } catch (OrtException e) {
            // fall back to a probe
        }
        return -1;
    }

    /** SHA-256 of a file, hex. */
    public static String sha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 20];
            for (int read; (read = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Switches off DJL's telemetry probe and keeps its native library under the data directory. */
    private static void quietDjl(Path nativeCache) {
        setIfAbsent("OPT_OUT_TRACKING", "true");
        setIfAbsent("ai.djl.offline", "true");
        if (System.getenv("DJL_CACHE_DIR") == null && System.getenv("ENGINE_CACHE_DIR") == null) {
            setIfAbsent("DJL_CACHE_DIR", nativeCache.toString());
            setIfAbsent("ENGINE_CACHE_DIR", nativeCache.toString());
        }
    }

    private static void setIfAbsent(String property, String value) {
        if (System.getProperty(property) == null) {
            System.setProperty(property, value);
        }
    }

    @Override
    public void close() {
        tokenizer.close();
        try {
            session.close();
        } catch (OrtException e) {
            // nothing to do when closing
        }
    }
}
