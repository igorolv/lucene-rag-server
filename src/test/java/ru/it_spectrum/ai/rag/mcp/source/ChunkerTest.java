package ru.it_spectrum.ai.rag.mcp.source;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.rag.mcp.collections.ChunkSettings;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkerTest {

    private static final ChunkSettings NO_MERGE = new ChunkSettings(2, 2500, 0);

    @Test
    void eachSectionAtTheSplitLevelIsOneRecordWithItsSubsections() {
        SourceDocument document = MarkdownReader.read("pay.md", """
                ---
                id: doc.pay
                kind: article
                ---
                # Платежи

                Вступление раздела.

                ## Создание

                Текст о создании.

                ### Вручную

                Текст о ручном создании.

                ## Отмена

                Текст об отмене.
                """);

        List<ChunkRecord> records = Chunker.chunk(document, NO_MERGE);

        assertThat(records).extracting(ChunkRecord::id)
                .containsExactly("doc.pay#платежи", "doc.pay#создание", "doc.pay#отмена");
        assertThat(records).extracting(ChunkRecord::breadcrumb)
                .containsExactly("Платежи", "Платежи › Создание", "Платежи › Отмена");
        assertThat(records.get(1).text())
                .isEqualTo("Текст о создании.\n\n### Вручную\n\nТекст о ручном создании.");
        assertThat(records).extracting(ChunkRecord::ordinal).containsExactly(0, 1, 2);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.document()).isEqualTo("doc.pay");
            assertThat(r.source()).isEqualTo("pay.md");
            assertThat(r.fields().get("kind")).containsExactly("article");
        });
    }

    @Test
    void splitLevelDecidesHowDeepRecordsGo() {
        SourceDocument document = MarkdownReader.read("a.md", "# T\n## A\na\n### A1\na1\n### A2\na2\n");

        assertThat(Chunker.chunk(document, new ChunkSettings(3, 2500, 0))).extracting(ChunkRecord::breadcrumb)
                .containsExactly("T › A", "T › A › A1", "T › A › A2");
        assertThat(Chunker.chunk(document, new ChunkSettings(1, 2500, 0))).extracting(ChunkRecord::breadcrumb)
                .containsExactly("T");
    }

    @Test
    void shortSectionJoinsThePreviousOneUnderTheSameParent() {
        SourceDocument document = MarkdownReader.read("a.md", """
                # T
                ## Long
                %s
                ## Tiny
                short
                """.formatted("word ".repeat(100)));

        List<ChunkRecord> records = Chunker.chunk(document, new ChunkSettings(2, 2500, 200));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().text()).endsWith("## Tiny\n\nshort");
    }

    @Test
    void longSectionIsSplitAtParagraphsAndPartsKeepTheBreadcrumb() {
        String paragraph = "Sentence of a paragraph. ".repeat(10).strip();
        SourceDocument document = MarkdownReader.read("a.md",
                "# T\n## Big\n" + (paragraph + "\n\n").repeat(6));

        List<ChunkRecord> records = Chunker.chunk(document, new ChunkSettings(2, 600, 0));

        assertThat(records.size()).isGreaterThan(1);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.text().length()).isLessThanOrEqualTo(600);
            assertThat(r.breadcrumb()).isEqualTo("T › Big");
        });
        assertThat(records).extracting(ChunkRecord::id).startsWith("a.md#big", "a.md#big~2");
    }

    @Test
    void aLineLongerThanTheLimitIsCutInsideTheLine() {
        assertThat(Chunker.split("x".repeat(450), 200)).extracting(String::length).containsExactly(200, 200, 50);
    }

    @Test
    void repeatedHeadingsGetDistinctIds() {
        SourceDocument document = MarkdownReader.read("a.md", "# T\n## Notes\none\n## Notes\ntwo\n");

        assertThat(Chunker.chunk(document, NO_MERGE)).extracting(ChunkRecord::id)
                .containsExactly("a.md#notes", "a.md#notes-2");
    }

    @Test
    void textBeforeTheFirstHeadingIsARecordAndEmptySectionsAreDropped() {
        SourceDocument document = MarkdownReader.read("a.md", "Lead text.\n\n# T\n## Empty\n## Full\nbody\n");

        assertThat(Chunker.chunk(document, NO_MERGE)).extracting(ChunkRecord::id, ChunkRecord::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("a.md#intro", "Lead text."),
                        org.assertj.core.groups.Tuple.tuple("a.md#full", "body"));
    }
}
