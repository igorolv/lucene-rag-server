package ru.it_spectrum.ai.rag.mcp.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ListableBeanFactory;
import ru.it_spectrum.ai.rag.mcp.embedding.BeanModelType;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelType;
import ru.it_spectrum.ai.rag.mcp.embedding.OnnxModelType;
import ru.it_spectrum.ai.rag.mcp.index.CollectionStore;
import ru.it_spectrum.ai.rag.mcp.ingest.Converter;
import ru.it_spectrum.ai.rag.mcp.ingest.Format;
import ru.it_spectrum.ai.rag.mcp.ingest.Mapper;
import ru.it_spectrum.ai.rag.mcp.ingest.Origin;
import ru.it_spectrum.ai.rag.mcp.ingest.Splitter;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

/** Wires the plain classes the tools use; the same classes serve the user-run commands. */
@Configuration
public class RagConfig {

    @Bean
    CollectionStore collectionStore(RagMcpProperties properties) {
        return new CollectionStore(properties.resolvedDataDir());
    }

    /**
     * Every step bean of the context, built-in or added by a jar, becomes a step a source can
     * name (docs/decisions.md D19). A step without beans gives an empty list.
     */
    @Bean
    StepRegistry stepRegistry(ObjectProvider<Origin> origins, ObjectProvider<Converter> converters,
                              ObjectProvider<Format> formats, Splitter splitter, Mapper mapper) {
        return new StepRegistry(origins.orderedStream().toList(), converters.orderedStream().toList(),
                formats.orderedStream().toList(), splitter, mapper);
    }

    /** Models run in-process from their files under {@code <data-dir>/models/} (D11). */
    @Bean
    ModelType onnxModelType(RagMcpProperties properties, ObjectProvider<ObservationRegistry> observations) {
        return new OnnxModelType(EmbeddingModels.nativeCache(properties.resolvedDataDir()),
                observations.getIfUnique(() -> ObservationRegistry.NOOP));
    }

    /** Models that are Spring AI {@code EmbeddingModel} beans, configured by Spring AI properties (D43). */
    @Bean
    ModelType beanModelType(ListableBeanFactory beans) {
        return new BeanModelType(beans);
    }

    /**
     * The embedding models, loaded on first use. Every {@link ModelType} bean of the context,
     * built-in or added by a jar, is a type a {@code models} entry can name (docs/decisions.md D43).
     */
    @Bean(destroyMethod = "close")
    EmbeddingModels embeddingModels(RagMcpProperties properties, ObjectProvider<ModelType> types) {
        return new EmbeddingModels(properties.resolvedDataDir().resolve(EmbeddingModels.MODELS_DIR),
                types.orderedStream().toList());
    }

    @Bean
    SearchService searchService(CollectionStore store, RagMcpProperties properties, EmbeddingModels models) {
        return new SearchService(store, properties, models);
    }
}
