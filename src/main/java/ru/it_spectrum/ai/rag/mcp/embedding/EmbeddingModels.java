package ru.it_spectrum.ai.rag.mcp.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The embedding models of this installation. The files of a model named {@code e5-small} are in
 * {@code <data-dir>/models/e5-small/}, on the machine that builds and on the one that serves; a
 * model is loaded on first use and kept for the life of the process.
 *
 * <p>Search checks the loaded model against the manifest of the collection: the same type, vector
 * size and file hashes, or {@link ModelUnavailableException}. A model whose files are replaced
 * while the process runs is seen after a restart.
 */
public final class EmbeddingModels implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModels.class);

    /** Directory of the models under the data directory. */
    public static final String MODELS_DIR = "models";

    /**
     * A loaded model: Spring AI's interface to embed with (docs/decisions.md D29) and what
     * identifies the vectors it gives. A model that is {@link AutoCloseable} is closed with the
     * registry.
     */
    public record Loaded(EmbeddingModel model, ModelInfo info) {
    }

    /** Loads the model files of one directory. */
    @FunctionalInterface
    public interface Loader {
        /**
         * @throws IOException when a file is missing or the model cannot be loaded
         */
        Loaded load(Path dir, ModelParameters parameters) throws IOException;
    }

    private final Path modelsDir;
    private final Map<String, Loader> loaders;
    private final Map<String, Loaded> loaded = new HashMap<>();

    /**
     * @param modelsDir directory holding one directory per model
     * @param loaders   how a model of each type is loaded, by type
     */
    public EmbeddingModels(Path modelsDir, Map<String, Loader> loaders) {
        this.modelsDir = modelsDir;
        this.loaders = Map.copyOf(loaders);
    }

    /** The models of a data directory, run in-process by ONNX Runtime (docs/decisions.md D11). */
    public static EmbeddingModels inProcess(Path dataDir) {
        Path nativeCache = dataDir.resolve("cache").resolve("djl");
        return new EmbeddingModels(dataDir.resolve(MODELS_DIR), Map.of(ModelParameters.ONNX,
                (dir, parameters) -> {
                    OnnxEmbeddingModel model = OnnxEmbeddingModel.open(dir, parameters.maxTokens(), nativeCache);
                    return new Loaded(model, model.info());
                }));
    }

    /** Where the files of a model are. */
    public Path directory(String name) {
        return modelsDir.resolve(name);
    }

    /** Types of model this installation can load. */
    public List<String> types() {
        return loaders.keySet().stream().sorted().toList();
    }

    /**
     * The model a collection is built with, as {@code collections.json} configures it.
     *
     * @throws IOException              when the files are missing or the model cannot be loaded
     * @throws IllegalArgumentException when the type is unknown
     */
    public synchronized VectorModel load(String name, ModelParameters parameters) throws IOException {
        Loader loader = loaders.get(parameters.type());
        if (loader == null) {
            throw new IllegalArgumentException("Model type '" + parameters.type() + "' is not supported; supported: "
                    + String.join(", ", types()));
        }
        Path dir = directory(name);
        String key = parameters.type() + "|" + parameters.maxTokens() + "|" + dir;
        Loaded model = loaded.get(key);
        if (model == null) {
            if (!Files.isDirectory(dir)) {
                throw new IOException("The files of model '" + name + "' are not in " + dir);
            }
            long start = System.nanoTime();
            model = loader.load(dir, parameters);
            log.info("Model '{}' loaded from {} in {} ms", name, dir, (System.nanoTime() - start) / 1_000_000);
            loaded.put(key, model);
        }
        return new VectorModel(name, parameters, model.model(), model.info());
    }

    /**
     * The model a built collection was embedded with, checked against what its manifest records.
     *
     * @param collection for the message
     * @param dimension  vector size recorded at build time
     * @param files      file hashes recorded at build time
     * @throws ModelUnavailableException when the model cannot be loaded or is not the same model
     */
    public VectorModel verified(String collection, String name, ModelParameters parameters, int dimension,
                                Map<String, String> files) {
        final VectorModel model;
        try {
            model = load(name, parameters);
        } catch (IOException | IllegalArgumentException e) {
            throw new ModelUnavailableException("Collection '" + collection + "' needs model '" + name
                    + "' for vector and hybrid search, and it cannot be loaded: " + e.getMessage()
                    + ". Put the model files there, or search with mode lexical", e);
        }
        ModelInfo info = model.info();
        if (info.dimension() != dimension || !new TreeMap<>(info.files()).equals(new TreeMap<>(files))) {
            throw new ModelUnavailableException("Collection '" + collection + "' was built with another version of model '"
                    + name + "' than the one in " + directory(name) + " (built with " + describe(dimension, files)
                    + "; found " + describe(info.dimension(), info.files()) + "). Rebuild the collection or put the "
                    + "model files it was built with there; mode lexical works without the model");
        }
        return model;
    }

    private static String describe(int dimension, Map<String, String> files) {
        StringBuilder result = new StringBuilder().append(dimension).append(" dimensions");
        new TreeMap<>(files).forEach((file, hash) ->
                result.append(", ").append(file).append(" sha256 ").append(hash, 0, Math.min(12, hash.length())));
        return result.toString();
    }

    @Override
    public synchronized void close() {
        for (Loaded model : loaded.values()) {
            if (model.model() instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("Cannot close a model: {}", e.getMessage());
                }
            }
        }
        loaded.clear();
    }
}
