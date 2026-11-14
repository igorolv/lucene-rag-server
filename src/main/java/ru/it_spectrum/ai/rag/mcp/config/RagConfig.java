package ru.it_spectrum.ai.rag.mcp.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    SearchService searchService(CollectionStore store, RagMcpProperties properties) {
        return new SearchService(store, properties);
    }
}
