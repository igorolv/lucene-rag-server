package ru.it_spectrum.ai.rag.mcp.eval;

import ru.it_spectrum.ai.rag.mcp.search.SearchHit;
import ru.it_spectrum.ai.rag.mcp.search.SearchMode;
import ru.it_spectrum.ai.rag.mcp.search.SearchPage;
import ru.it_spectrum.ai.rag.mcp.search.SearchService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures how well search finds what a question set expects: every question is searched in
 * every mode, and the rank of the first record that a reference of the question names is taken
 * (docs/plan.md section 4, stage 4).
 *
 * <ul>
 *   <li>A reference that names no record of the searched collections is <em>unreachable</em>:
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
     */
    public record Outcome(QuestionSet.Question question, List<String> reachable, List<String> unreachable,
                          Map<SearchMode, Integer> ranks, Map<SearchMode, Integer> found) {
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
     * @param measures one per mode, in the order asked
     */
    public record Report(int depth, List<Outcome> outcomes, List<Measures> measures) {

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
     * @throws IllegalArgumentException for an unknown collection or a mode the collections do not have
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
        Map<SearchMode, Long> nanos = new LinkedHashMap<>();
        Map<SearchMode, Integer> searches = new LinkedHashMap<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (QuestionSet.Question question : questions) {
            List<String> reachable = new ArrayList<>();
            List<String> unreachable = new ArrayList<>();
            for (String ref : question.expected()) {
                (all.stream().anyMatch(hit -> references.matches(ref, hit)) ? reachable : unreachable).add(ref);
            }
            Map<SearchMode, Integer> ranks = new LinkedHashMap<>();
            Map<SearchMode, Integer> found = new LinkedHashMap<>();
            if (!reachable.isEmpty()) {
                for (SearchMode mode : modes) {
                    long start = System.nanoTime();
                    SearchPage page = search.search(collections, question.question(), null, null, depth, 0,
                            mode.id(), perDocument);
                    nanos.merge(mode, System.nanoTime() - start, Long::sum);
                    searches.merge(mode, 1, Integer::sum);
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
            outcomes.add(new Outcome(question, List.copyOf(reachable), List.copyOf(unreachable), ranks, found));
        }
        List<Measures> measures = new ArrayList<>();
        for (SearchMode mode : modes) {
            int measured = 0;
            int first = 0;
            int top5 = 0;
            int top = 0;
            double rr = 0;
            double recall = 0;
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
            }
            int count = searches.getOrDefault(mode, 0);
            measures.add(new Measures(mode, measured, first, top5, top, measured == 0 ? 0 : rr / measured,
                    measured == 0 ? 0 : recall / measured, count == 0 ? 0 : nanos.get(mode) / 1e6 / count));
        }
        return new Report(depth, List.copyOf(outcomes), List.copyOf(measures));
    }
}
