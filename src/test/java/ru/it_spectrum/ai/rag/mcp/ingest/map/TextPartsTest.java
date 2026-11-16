package ru.it_spectrum.ai.rag.mcp.ingest.map;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Cutting the text of a long record into parts. */
class TextPartsTest {

    private static final String HEADER = "| Столбец | Смысл | Свидетель |\n|---|---|---|";

    private static String table(int rows) {
        return HEADER + "\n" + IntStream.rangeClosed(1, rows)
                .mapToObj(i -> "| PAYMENT_COLUMN_" + i + " | сумма платежа в копейках, строка " + i + " | `PaymentView.xml:" + i + "` |")
                .collect(Collectors.joining("\n"));
    }

    @Test
    void aShortTextIsOnePart() {
        assertThat(TextParts.split("Платежи.", 200)).containsExactly("Платежи.");
    }

    @Test
    void everyPartOfACutTableStartsWithItsHeader() {
        String text = "Таблица платежей.\n\n" + table(20);

        List<String> parts = TextParts.split(text, 600);

        assertThat(parts).hasSizeGreaterThan(2);
        assertThat(parts.subList(1, parts.size())).allSatisfy(part -> assertThat(part).startsWith(HEADER + "\n| PAYMENT_COLUMN_"));
        assertThat(parts).allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(600));
        String rows = parts.stream().flatMap(p -> p.lines().filter(l -> l.startsWith("| PAYMENT_COLUMN_")))
                .collect(Collectors.joining("\n"));
        assertThat(rows).isEqualTo(table(20).lines().skip(2).collect(Collectors.joining("\n")));
    }

    @Test
    void textAfterATableGetsNoHeader() {
        String text = table(8) + "\nПосле таблицы идет текст без вертикальных черт, который сам по себе достаточно длинный, "
                + "чтобы не поместиться в ту же часть, что и строки таблицы над ним.";

        List<String> parts = TextParts.split(text, 400);

        assertThat(parts.getLast()).doesNotContain("| Столбец |");
    }

    @Test
    void aTableInsideAFencedBlockIsNotATable() {
        String text = "```\n" + table(12) + "\n```";

        List<String> parts = TextParts.split(text, 400);

        assertThat(String.join("\n", parts).split("\\| Столбец \\|", -1)).hasSize(2);
    }

    @Test
    void aLongLineIsCutBetweenWords() {
        String line = "Добавить запись в новый пакет обмена с НСПК ".repeat(20).strip();

        List<String> parts = TextParts.split(line, 300);

        assertThat(parts).hasSizeGreaterThan(1);
        List<String> words = List.of("Добавить", "запись", "в", "новый", "пакет", "обмена", "с", "НСПК");
        assertThat(parts).allSatisfy(part -> {
            assertThat(part.length()).isLessThanOrEqualTo(300);
            assertThat(part.split(" ")).allSatisfy(word -> assertThat(words).contains(word));
        });
        assertThat(String.join(" ", parts)).isEqualTo(line);
    }

    @Test
    void aLineWithoutBlanksIsCutAtTheLimit() {
        String line = "x".repeat(650);

        assertThat(TextParts.split(line, 300)).extracting(String::length).containsExactly(300, 300, 50);
    }

    @Test
    void aHeaderLongerThanHalfOfTheLimitIsNotRepeated() {
        String wide = "| " + "Очень длинное имя столбца | ".repeat(10) + "\n|" + "---|".repeat(10);
        String text = wide + "\n" + IntStream.rangeClosed(1, 30).mapToObj(i -> "| значение " + i + " |")
                .collect(Collectors.joining("\n"));

        List<String> parts = TextParts.split(text, 400);

        assertThat(parts.subList(1, parts.size())).noneSatisfy(part -> assertThat(part).contains("Очень длинное"));
    }
}
