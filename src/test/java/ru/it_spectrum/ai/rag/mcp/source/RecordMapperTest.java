package ru.it_spectrum.ai.rag.mcp.source;

import org.junit.jupiter.api.Test;
import ru.it_spectrum.ai.rag.mcp.collections.MapSettings;
import ru.it_spectrum.ai.rag.mcp.collections.SplitSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Laying units out as records with a configured mapping. */
class RecordMapperTest {

    private static final String SPEC = """
            ---
            owner: billing-team
            tables: [PAYMENT, INVOICE]
            draft: true
            ---
            # payments Specification
            ## Requirements
            ### Requirement: Create a payment
            The system SHALL create a payment.
            ### Requirement: Cancel a payment
            The system SHALL cancel a payment.
            """;

    private static final SplitSettings REQUIREMENTS = new SplitSettings(3, "^Requirement: (?<name>.+)$", 2500, 0);

    private static List<ChunkRecord> records(String path, String content, SplitSettings split, MapSettings map) {
        SourceDocument document = MarkdownReader.read(path, content);
        return RecordMapper.map(document, Chunker.units(document, split), map, split.maxChars());
    }

    private static MapSettings map(String document, String id, String title, List<String> breadcrumb, String text,
                                   Map<String, String> fields, List<String> inherit) {
        return new MapSettings(null, document, id, title, breadcrumb, text, fields, inherit);
    }

    @Test
    void aSpecFileIsLaidOutByPathHeadingGroupsAndConstants() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("kind", "spec");
        fields.put("capability", "{path[0]}");
        fields.put("owner", "{frontmatter.owner}");
        fields.put("missing", "{frontmatter.absent}");
        MapSettings map = map("{path[0]}", "{document.key}/{slug}", "{heading.name}",
                List.of("{path[0]}", "{title}"), "{breadcrumb}\n\n{body}", fields, List.of("tables"));

        List<ChunkRecord> records = records("payments/spec.md", SPEC, REQUIREMENTS, map);

