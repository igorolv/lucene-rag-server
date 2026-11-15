package ru.it_spectrum.ai.rag.mcp.embedding;

import java.util.List;
import java.util.Optional;

/**
 * Models {@code rag-model fetch} knows how to get (docs/decisions.md D70): ONNX exports published
 * on Hugging Face, pinned to a revision of their repository, with the size and SHA-256 of each
 * file as Hugging Face lists them. A download that does not match is not kept.
 */
public final class ModelCatalog {

    /**
     * One file of a model.
     *
     * @param path    the path in the model repository
     * @param name    the name under {@code <data-dir>/models/<model>/}
     * @param size    bytes
     * @param sha256  lower-case hex; {@code null} for a small file Hugging Face publishes no SHA-256 of
     * @param gitBlob the git object id of the file, which Hugging Face publishes for every file;
     *                {@code null} when {@code sha256} is known
     */
    public record File(String path, String name, long size, String sha256, String gitBlob) {

        public File(String path, String name, long size, String sha256) {
            this(path, name, size, sha256, null);
        }
    }

    /**
     * A model of the catalog.
     *
     * @param name        the name under {@code models} and of its directory
     * @param repository  the Hugging Face repository
     * @param revision    the commit of the repository the files are taken from
     * @param license     the license of the model
     * @param dimension   vector size
     * @param description what it is, for {@code rag-model list}
     * @param files       {@code model.onnx} and {@code tokenizer.json}
     */
    public record Model(String name, String repository, String revision, String license, int dimension,
                        String description, List<File> files) {

        public long size() {
            return files.stream().mapToLong(File::size).sum();
        }

        /** The entry of {@code models} in {@code collections.json} for this model. */
        public String collectionsEntry() {
            return "\"" + name + "\": { \"type\": \"onnx\", \"queryPrefix\": \"query: \", "
                    + "\"passagePrefix\": \"passage: \", \"maxTokens\": 512 }";
        }
    }

    /** Where files are downloaded from unless another base is given. */
    public static final String HUGGING_FACE = "https://huggingface.co";

    public static final List<Model> MODELS = List.of(
            new Model("e5-small", "intfloat/multilingual-e5-small", "614241f622f53c4eeff9890bdc4f31cfecc418b3",
                    "MIT", 384, "multilingual E5 small, the default (D25)", List.of(
                    new File("onnx/model.onnx", OnnxEmbeddingModel.MODEL_FILE, 470_268_510L,
                            "ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665"),
                    new File("onnx/tokenizer.json", OnnxEmbeddingModel.TOKENIZER_FILE, 17_082_730L,
                            "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"))),
            new Model("e5-base", "intfloat/multilingual-e5-base", "d128750597153bb5987e10b1c3493a34e5a4502a",
                    "MIT", 768, "multilingual E5 base, about three times slower to embed", List.of(
                    new File("onnx/model.onnx", OnnxEmbeddingModel.MODEL_FILE, 1_110_059_084L,
                            "84a4d426f7e87a6bf5bf195f0bae2c4a7d15f675b23ca96f42fab8326d7a77aa"),
                    new File("onnx/tokenizer.json", OnnxEmbeddingModel.TOKENIZER_FILE, 17_082_660L,
                            "62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626"))));

    private ModelCatalog() {
    }

    public static Optional<Model> find(String name) {
        return MODELS.stream().filter(m -> m.name().equals(name)).findFirst();
    }

    /** What to download for a model of the catalog; its pooling is mean, as E5's. */
    public static HuggingFace.Source source(Model model) {
        return new HuggingFace.Source(model.repository(), model.revision(), model.files(), ModelParameters.MEAN);
    }
}
