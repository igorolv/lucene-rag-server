package ru.it_spectrum.ai.rag.mcp.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.definition.WhereSettings;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WhereTest {

    @TempDir
    Path dir;

    private List<IndexDefinition> load(String source) throws IOException {
        Path file = dir.resolve("collections.json");
        Files.writeString(file, "{\"collections\": {\"c\": {\"sources\": [" + source + "]}}}");
        return StepRegistry.builtIn().checked(IndexesLoader.load(file, name -> null));
    }

    private List<ChunkRecord> records(String where) throws IOException {
        String articles = TestKnowledge.root().resolve("articles").toString().replace('\\', '/');
        IndexDefinition definition = load("{\"path\": \"" + articles + "\", \"where\": " + where + "}")
                .getFirst();
        assertThat(definition.configError()).isNull();
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(definition.sources().getFirst());
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }
        return records;
    }

    private static List<String> documents(List<ChunkRecord> records) {
        return records.stream().map(ChunkRecord::document).distinct().toList();
    }

    @Test
    void recordsAreKeptByTheValuesOfTheirFields() throws IOException {
        assertThat(documents(records("{}")))
                .containsExactly("article.billing.payments", "article.billing.refunds");
        assertThat(documents(records("{\"tags\": \"Refunds \"}"))).as("any value, case and blanks ignored")
                .containsExactly("article.billing.refunds");
        assertThat(documents(records("{\"tags\": {\"not\": \"refunds\"}}")))
                .containsExactly("article.billing.payments");
        assertThat(documents(records("{\"subsystem\": \"billing\", \"tables\": \"invoice\"}")))
                .as("every condition must hold").containsExactly("article.billing.payments");
        assertThat(records("{\"status\": \"skeleton\"}")).as("a missing field has no value").isEmpty();
        assertThat(documents(records("{\"status\": {\"not\": \"skeleton\"}}"))).hasSize(2);
    }

    @Test
    void whereSeesTheFieldsMadeByMap() throws IOException {
        String articles = TestKnowledge.root().resolve("articles").toString().replace('\\', '/');
        IndexDefinition definition = load("{\"path\": \"" + articles + "\", \"map\": {\"inheritFields\": [],"
                + " \"fields\": {\"area\": \"{path.name}\"}}, \"where\": {\"area\": \"payments\", \"kind\": {\"not\": \"article\"}}}")
                .getFirst();
        SourcePipeline pipeline = StepRegistry.builtIn().pipeline(definition.sources().getFirst());
        List<ChunkRecord> records = new ArrayList<>();
        for (SourceItem item : pipeline.items()) {
            records.addAll(pipeline.records(item));
        }

        assertThat(documents(records)).containsExactly("article.billing.payments");
    }

    @Test
    void conditionsOtherThanAValueOrNotAreRefused() throws IOException {
        assertThat(load("{\"path\": \"a\", \"where\": [\"x\"]}").getFirst().configError())
                .contains("\"where\" must be a JSON object");
        assertThat(load("{\"path\": \"a\", \"where\": {\"n\": {\"gt\": 3}}}").getFirst().configError())
                .contains("where.n must be a value or {\"not\": value}");
        assertThat(load("{\"path\": \"a\", \"where\": {\"n\": [\"a\", \"b\"]}}").getFirst().configError())
                .contains("where.n must be a value");
        assertThat(load("{\"path\": \"a\", \"where\": {\"bad name\": \"x\"}}").getFirst().configError())
                .contains("\"bad name\" is not a field name");
        assertThat(load("{\"path\": \"a\", \"where\": {\"n\": 3, \"f\": true}}").getFirst().sources().getFirst().where())
                .isEqualTo(new WhereSettings(List.of(new WhereSettings.Condition("n", "3", false),
                        new WhereSettings.Condition("f", "true", false))));
    }

    @Test
    void theManifestKeepsTheConditionsAndTheCountsLeaveOutWhatWasDropped(@TempDir Path dataDir) throws IOException {
        String articles = TestKnowledge.root().resolve("articles").toString().replace('\\', '/');
        IndexDefinition definition = load("{\"path\": \"" + articles
                + "\", \"where\": {\"tags\": {\"not\": \"refunds\"}}}").getFirst();

        Manifest manifest = IndexBuilder.build(definition, StepRegistry.builtIn(), dataDir, "test");

        assertThat(manifest.files()).isEqualTo(2);
        assertThat(manifest.documents()).isEqualTo(1);
        assertThat(Manifest.read(dataDir.resolve("c")).sources().getFirst().where())
                .isEqualTo(new WhereSettings(List.of(new WhereSettings.Condition("tags", "refunds", true))));
        assertThat(new WhereSettings(List.of(new WhereSettings.Condition("tags", "refunds", true)))
                .matches(Map.of("tags", List.of("payments")))).isTrue();
    }
}
