package ru.it_spectrum.ai.rag.mcp.collections;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateTest {

    private static final Map<String, List<String>> VALUES = Map.of(
            "a", List.of("one"),
            "many", List.of("x", " y ", ""),
            "blank", List.of(" "));

    @Test
    void placeholdersAreReplacedByTheFirstValueAndMissingOnesByNothing() {
        assertThat(Template.render("{a}-{many}-{none}!", VALUES::get)).isEqualTo("one-x-!");
        assertThat(Template.render("no placeholders", VALUES::get)).isEqualTo("no placeholders");
    }

    @Test
    void alternativesTakeTheFirstThatHasAValue() {
        assertThat(Template.render("{none|blank|a}", VALUES::get)).isEqualTo("one");
        assertThat(Template.render("{none | many}", VALUES::get)).isEqualTo("x");
    }

    @Test
    void aSinglePlaceholderYieldsEveryValue() {
        assertThat(Template.values(" {many} ", VALUES::get)).containsExactly("x", "y");
        assertThat(Template.values("v:{many}", VALUES::get)).containsExactly("v:x");
        assertThat(Template.values("constant", VALUES::get)).containsExactly("constant");
        assertThat(Template.values("{none}", VALUES::get)).isEmpty();
    }

    @Test
    void namesListEveryReferenceIncludingAlternatives() {
        assertThat(Template.names("{a}/{b|c} text {a}")).containsExactly("a", "b", "c", "a");
    }

    @Test
    void brokenTemplatesAreRejected() {
        assertThatThrownBy(() -> Template.names("{a")).hasMessageContaining("unclosed");
        assertThatThrownBy(() -> Template.names("{}")).hasMessageContaining("empty placeholder");
        assertThatThrownBy(() -> Template.names("{a|}")).hasMessageContaining("empty placeholder");
    }
}
