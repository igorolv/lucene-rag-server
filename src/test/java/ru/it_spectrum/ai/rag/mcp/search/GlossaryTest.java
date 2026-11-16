package ru.it_spectrum.ai.rag.mcp.search;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A glossary file whose terms are documents of their own (docs/decisions.md D88, D91): a search
 * among terms returns several of them with the default of one section per document.
 */
class GlossaryTest {

    static final String GLOSSARY = "glossary@billing";

    @TempDir
    static Path dataDir;

    static SearchService search;

    @BeforeAll
    static void build() throws IOException {
        String root = TestKnowledge.root().toString().replace('\\', '/');
        Path file = dataDir.resolve("indexes.json");
        Files.writeString(file, """
                {"indexes": {"%s": {"project": "billing", "sources": [{
                  "path": "%s/glossary/glossary.md",
                  "split": {"level": "h3", "headingPattern": "^Term: (?<name>.+)$"},
                  "map": {
                    "bodyPattern": "(?s)\\\\*\\\\*ID:\\\\*\\\\* (?<id>GL-\\\\d+)(?:.*?\\\\*\\\\*Сокращение:\\\\*\\\\* (?<abbr>[^\\\\n—]+))?",
                    "document": "glossary:{body.id}",
                    "title": "{heading.name}",
                    "breadcrumb": ["Словарь терминов", "{title}", "{body.abbr}"],
                    "id": "term:{body.id|slug}",
                    "fields": {"kind": "term", "term": "{body.id}", "abbr": "{body.abbr}"},
                    "keys": ["term", "abbr"]
                  }}]}}}""".formatted(GLOSSARY, root));
        for (IndexDefinition definition : IndexesLoader.load(file, name -> null)) {
            IndexBuilder.build(definition, StepRegistry.builtIn(), dataDir, "test");
        }
        search = new SearchService(new IndexStore(dataDir), new RagMcpProperties(dataDir.toString(), null));
    }

    @Test
    void everyTermIsADocumentOfItsOwn() throws IOException {
        SearchPage terms = search.search(null, null, Map.of("kind", "term"), null, 50, null, null);

        assertThat(terms.hits()).extracting(SearchHit::document)
                .containsExactlyInAnyOrder("glossary:GL-001", "glossary:GL-002", "glossary:GL-003", "glossary:GL-004");
        assertThat(terms.hits()).extracting(SearchHit::ordinal).containsOnly(0);
        assertThat(terms.hits()).extracting(SearchHit::id).contains("term:GL-001");
    }

    @Test
    void aSearchAmongTermsReturnsSeveralWithOneSectionPerDocument() throws IOException {
        SearchPage page = search.search(null, "платёж счёт", Map.of("kind", "term"), null, 10, null, null);

        assertThat(page.hits()).hasSizeGreaterThan(1);
        assertThat(page.hits()).extracting(SearchHit::document).doesNotHaveDuplicates();
    }

    @Test
    void aTermIsFoundByItsAbbreviation() throws IOException {
        assertThat(search.lookup(null, "ор", null, null).hits()).extracting(SearchHit::id)
                .containsExactly("term:GL-003");
        assertThat(search.lookup(null, "GL-004", null, null).hits()).extracting(SearchHit::breadcrumb)
                .containsExactly("Словарь терминов › Частичный возврат › ЧВ");
        assertThat(search.lookup(List.of(GLOSSARY), "—", null, null).total()).isZero();
    }
}