        assertThat(records).hasSize(2);
        ChunkRecord first = records.getFirst();
        assertThat(first.document()).isEqualTo("payments");
        assertThat(first.id()).isEqualTo("payments/create-a-payment");
        assertThat(first.title()).isEqualTo("Create a payment");
        assertThat(first.breadcrumb()).isEqualTo("payments › Create a payment");
        assertThat(first.text()).isEqualTo("payments › Create a payment\n\nThe system SHALL create a payment.");
        assertThat(first.fields()).containsOnlyKeys("kind", "capability", "owner", "tables");
        assertThat(first.fields().get("kind")).containsExactly("spec");
        assertThat(first.fields().get("capability")).containsExactly("payments");
        assertThat(first.fields().get("owner")).containsExactly("billing-team");
        assertThat(first.fields().get("tables")).containsExactly("PAYMENT", "INVOICE");
    }

    @Test
    void byDefaultEveryFrontMatterFieldIsInheritedAndTheKeyIsTheIdFieldOrThePath() {
        List<ChunkRecord> withoutId = records("payments/spec.md", SPEC, REQUIREMENTS, MapSettings.DEFAULT);

        assertThat(withoutId.getFirst().document()).isEqualTo("payments/spec.md");
        assertThat(withoutId.getFirst().id()).isEqualTo("payments/spec.md#requirement-create-a-payment");
        assertThat(withoutId.getFirst().title()).isEqualTo("Requirement: Create a payment");
        assertThat(withoutId.getFirst().breadcrumb())
                .isEqualTo("payments Specification › Requirements › Requirement: Create a payment");
        assertThat(withoutId.getFirst().fields()).containsOnlyKeys("owner", "tables", "draft");

        List<ChunkRecord> withId = records("a.md", "---\nid: doc.a\n---\n# T\n## S\ntext\n",
                SplitSettings.DEFAULT, MapSettings.DEFAULT);
        assertThat(withId.getFirst().document()).isEqualTo("doc.a");
    }

    @Test
    void inheritNothingKeepsOnlyTheMappedFields() {
        MapSettings map = map(null, null, null, null, null, Map.of("kind", "spec"), List.of());

        assertThat(records("payments/spec.md", SPEC, REQUIREMENTS, map).getFirst().fields())
                .isEqualTo(Map.of("kind", List.of("spec")));
    }

    @Test
    void aMappedFieldReplacesAnInheritedOneAndAnEmptyResultRemovesIt() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("owner", "team:{frontmatter.owner}");
        fields.put("draft", "{frontmatter.absent}");
        MapSettings map = map(null, null, null, null, null, fields, null);

        Map<String, List<String>> result = records("payments/spec.md", SPEC, REQUIREMENTS, map).getFirst().fields();

        assertThat(result.get("owner")).containsExactly("team:billing-team");
        assertThat(result).containsOnlyKeys("owner", "tables");
    }

    @Test
    void aMultiValuedFieldIsCopiedWholeOnlyByASinglePlaceholder() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("all", "{frontmatter.tables}");
        fields.put("first", "table {frontmatter.tables}");
        MapSettings map = map(null, null, null, null, null, fields, List.of());

        Map<String, List<String>> result = records("payments/spec.md", SPEC, REQUIREMENTS, map).getFirst().fields();

        assertThat(result.get("all")).containsExactly("PAYMENT", "INVOICE");
        assertThat(result.get("first")).containsExactly("table PAYMENT");
    }

    @Test
    void idsStayUniqueWhateverTheTemplateGives() {
        MapSettings map = map(null, "{document.key}", null, null, null, null, null);

        assertThat(records("payments/spec.md", SPEC, REQUIREMENTS, map)).extracting(ChunkRecord::id)
                .containsExactly("payments/spec.md", "payments/spec.md-2");
    }

    @Test
    void anEmptyKeyOrIdIsAnErrorAndAnEmptyTextIsSkipped() {
        assertThatThrownBy(() -> records("payments/spec.md", SPEC, REQUIREMENTS,
                map("{frontmatter.absent}", null, null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("map.document")
                .hasMessageContaining("payments/spec.md");
        assertThatThrownBy(() -> records("payments/spec.md", SPEC, REQUIREMENTS,
                map(null, "{frontmatter.absent}", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("map.id");

        assertThat(records("payments/spec.md", SPEC, REQUIREMENTS,
                map(null, null, null, null, "{frontmatter.absent}", null, null))).isEmpty();
    }

    @Test
    void aPathPatternExtractsPartsOfThePath() {
        MapSettings map = new MapSettings("^(?<subsystem>[a-z]+)-(?<screen>[^/]+)/", null, null, null, null, null,
                Map.of("subsystem", "{path.subsystem}", "screen", "{path.screen}"), List.of());

        assertThat(records("billing-payment-list/spec.md", SPEC, REQUIREMENTS, map).getFirst().fields())
                .isEqualTo(Map.of("subsystem", List.of("billing"), "screen", List.of("payment-list")));
        // A path the pattern does not match leaves the values empty, so the fields are absent.
        assertThat(records("README.md", SPEC, REQUIREMENTS, map).getFirst().fields()).isEmpty();
    }

    @Test
    void pathValuesNameTheFileAndItsDirectories() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("area", "{path[0]}");
        fields.put("type", "{path[1]}");
        fields.put("file", "{path.name}");
        fields.put("dir", "{path.dir}");
        fields.put("beyond", "{path[7]}");
        MapSettings map = map(null, null, null, null, null, fields, List.of());

        Map<String, List<String>> result = records("admin/screens/users.md", "# Users\n## List\ntext\n",
                SplitSettings.DEFAULT, map).getFirst().fields();

        assertThat(result).isEqualTo(Map.of("area", List.of("admin"), "type", List.of("screens"),
                "file", List.of("users"), "dir", List.of("admin/screens")));
    }
}
