package ru.it_spectrum.ai.rag.mcp.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.it_spectrum.ai.rag.mcp.TestKnowledge;
import ru.it_spectrum.ai.rag.mcp.definition.IndexDefinition;
import ru.it_spectrum.ai.rag.mcp.definition.IndexesLoader;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.FakeEmbeddingModel;
import ru.it_spectrum.ai.rag.mcp.eval.QuestionSet;
import ru.it_spectrum.ai.rag.mcp.eval.References;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.ingest.StepRegistry;
import ru.it_spectrum.ai.rag.mcp.search.SearchHit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Measures the synthetic question set against a docs index with vectors and a data index. */
class RagEvalCommandTest {

    static final String DOCS = TestKnowledge.DOCS;
    static final String DATA = "data@billing";
    static final String QUEUE = "queue@billing";

    @TempDir
    static Path dataDir;

    static RagMcpProperties properties;
    static EmbeddingModels models;
    static String questions;

    @BeforeAll
    static void build() throws IOException {
        String root = TestKnowledge.root().toString().replace('\\', '/');
        models = FakeEmbeddingModel.models(dataDir, "fake");
        Path file = dataDir.resolve("indexes.json");
        Files.writeString(file, """
                {"models": {"fake": {"type": "fake", "queryPrefix": "query: ", "passagePrefix": "passage: "}},
                 "indexes": {
                  "%s": {"vectors": {"model": "fake"},
                         "sources": [{"path": "%s/articles"}, {"path": "%s/playbooks"}]},
                  "%s": {"sources": [{"path": "%s/data/tables", "include": "*.yaml",
                                      "map": {"text": "{body}", "fields": {"table": "{yaml.table}"}}}]},
                  "%s": {"sources": [{"path": "%s/queue/symptoms.md", "split": {"tableRows": true},
                                      "map": {"title": "{column.2}", "fields": {"number": "{column.1}"}}}]}
                 }}
                """.formatted(DOCS, root, root, DATA, root, QUEUE, root));
        for (IndexDefinition definition : StepRegistry.builtIn().checked(IndexesLoader.load(file, n -> null))) {
            IndexBuilder.build(definition, StepRegistry.builtIn(), models, dataDir, "test");
        }
        properties = new RagMcpProperties(dataDir.toString(), null);
        questions = TestKnowledge.root().resolve("eval").resolve("questions.jsonl").toString();
    }

    private record Run(int code, String out) {
    }

