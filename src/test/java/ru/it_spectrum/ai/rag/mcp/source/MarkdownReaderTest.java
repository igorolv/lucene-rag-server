package ru.it_spectrum.ai.rag.mcp.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReaderTest {

    @Test
    void frontMatterBecomesFieldsAndIsRemovedFromTheText() {
        SourceDocument document = MarkdownReader.read("a/payments.md", """
                ---
                id: article.payments
                kind: article
                sf: [101, 102]
                nested: {skipped: true}
                empty:
                bad name!: x
                ---

                # Платежи

                Вступление.
                """);

        assertThat(document.key()).isEqualTo("article.payments");
        assertThat(document.title()).isEqualTo("Платежи");
        assertThat(document.fields()).containsOnlyKeys("id", "kind", "sf");
        assertThat(document.fields().get("sf")).containsExactly("101", "102");
        assertThat(document.preamble()).isEmpty();
        assertThat(document.sections()).hasSize(1);
        assertThat(document.sections().getFirst().text()).isEqualTo("Вступление.");
    }

    @Test
    void headingsBuildATreeByLevel() {
        SourceDocument document = MarkdownReader.read("doc.md", """
                Before any heading.

                # Title
                ## First
                one
                ### Deep
                deep text
                ## Second ##
                two
                """);

        assertThat(document.preamble()).isEqualTo("Before any heading.");
        Section title = document.sections().getFirst();
        assertThat(title.children()).extracting(Section::title).containsExactly("First", "Second");
        Section first = title.children().getFirst();
        assertThat(first.text()).isEqualTo("one");
        assertThat(first.children()).extracting(Section::title, Section::level, Section::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Deep", 3, "deep text"));
    }

    @Test
    void hashLinesInsideFencedCodeAreNotHeadings() {
        SourceDocument document = MarkdownReader.read("doc.md", """
                # Title

                ```bash
                # a comment
                ```

                ~~~
                ## also not a heading
                ~~~
                """);

        assertThat(document.sections()).hasSize(1);
        assertThat(document.sections().getFirst().children()).isEmpty();
        assertThat(document.sections().getFirst().text()).contains("# a comment", "## also not a heading");
    }

    @Test
    void withoutFrontMatterThePathIsTheKeyAndTheFileNameTheFallbackTitle() {
        SourceDocument document = MarkdownReader.read("notes/release plan.md", "## Only a section\ntext\n");

        assertThat(document.key()).isEqualTo("notes/release plan.md");
        assertThat(document.title()).isEqualTo("release plan");
        assertThat(document.fields()).isEmpty();
    }

    @Test
    void invalidOrUnterminatedFrontMatterStaysText() {
        SourceDocument unterminated = MarkdownReader.read("a.md", "---\nid: x\n\n# Title\n");
        assertThat(unterminated.fields()).isEmpty();
        assertThat(unterminated.preamble()).contains("id: x");

        SourceDocument invalid = MarkdownReader.read("b.md", "---\nid: [unclosed\n---\n# Title\n");
        assertThat(invalid.fields()).isEmpty();
    }

    @Test
    void headingMarkupIsStrippedFromTitles() {
        SourceDocument document = MarkdownReader.read("a.md", "# The `search` tool and [docs](http://example.com)\n");

        assertThat(document.sections()).extracting(Section::title)
                .isEqualTo(List.of("The search tool and docs"));
    }
}
