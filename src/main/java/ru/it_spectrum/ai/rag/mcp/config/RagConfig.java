package ru.it_spectrum.ai.rag.mcp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.it_spectrum.ai.rag.mcp.index.CollectionStore;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

/** Wires the plain classes the tools use; the same classes serve the user-run commands. */
@Configuration
public class RagConfig {

    @Bean
    CollectionStore collectionStore(RagMcpProperties properties) {
        return new CollectionStore(properties.resolvedDataDir());
    }

    @Bean
    SearchService searchService(CollectionStore store, RagMcpProperties properties) {
        return new SearchService(store, properties);
    }
}
