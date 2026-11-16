package ru.it_spectrum.ai.rag.mcp.eval;

import ru.it_spectrum.ai.rag.mcp.search.SearchHit;
import ru.it_spectrum.ai.rag.mcp.search.SearchMode;
import ru.it_spectrum.ai.rag.mcp.search.SearchPage;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measures how well search finds what a question set expects: every question is searched in
 * every mode, and the rank of the first record that a reference of the question names is taken
 * (docs/plan.md section 4, stage 4).
 *
 * <ul>
 *   <li>A reference that names no record of the searched indexes is <em>unreachable</em>:
 *       no search can find it, so it is reported apart and left out of the measures. A question
 *       with no reachable reference is left out too.</li>
 *   <li>Ranks count records as a client sees them, after the limit of records per document
 *       that search applies.</li>
 *   <li>For each mode: the questions whose first match is at rank 1, within 5 and within the
 *       depth; the mean reciprocal rank (0 for a question not found within the depth); recall,
 *       the mean share of the reachable references of a question matched within the depth; the
 *       mean time of a search.</li>
 * </ul>
 */
public final class Evaluation {

    /**
     * The outcome of one question.
     *
     * @param question    the question
     * @param reachable   its references that name a record
     * @param unreachable its references that name none
     * @param ranks       per mode, the rank of the first match from 1, or 0 when none is within the depth
     * @param found       per mode, how many reachable references a record within the depth matches
     * @param nanos       per mode, how long its search took
     */
    public record Outcome(QuestionSet.Question question, List<String> reachable, List<String> unreachable,
                          Map<SearchMode, Integer> ranks, Map<SearchMode, Integer> found,
                          Map<SearchMode, Long> nanos) {
    }

    /**
     * The measures of one mode over the measured questions.
     *
     * @param mode      the mode
     * @param measured  questions with a reachable reference
     * @param first     questions found at rank 1
     * @param top5      questions found within rank 5
     * @param top       questions found within the depth
     * @param mrr       mean reciprocal rank
     * @param recall    mean share of the reachable references found within the depth
     * @param millis    mean milliseconds per search
     */
    public record Measures(SearchMode mode, int measured, int first, int top5, int top, double mrr, double recall,
                           double millis) {
    }

    /**
     * @param depth    how many records of each search are looked at
     * @param outcomes one per question, in the order of the set
     * @param modes    the modes measured, in the order asked
     * @param measures one per mode, in the order asked
     */
    public record Report(int depth, List<Outcome> outcomes, List<SearchMode> modes, List<Measures> measures) {

        /** A group's name for the questions that have no value of the key. */
        public static final String NO_VALUE = "(none)";

        /**
         * The measures of each group of questions that share a value of {@code key}, by value in
         * alphabetical order and {@link #NO_VALUE} last. A question with several values of the key
         * counts in each of their groups.
         */
        public Map<String, List<Measures>> groups(String key) {
            Map<String, List<Outcome>> byValue = new TreeMap<>();
            List<Outcome> without = new ArrayList<>();
            for (Outcome outcome : outcomes) {
                List<String> values = outcome.question().values(key);
                if (values.isEmpty()) {
                    without.add(outcome);
                }
                new LinkedHashSet<>(values).forEach(v -> byValue.computeIfAbsent(v, k -> new ArrayList<>()).add(outcome));
            }
            Map<String, List<Measures>> result = new LinkedHashMap<>();
            byValue.forEach((value, group) -> result.put(value, measure(group, modes)));
            if (!without.isEmpty()) {
                result.put(NO_VALUE, measure(without, modes));
            }
            return result;
        }

        public long measured() {
            return outcomes.stream().filter(o -> !o.reachable().isEmpty()).count();
        }

        public long withoutReferences() {
            return outcomes.stream().filter(o -> o.reachable().isEmpty() && o.unreachable().isEmpty()).count();
        }
    }

    private final SearchService search;
    private final References references;

    /**
     * @param search     a search whose limits allow {@code depth} records per page and do not cut a
     *                   page by size
     * @param references how references name records
     */
    public Evaluation(SearchService search, References references) {
        this.search = search;
        this.references = references;
    }

    /**
     * @throws IllegalArgumentException for an unknown index or a mode the indexes do not have
     * @throws IOException              when an index cannot be read or a model fails
     */
    public Report run(List<String> collections, List<QuestionSet.Question> questions, List<SearchMode> modes,
                      int depth) throws IOException {
        return run(collections, questions, modes, depth, null);
    }

    /**
     * @param perDocument records of one document in the results, as the {@code search} tool takes
     *                    it; {@code null} for its default
     */
    public Report run(List<String> collections, List<QuestionSet.Question> questions, List<SearchMode> modes,
                      int depth, Integer perDocument) throws IOException {
        List<SearchHit> all = search.all(collections);
        List<Outcome> outcomes = new ArrayList<>();
        for (QuestionSet.Question question : questions) {
            List<String> reachable = new ArrayList<>();
            List<String> unreachable = new ArrayList<>();
            for (String ref : question.expected()) {
                (all.stream().anyMatch(hit -> references.matches(ref, hit)) ? reachable : unreachable).add(ref);
            }
            Map<SearchMode, Integer> ranks = new LinkedHashMap<>();
            Map<SearchMode, Integer> found = new LinkedHashMap<>();
            Map<SearchMode, Long> nanos = new LinkedHashMap<>();
            if (!reachable.isEmpty()) {
                for (SearchMode mode : modes) {
                    long start = System.nanoTime();
                    SearchPage page = search.search(collections, question.question(), null, null, depth, 0,
                            mode.id(), perDocument);
                    nanos.put(mode, System.nanoTime() - start);
                    List<SearchHit> hits = page.hits();
                    int rank = 0;
                    for (int i = 0; i < hits.size(); i++) {
                        if (references.matchesAny(reachable, hits.get(i))) {
                            rank = i + 1;
                            break;
                        }
                    }
                    ranks.put(mode, rank);
                    found.put(mode, (int) reachable.stream()
                            .filter(ref -> hits.stream().anyMatch(hit -> references.matches(ref, hit))).count());
                }
            }
            outcomes.add(new Outcome(question, List.copyOf(reachable), List.copyOf(unreachable), ranks, found,
                    nanos));
        }
        return new Report(depth, List.copyOf(outcomes), List.copyOf(modes), measure(outcomes, modes));
    }

    /** The measures of each mode over the measured questions of {@code outcomes}. */
    static List<Measures> measure(List<Outcome> outcomes, List<SearchMode> modes) {
        List<Measures> measures = new ArrayList<>();
        for (SearchMode mode : modes) {
            int measured = 0;
            int first = 0;
            int top5 = 0;
            int top = 0;
            double rr = 0;
            double recall = 0;
            long nanos = 0;
            for (Outcome outcome : outcomes) {
                if (outcome.reachable().isEmpty()) {
                    continue;
                }
                measured++;
                int rank = outcome.ranks().get(mode);
                if (rank > 0) {
                    top++;
                    rr += 1.0 / rank;
                    first += rank == 1 ? 1 : 0;
                    top5 += rank <= 5 ? 1 : 0;
                }
                recall += (double) outcome.found().get(mode) / outcome.reachable().size();
                nanos += outcome.nanos().get(mode);
            }
            measures.add(new Measures(mode, measured, first, top5, top, measured == 0 ? 0 : rr / measured,
                    measured == 0 ? 0 : recall / measured, measured == 0 ? 0 : nanos / 1e6 / measured));
        }
        return List.copyOf(measures);
    }
}
