package ru.it_spectrum.ai.rag.mcp.ingest.split;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.rag.mcp.definition.MapSettings;
import ru.it_spectrum.ai.rag.mcp.definition.SplitSettings;
import ru.it_spectrum.ai.rag.mcp.ingest.ChunkRecord;
import ru.it_spectrum.ai.rag.mcp.ingest.SectionTree;
import ru.it_spectrum.ai.rag.mcp.ingest.Unit;
import ru.it_spectrum.ai.rag.mcp.ingest.format.MarkdownFormat;
import ru.it_spectrum.ai.rag.mcp.ingest.format.MarkdownReader;
import ru.it_spectrum.ai.rag.mcp.ingest.map.TemplateMapper;
import ru.it_spectrum.ai.rag.mcp.ingest.map.TextParts;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Splitting with the default mapping. */
class HeadingSplitterTest {

    private static final SplitSettings H2 = SplitSettings.DEFAULT;

    private static List<ChunkRecord> records(SectionTree document, SplitSettings split) {
        return new TemplateMapper().map(document, new HeadingSplitter().split(document, split), MapSettings.DEFAULT,
                new MarkdownFormat().mapDefaults(), split.maxChars());
    }

    @Test
    void eachSectionAtTheSplitLevelIsOneRecordWithItsSubsections() {
        SectionTree document = MarkdownReader.read("pay.md", """
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

        List<ChunkRecord> records = records(document, H2);

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
    void theLevelDecidesHowDeepRecordsGo() {
        SectionTree document = MarkdownReader.read("a.md", "# T\n## A\na\n### A1\na1\n### A2\na2\n");

        assertThat(records(document, new SplitSettings(3, null, 2500, 0))).extracting(ChunkRecord::breadcrumb)
                .containsExactly("T › A", "T › A › A1", "T › A › A2");
        assertThat(records(document, new SplitSettings(1, null, 2500, 0))).extracting(ChunkRecord::breadcrumb)
                .containsExactly("T");
    }

    @Test
    void theDocumentLevelMakesOneRecordOfTheWholeFile() {
        SectionTree document = MarkdownReader.read("notes/a.md", "Lead.\n\n# T\n## A\na\n## B\nb\n");

        List<ChunkRecord> records = records(document, new SplitSettings(SplitSettings.DOCUMENT, null, 2500, 0));

        assertThat(records).extracting(ChunkRecord::id, ChunkRecord::breadcrumb, ChunkRecord::text)
                .containsExactly(Tuple.tuple("notes/a.md#t", "T", "Lead.\n\n# T\n\n## A\n\na\n\n## B\n\nb"));
    }

    @Test
    void aHeadingPatternSelectsSectionsAndExposesItsGroups() {
        SectionTree document = MarkdownReader.read("spec.md", """
                # Spec
                Purpose text that is not a requirement.
                ## Requirements
                ### Requirement: Create a payment
                The system SHALL create a payment.
                #### Scenario: Manual
                - WHEN an operator saves the form
                ### Notes
                Not a requirement.
                ### Requirement: Cancel a payment
                The system SHALL cancel a payment.
                """);
        SplitSettings split = new SplitSettings(3, "^Requirement: (?<name>.+)$", 2500, 0);

        List<Unit> units = new HeadingSplitter().split(document, split);

        assertThat(units).extracting(Unit::heading)
                .containsExactly("Requirement: Create a payment", "Requirement: Cancel a payment");
        assertThat(units.getFirst().value("heading.name")).isEqualTo("Create a payment");
        assertThat(units.getFirst().ancestors()).containsExactly("Spec", "Requirements");
        assertThat(units.getFirst().text()).contains("SHALL create", "#### Scenario: Manual");
    }

    @Test
    void shortSectionJoinsThePreviousOneUnderTheSameParent() {
        SectionTree document = MarkdownReader.read("a.md", """
                # T
                ## Long
                %s
                ## Tiny
                short
                """.formatted("word ".repeat(100)));

        List<ChunkRecord> records = records(document, new SplitSettings(2, null, 2500, 200));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().text()).endsWith("## Tiny\n\nshort");
    }

    @Test
    void longSectionIsSplitAtParagraphsAndPartsKeepTheBreadcrumb() {
        String paragraph = "Sentence of a paragraph. ".repeat(10).strip();
        SectionTree document = MarkdownReader.read("a.md",
                "# T\n## Big\n" + (paragraph + "\n\n").repeat(6));

        List<ChunkRecord> records = records(document, new SplitSettings(2, null, 600, 0));

        assertThat(records.size()).isGreaterThan(1);
        assertThat(records).allSatisfy(r -> {
            assertThat(r.text().length()).isLessThanOrEqualTo(600);
            assertThat(r.breadcrumb()).isEqualTo("T › Big");
        });
        assertThat(records).extracting(ChunkRecord::id).startsWith("a.md#big", "a.md#big~2");
    }

    @Test
    void aLineLongerThanTheLimitIsCutInsideTheLine() {
        assertThat(TextParts.split("x".repeat(450), 200)).extracting(String::length).containsExactly(200, 200, 50);
    }

    @Test
    void repeatedHeadingsGetDistinctIds() {
        SectionTree document = MarkdownReader.read("a.md", "# T\n## Notes\none\n## Notes\ntwo\n");

        assertThat(records(document, H2)).extracting(ChunkRecord::id)
                .containsExactly("a.md#notes", "a.md#notes-2");
    }

    @Test
    void textBeforeTheFirstHeadingIsARecordAndEmptySectionsAreDropped() {
        SectionTree document = MarkdownReader.read("a.md", "Lead text.\n\n# T\n## Empty\n## Full\nbody\n");

        assertThat(records(document, H2)).extracting(ChunkRecord::id, ChunkRecord::text)
                .containsExactly(Tuple.tuple("a.md#intro", "Lead text."), Tuple.tuple("a.md#full", "body"));
    }
}
