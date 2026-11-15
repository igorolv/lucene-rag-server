package ru.it_spectrum.ai.rag.mcp.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model type {@value #NAME}: a Spring AI {@link EmbeddingModel} bean of the application
 * context, configured by Spring AI's own properties (for example {@code spring.ai.openai.embedding.*}
 * with its starter on the class path), whose bean name is the key of the model under
 * {@code models} (docs/decisions.md D43). A remote endpoint (D11) is such a bean.
 *
 * <p>The server keeps what Spring AI does not give. The identity recorded in the manifest is the
 * bean name and the model the response names, and the vector size is that of a probe embedded
 * when the model is loaded; a collection served with a bean that answers for another model is
 * refused like one served with other model files. The prefixes of {@code models} apply. A model
 * of this type does not say which inputs it cut, so none are counted as cut.
 */
public final class BeanModelType implements ModelType {

    public static final String NAME = "bean";

    /** Identity entries of a bean model, in the place of the file hashes of a model with files. */
    public static final String IDENTITY_BEAN = "bean";
    public static final String IDENTITY_MODEL = "model";

    private final ListableBeanFactory beans;

    public BeanModelType(ListableBeanFactory beans) {
        this.beans = beans;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean usesFiles() {
        return false;
    }

    @Override
    public EmbeddingModels.Loaded load(String name, Path dir, ModelParameters parameters) throws IOException {
        final EmbeddingModel model;
        try {
            model = beans.getBean(name, EmbeddingModel.class);
        } catch (BeansException e) {
            List<String> available = Arrays.stream(beans.getBeanNamesForType(EmbeddingModel.class)).sorted().toList();
            throw new IOException("Model '" + name + "' has type " + NAME + ", and there is no EmbeddingModel bean named '"
                    + name + "'. Configure a Spring AI embedding model and name the model after its bean; beans: "
                    + (available.isEmpty() ? "none" : String.join(", ", available)), e);
        }
        final EmbeddingResponse probe;
        try {
            probe = model.call(new EmbeddingRequest(List.of(parameters.passagePrefix() + "probe"),
                    EmbeddingOptions.builder().build()));
        } catch (RuntimeException e) {
            throw new IOException("Model '" + name + "' (bean) failed to embed a probe: " + e.getMessage(), e);
        }
        if (probe.getResults().isEmpty() || probe.getResults().getFirst().getOutput() == null) {
            throw new IOException("Model '" + name + "' (bean) returned no vector for a probe");
        }
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put(IDENTITY_BEAN, name);
        String responseModel = probe.getMetadata() == null ? null : probe.getMetadata().getModel();
        if (responseModel != null && !responseModel.isBlank()) {
            identity.put(IDENTITY_MODEL, responseModel);
        }
        return new EmbeddingModels.Loaded(model,
                new ModelInfo(NAME, probe.getResults().getFirst().getOutput().length, identity));
    }
}
