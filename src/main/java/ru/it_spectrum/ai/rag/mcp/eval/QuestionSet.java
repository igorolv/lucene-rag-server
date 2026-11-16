package ru.it_spectrum.ai.rag.mcp.eval;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A question set: a JSONL file with one question per line, as the first corpus keeps it. A line
 * has {@code question} and {@code expected}, a list of references to what answers it; {@code id}
 * names the question in a report. Any other key whose value is a string, a number or a list of them is
 * kept as a value of the question, by which {@code rag-eval --group-by} groups the measures;
 * other values are ignored and blank lines skipped.
 */
public final class QuestionSet {

    /**
     * One question.
     *
     * @param id       the {@code id} of the line, or {@code line <n>}
     * @param question the text searched for
     * @param expected references to the records that answer it; may be empty
     * @param values   the other keys of the line with their string values, a list for each key
     */
    public record Question(String id, String question, List<String> expected, Map<String, List<String>> values) {

        public Question {
            values = Map.copyOf(values);
        }

        /** A question with no other keys. */
        public Question(String id, String question, List<String> expected) {
            this(id, question, expected, Map.of());
        }

        /** The values of a key, empty when the line has none. */
        public List<String> values(String key) {
            return values.getOrDefault(key, List.of());
        }
    }

    private static final Set<String> OWN_KEYS = Set.of("id", "question", "expected");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private QuestionSet() {
    }

    /**
     * @throws IOException              when the file cannot be read
     * @throws IllegalArgumentException naming the line that is not a question
     */
    public static List<Question> read(Path file) throws IOException {
        List<Question> questions = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            String where = file.getFileName() + ", line " + (i + 1);
            final Object parsed;
            try {
                parsed = JSON.readValue(line, Object.class);
            } catch (JacksonException e) {
                throw new IllegalArgumentException(where + " is not valid JSON: " + e.getOriginalMessage(), e);
            }
            if (!(parsed instanceof Map<?, ?> object) || !(object.get("question") instanceof String question)
                    || question.isBlank()) {
                throw new IllegalArgumentException(where + " has no question");
            }
            List<String> expected = new ArrayList<>();
            Object refs = object.get("expected");
            if (refs instanceof Collection<?> list) {
                list.forEach(ref -> addScalar(expected, ref));
            } else {
                addScalar(expected, refs);
            }
            Map<String, List<String>> values = new LinkedHashMap<>();
            object.forEach((key, value) -> {
                if (!OWN_KEYS.contains(String.valueOf(key))) {
                    List<String> strings = new ArrayList<>();
                    if (value instanceof Collection<?> list) {
                        list.forEach(v -> addScalar(strings, v));
                    } else {
                        addScalar(strings, value);
                    }
                    if (!strings.isEmpty()) {
                        values.put(String.valueOf(key), List.copyOf(strings));
                    }
                }
            });
            Object id = object.get("id");
            questions.add(new Question(id == null || id.toString().isBlank() ? "line " + (i + 1) : id.toString(),
                    question.strip(), List.copyOf(expected), values));
        }
        return List.copyOf(questions);
    }

    private static void addScalar(List<String> into, Object value) {
        if ((value instanceof String || value instanceof Number) && !value.toString().isBlank()) {
            into.add(value.toString().strip());
        }
    }
}
