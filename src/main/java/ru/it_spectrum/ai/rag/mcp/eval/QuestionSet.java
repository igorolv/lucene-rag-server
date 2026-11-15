package ru.it_spectrum.ai.rag.mcp.eval;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A question set: a JSONL file with one question per line, as the first corpus keeps it. A line
 * has {@code question} and {@code expected}, a list of references to what answers it; {@code id}
 * names the question in a report. Other keys are ignored and blank lines skipped.
 */
public final class QuestionSet {

    /**
     * One question.
     *
     * @param id       the {@code id} of the line, or {@code line <n>}
     * @param question the text searched for
     * @param expected references to the records that answer it; may be empty
     */
    public record Question(String id, String question, List<String> expected) {
    }

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
                list.forEach(ref -> addRef(expected, ref));
            } else {
                addRef(expected, refs);
            }
            Object id = object.get("id");
            questions.add(new Question(id == null || id.toString().isBlank() ? "line " + (i + 1) : id.toString(),
                    question.strip(), List.copyOf(expected)));
        }
        return List.copyOf(questions);
    }

    private static void addRef(List<String> expected, Object ref) {
        if ((ref instanceof String || ref instanceof Number) && !ref.toString().isBlank()) {
            expected.add(ref.toString().strip());
        }
    }
}