    private static Run eval(String... args) {
        var bytes = new ByteArrayOutputStream();
        int code = RagEvalCommand.run(args, properties, models, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return new Run(code, bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void everyModeOfTheIndexesIsMeasured() {
        Run run = eval(DOCS + "," + DATA + "," + QUEUE, questions, "--ref", "data=table", "--ref", "symptom=number",
                "--details");

        assertThat(run.code()).as(run.out()).isZero();
        assertThat(run.out())
                .contains("Questions: 3 read, 3 measured, 0 left out (0 without references")
                .contains("References that name no record: 1 (spec: 1)")
                .containsPattern("lexical +\\d/3 +\\d/3 +3/3")
                .containsPattern("hybrid +\\d/3")
                .containsPattern("vector +\\d/3")
                .containsPattern("q-0003: lexical \\d+ vector [-\\d]+ hybrid \\d+; names no record: "
                        + "spec:billing-payments/payment-queue")
                .contains("RESULT: OK (3 questions measured in 3 modes, depth 10)");
    }

    @Test
    void withoutTheMappingADataReferenceNamesNoRecord() {
        Run run = eval(DOCS + "," + DATA, questions, "--modes", "lexical");

        assertThat(run.code()).as(run.out()).isZero();
        assertThat(run.out()).contains("(data: 1, spec: 1, symptom: 1)").contains("in 1 mode,");
    }

    @Test
    void anIndexWithVectorsGetsTheVectorModeToo() {
        Run run = eval(DOCS, questions, "--limit", "3");

        assertThat(run.code()).as(run.out()).isZero();
        assertThat(run.out()).containsPattern("vector +\\d/2 +\\d/2 +\\d/2").contains("depth 3)");
    }

    @Test
    void measuresAreGroupedByAKeyOfTheQuestions() {
        Run run = eval(DOCS + "," + DATA + "," + QUEUE, questions, "--ref", "data=table", "--ref", "symptom=number",
                "--modes", "lexical", "--group-by", "type", "--group-by", "subsystem");

        assertThat(run.code()).as(run.out()).isZero();
        assertThat(run.out())
                .containsPattern("type +mode +@1")
                // A question with two values counts in both groups, one without the key in (none).
                .containsPattern("diagnostics +lexical +\\d/1")
                .containsPattern("how-to +lexical +\\d/1")
                .containsPattern("status +lexical +\\d/1")
                .containsPattern("\\(none\\) +lexical +\\d/1")
                .containsPattern("subsystem +mode")
                .containsPattern("billing +lexical +\\d/3 +\\d/3 +3/3");
        assertThat(run.out().indexOf("diagnostics")).isLessThan(run.out().indexOf("how-to"));
        assertThat(run.out().indexOf("status")).isLessThan(run.out().indexOf("(none)"));
        assertThat(eval(DOCS, questions, "--group-by", " ").code()).isEqualTo(2);
    }

    @Test
    void usageAndBadInputAreReported() throws IOException {
        assertThat(eval().code()).isEqualTo(2);
        assertThat(eval(DOCS).code()).isEqualTo(2);
        assertThat(eval(DOCS, questions, "--ref", "data").code()).isEqualTo(2);
        assertThat(eval(DOCS, questions, "--limit", "0").out()).contains("--limit must be from 1 to 100");
        assertThat(eval(DOCS, "missing.jsonl").out()).contains("question file missing.jsonl does not exist");
        assertThat(eval("nope", questions).out()).contains("Unknown index 'nope'");
        assertThat(eval(DATA, questions, "--modes", "vector").out()).contains("no vectors");
        Path bad = dataDir.resolve("bad.jsonl");
        Files.writeString(bad, "{\"id\": \"x\", \"expected\": []}\n");
        Run run = eval(DOCS, bad.toString());
        assertThat(run.code()).isEqualTo(2);
        assertThat(run.out()).contains("bad.jsonl, line 1 has no question");
    }

    @Test
    void aQuestionSetLineNeedsAQuestionAndMayLackTheRest() throws IOException {
        Path file = dataDir.resolve("set.jsonl");
        Files.writeString(file, """
                {"question": "Почему?", "expected": "article.billing.refunds"}

                {"id": 7, "question": " Где? ", "expected": ["a", 5, {"x": 1}, ""]}
                """);

        assertThat(QuestionSet.read(file)).containsExactly(
                new QuestionSet.Question("line 1", "Почему?", List.of("article.billing.refunds")),
                new QuestionSet.Question("7", "Где?", List.of("a", "5")));
        Files.writeString(file, """
                {"question": "Q", "type": ["how-to", 2, {"x": 1}, ""], "subsystem": " ssv ", "meta": {"a": 1}, "no": []}
                """);
        QuestionSet.Question question = QuestionSet.read(file).getFirst();
        assertThat(question.values()).containsOnlyKeys("type", "subsystem");
        assertThat(question.values("type")).containsExactly("how-to", "2");
        assertThat(question.values("subsystem")).containsExactly("ssv");
        assertThat(question.values("origin")).isEmpty();
        Files.writeString(file, "not json\n");
        assertThatThrownBy(() -> QuestionSet.read(file)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1 is not valid JSON");
    }

    @Test
    void referencesNameRecordsByKeyIdPathOrField() {
        SearchHit hit = new SearchHit("c", "menu.tsv#3", "menu.tsv", "t", "b", "menu.tsv", 2, 0f, null, null,
                Map.of("table", List.of("PAYMENT"), "kind", List.of("menu")));
        References refs = new References(Map.of("data", "table"));

        assertThat(refs.matches("menu.tsv", hit)).isTrue();
        assertThat(refs.matches("menu.tsv#3", hit)).isTrue();
        assertThat(refs.matches("catalogs/menu/menu.tsv", hit)).isTrue();
        assertThat(refs.matches("data: payment", hit)).isTrue();
        assertThat(refs.matches("kind:MENU", hit)).isTrue();
        assertThat(refs.matches("data:REFUND", hit)).isFalse();
        assertThat(refs.matches("table-menu.tsv", hit)).isFalse();
        assertThat(refs.matches("symptom:3", hit)).isFalse();
    }
}
