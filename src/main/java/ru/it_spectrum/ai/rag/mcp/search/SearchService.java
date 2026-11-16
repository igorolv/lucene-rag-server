package ru.it_spectrum.ai.rag.mcp.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.simple.SimpleQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.filter.Filter;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelParameters;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelUnavailableException;
import ru.it_spectrum.ai.rag.mcp.embedding.VectorModel;
import ru.it_spectrum.ai.rag.mcp.index.Analyzers;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexStore;
import ru.it_spectrum.ai.rag.mcp.index.IndexFields;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lexical, vector and hybrid search over built collections.
 *
 * <ul>
 *   <li>The query is plain words; {@code "quoted words"} are a phrase. Words are optional: a
 *       record matching more of them, or matching them in its title, ranks higher (BM25), so a
 *       question in natural language still finds its passage.</li>
 *   <li>Filters are exact matches on document fields, case-insensitive; every filter must hold. A
 *       filter expression of Spring AI adds "or", "not", lists and text ranges
 *       ({@link FilterQueries}); it must hold together with the filters.</li>
 *   <li>Several collections are searched as one index, with shared term statistics.</li>
 *   <li>A page holds at most {@code perDocument} records of one document, 1 by default: the best
 *       ranked; the other sections of the document drop out of the results (docs/decisions.md
 *       D38). A row of a catalog, a table or a row format stands alone and is never dropped.</li>
 *   <li>The number of results and the total size of the returned text are capped by the server
 *       settings whatever a call asks for.</li>
 * </ul>
 */
public final class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    public static final int MAX_QUERY_CHARS = 1_000;

    /** A snippet longer than this is cut; a passage of prose is normally one or two sentences. */
    private static final int SNIPPET_MAX_CHARS = 600;

    private static final Map<String, Float> WEIGHTS = Map.of(
            IndexFields.TEXT, 1f, IndexFields.TEXT_EN, 1f,
            IndexFields.TITLE, 2f, IndexFields.TITLE_EN, 2f);

    /**
     * Candidates each ranked list of a vector or hybrid search contributes, at least; a page that
     * reaches further asks for more.
     */
    static final int CANDIDATES = 100;

    /** The constant of reciprocal rank fusion: a record at rank r of a list scores 1 / (k + r). */
    static final int RRF_K = 60;

    /** Records of one document a page holds when a call does not say. */
    public static final int DEFAULT_PER_DOCUMENT = 1;

    private final IndexStore store;
    private final RagMcpProperties properties;
    private final EmbeddingModels models;
    private final Analyzer analyzer = Analyzers.analyzer();

    /** A search without models: lexical only. */
    public SearchService(IndexStore store, RagMcpProperties properties) {
        this(store, properties, null);
    }

    /**
     * @param models the embedding models queries of vector and hybrid search are embedded with;
     *               {@code null} allows lexical search only
     */
    public SearchService(IndexStore store, RagMcpProperties properties, EmbeddingModels models) {
        this.store = store;
        this.properties = properties;
        this.models = models;
    }

    /** A search in the default mode. */
    public SearchPage search(List<String> collections, String query, Map<String, String> filters,
                             Integer limit, Integer offset) throws IOException {
        return search(collections, query, filters, null, limit, offset, null);
    }

    /** A search without a filter expression. */
    public SearchPage search(List<String> collections, String query, Map<String, String> filters,
                             Integer limit, Integer offset, String mode) throws IOException {
        return search(collections, query, filters, null, limit, offset, mode);
    }

    /** A search with the default number of records per document. */
    public SearchPage search(List<String> collections, String query, Map<String, String> filters,
                             String filterExpression, Integer limit, Integer offset, String mode) throws IOException {
        return search(collections, query, filters, filterExpression, limit, offset, mode, null);
    }

    /**
     * @param collections names of built collections; at least one
     * @param query       words to look for; blank lists the records matching the filters
     * @param filters     field name to required value; may be {@code null}
     * @param filterExpression a Spring AI filter expression in its text syntax; blank or
     *                    {@code null} for none (docs/decisions.md D30)
     * @param limit       page size; {@code null} for the server default, capped by the server maximum
     * @param offset      results to skip; {@code null} for none
     * @param mode        {@code lexical}, {@code vector} or {@code hybrid}; blank for hybrid when a
     *                    searched collection has vectors and lexical otherwise
     * @param perDocument the most records of one document in the results; {@code null} for
     *                    {@value #DEFAULT_PER_DOCUMENT}
     * @throws IllegalArgumentException  for an unknown collection, an unknown filter field or a bad argument
     * @throws ModelUnavailableException when the call asks for {@code vector} or {@code hybrid} and the
     *                                   model of a collection cannot be used; without a mode the
     *                                   search runs lexically and says why in its notice
     */
    public SearchPage search(List<String> collections, String query, Map<String, String> filters,
                             String filterExpression, Integer limit, Integer offset, String mode, Integer perDocument)
            throws IOException {
        if (perDocument != null && perDocument < 1) {
            throw new IllegalArgumentException("perDocument must be at least 1");
        }
        int perGroup = perDocument == null ? DEFAULT_PER_DOCUMENT : perDocument;
        SearchMode requested = SearchMode.parse(mode);
        List<Manifest> manifests = resolve(collections);
        String text = query == null ? "" : query.strip();
        if (text.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("query is longer than " + MAX_QUERY_CHARS + " characters");
        }
        Query textQuery = text.isEmpty() ? null : parse(text);
        SearchMode effective = effectiveMode(requested, manifests, textQuery != null);
        Query filter = filter(manifests, filters, filterExpression);
        var builder = new BooleanQuery.Builder();
        builder.add(textQuery == null ? new MatchAllDocsQuery() : textQuery, BooleanClause.Occur.MUST);
        if (filter != null) {
            builder.add(filter, BooleanClause.Occur.FILTER);
        }
        if (effective == SearchMode.LEXICAL) {
            return page(manifests, builder.build(), textQuery, limit, offset, SearchMode.LEXICAL, perGroup);
        }
        try {
            return fused(manifests, effective, text, builder.build(), filter, textQuery, limit, offset, perGroup);
        } catch (ModelUnavailableException e) {
            if (requested != null) {
                throw e;
            }
            // The call named no mode: words still find what they can (docs/search.md, "Modes").
            log.info("Searching lexically: {}", e.getMessage());
            return page(manifests, builder.build(), textQuery, limit, offset, SearchMode.LEXICAL, perGroup)
                    .withNotice("Searched by words only: " + e.getMessage());
        }
    }

    /** How deep an explanation looks into each ranked list for the record it is asked about. */
    static final int EXPLAIN_DEPTH = 1_000;

    /**
     * A record of an explanation.
     *
     * @param hit         the record, without text or snippet, with its fused score
     * @param rank        its place in the ranking of the search, 1 first, before the limit of
     *                    records per document; {@code null} beyond {@value #EXPLAIN_DEPTH}
     * @param lexicalRank its place among the records the words match; {@code null} when the words
     *                    do not match it, it is beyond the depth, or the mode has no word list
     * @param vectorRank  its place among the records nearest to the query vector of its model;
     *                    {@code null} beyond the depth or when the mode has no vector list
     */
    public record Ranked(SearchHit hit, Integer rank, Integer lexicalRank, Integer vectorRank) {
    }

    /**
     * Why a search ranks records as it does (docs/decisions.md D72).
     *
     * @param mode          the mode the search ran in
     * @param terms         the query's words as the Russian analysis indexes them, stop words gone
     * @param englishTerms  the same for the English analysis
     * @param matching      records the words and filters match
     * @param ranked        the first records of the ranking
     * @param target        the record asked about; {@code null} when none was
     * @param matchesWords  whether the words match the target at all
     * @param passesFilters whether the target passes the filters
     * @param notice        why the mode differs from the one asked for; {@code null} when it does not
     */
    public record Explanation(SearchMode mode, List<String> terms, List<String> englishTerms, int matching,
                              List<Ranked> ranked, Ranked target, Boolean matchesWords, Boolean passesFilters,
                              String notice) {
    }

    /**
     * Explains a search: the words the query becomes, and for the first records of the ranking and
     * for one record by its id, the rank in the fused ranking and in each list fused. The limit of
     * records per document is not applied, so a record dropped by it is still shown.
     *
     * @param id    a record to explain; {@code null} for none
     * @param limit records of the ranking to show; capped as a page of search is
     * @throws IllegalArgumentException as {@link #search} does, and for an id no collection has
     */
    public Explanation explain(List<String> collections, String query, Map<String, String> filters,
                               String filterExpression, String mode, String id, Integer limit) throws IOException {
        SearchMode requested = SearchMode.parse(mode);
        List<Manifest> manifests = resolve(collections);
        String text = query == null ? "" : query.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }
        if (text.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("query is longer than " + MAX_QUERY_CHARS + " characters");
        }
        Query textQuery = parse(text);
        SearchMode effective = effectiveMode(requested, manifests, true);
        Query filter = filter(manifests, filters, filterExpression);
        var builder = new BooleanQuery.Builder().add(textQuery, BooleanClause.Occur.MUST);
        if (filter != null) {
            builder.add(filter, BooleanClause.Occur.FILTER);
        }
        Query lexical = builder.build();
        Map<VectorGroup, List<Integer>> groups = new LinkedHashMap<>();
        Map<VectorGroup, float[]> queries = new LinkedHashMap<>();
        String notice = null;
        if (effective != SearchMode.LEXICAL) {
            for (int i = 0; i < manifests.size(); i++) {
                Manifest.Vectors vectors = manifests.get(i).vectors();
                if (vectors != null) {
                    groups.computeIfAbsent(VectorGroup.of(vectors), k -> new ArrayList<>()).add(i);
                }
            }
            try {
                for (var group : groups.entrySet()) {
                    queries.put(group.getKey(), queryVector(manifests.get(group.getValue().getFirst()), text));
                }
            } catch (ModelUnavailableException e) {
                if (requested != null) {
                    throw e;
                }
                effective = SearchMode.LEXICAL;
                groups.clear();
                notice = "Searched by words only: " + e.getMessage();
            }
        }
        try (Opened opened = open(manifests)) {
            IndexSearcher searcher = new IndexSearcher(opened.reader);
            List<TopDocs> lists = new ArrayList<>();
            Map<Integer, Integer> lexicalRanks = new HashMap<>();
            Map<Integer, Integer> vectorRanks = new HashMap<>();
            if (effective != SearchMode.VECTOR) {
                TopDocs top = searcher.search(lexical, EXPLAIN_DEPTH);
                lists.add(top);
                ranks(top, lexicalRanks);
            }
            for (var group : groups.entrySet()) {
                TopDocs top = nearest(opened, group.getValue(), queries.get(group.getKey()), filter, EXPLAIN_DEPTH);
                lists.add(top);
                ranks(top, vectorRanks);
            }
            TopDocs fused = lists.size() == 1 ? lists.getFirst()
                    : TopDocs.rrf(EXPLAIN_DEPTH, RRF_K, lists.toArray(TopDocs[]::new));
            Map<Integer, Integer> fusedRanks = new HashMap<>();
            ranks(fused, fusedRanks);
            Map<Integer, Float> scores = new HashMap<>();
            for (ScoreDoc scoreDoc : fused.scoreDocs) {
                scores.put(scoreDoc.doc, scoreDoc.score);
            }
            var stored = searcher.storedFields();
            List<Ranked> ranked = new ArrayList<>();
            for (int i = 0; i < Math.min(pageSize(limit), fused.scoreDocs.length); i++) {
                int doc = fused.scoreDocs[i].doc;
                ranked.add(new Ranked(hit(stored.document(doc), fused.scoreDocs[i].score, null, null), i + 1,
                        lexicalRanks.get(doc), vectorRanks.get(doc)));
            }
            Ranked target = null;
            Boolean matchesWords = null;
            Boolean passesFilters = null;
            if (id != null && !id.isBlank()) {
                Query byId = new TermQuery(new Term(IndexFields.ID, id.strip()));
                TopDocs found = searcher.search(byId, 1);
                if (found.scoreDocs.length == 0) {
                    throw new IllegalArgumentException("No record '" + id.strip() + "' in "
                            + String.join(", ", manifests.stream().map(Manifest::name).toList()));
                }
                int doc = found.scoreDocs[0].doc;
                target = new Ranked(hit(stored.document(doc), scores.getOrDefault(doc, 0f), null, null),
                        fusedRanks.get(doc), lexicalRanks.get(doc), vectorRanks.get(doc));
                matchesWords = searcher.count(new BooleanQuery.Builder().add(byId, BooleanClause.Occur.MUST)
                        .add(textQuery, BooleanClause.Occur.MUST).build()) > 0;
                passesFilters = filter == null || searcher.count(new BooleanQuery.Builder()
                        .add(byId, BooleanClause.Occur.MUST).add(filter, BooleanClause.Occur.FILTER).build()) > 0;
            }
            return new Explanation(effective, analyzed(IndexFields.TEXT, text), analyzed(IndexFields.TEXT_EN, text),
                    searcher.count(lexical), List.copyOf(ranked), target, matchesWords, passesFilters, notice);
        }
    }

    private static void ranks(TopDocs top, Map<Integer, Integer> ranks) {
        for (int i = 0; i < top.scoreDocs.length; i++) {
            ranks.putIfAbsent(top.scoreDocs[i].doc, i + 1);
        }
    }

    /** The terms a field's analysis makes of a text, in order, without repeats. */
    private List<String> analyzed(String field, String text) throws IOException {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream stream = analyzer.tokenStream(field, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        }
        return List.copyOf(terms);
    }

    /**
     * The filters and the filter expression as one Lucene query, or {@code null} for none. The map
     * is the equality conditions of a Spring AI expression joined by "and".
     */
    private static Query filter(List<Manifest> manifests, Map<String, String> filters, String filterExpression) {
        Filter.Expression expression = FilterQueries.of(filters);
        if (filterExpression != null && !filterExpression.isBlank()) {
            expression = FilterQueries.and(expression, FilterQueries.parse(filterExpression.strip()));
        }
        if (expression == null) {
            return null;
        }
        Set<String> known = manifests.stream().flatMap(m -> m.fields().stream()).collect(Collectors.toSet());
        return new FilterQueries(known).query(expression);
    }

    /** The mode a search runs in, or why the requested one cannot. */
    private static SearchMode effectiveMode(SearchMode requested, List<Manifest> manifests, boolean hasQuery) {
        List<String> withVectors = manifests.stream().filter(m -> m.vectors() != null).map(Manifest::name).toList();
        if (requested == null) {
            return hasQuery && !withVectors.isEmpty() ? SearchMode.HYBRID : SearchMode.LEXICAL;
        }
        if (requested == SearchMode.LEXICAL) {
            return requested;
        }
        if (!hasQuery) {
            throw new IllegalArgumentException("Mode " + requested.id() + " needs a query");
        }
        List<String> without = manifests.stream().filter(m -> m.vectors() == null).map(Manifest::name).toList();
        if (requested == SearchMode.VECTOR && !without.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", without) + (without.size() == 1 ? " has" : " have")
                    + " no vectors; search with mode lexical or hybrid");
        }
        if (withVectors.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", without) + (without.size() == 1 ? " has" : " have")
                    + " no vectors; search with mode lexical");
        }
        return requested;
    }

    /**
     * One page of a vector or hybrid search. Each model ranks the nearest records of its
     * collections; a hybrid search adds the BM25 list; several lists are fused by reciprocal rank
     * fusion (docs/decisions.md D12). Collections without vectors take part in the BM25 list only.
     */
    private SearchPage fused(List<Manifest> manifests, SearchMode mode, String text, Query lexical, Query filter,
                             Query textQuery, Integer limit, Integer offset, int perGroup) throws IOException {
        int pageSize = pageSize(limit);
        int skip = offset == null ? 0 : Math.max(0, offset);
        int window = Math.max(CANDIDATES, skip + pageSize);
        Map<VectorGroup, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < manifests.size(); i++) {
            Manifest.Vectors vectors = manifests.get(i).vectors();
            if (vectors != null) {
                groups.computeIfAbsent(VectorGroup.of(vectors), k -> new ArrayList<>()).add(i);
            }
        }
        Map<VectorGroup, float[]> queries = new LinkedHashMap<>();
        for (var group : groups.entrySet()) {
            queries.put(group.getKey(), queryVector(manifests.get(group.getValue().getFirst()), text));
        }
        try (Opened opened = open(manifests)) {
            IndexSearcher searcher = new IndexSearcher(opened.reader);
            List<TopDocs> lists = new ArrayList<>();
            if (mode == SearchMode.HYBRID) {
                lists.add(searcher.search(lexical, window));
            }
            for (var group : groups.entrySet()) {
                lists.add(nearest(opened, group.getValue(), queries.get(group.getKey()), filter, window));
            }
            TopDocs top = lists.size() == 1 ? lists.getFirst()
                    : TopDocs.rrf(window, RRF_K, lists.toArray(TopDocs[]::new));
            List<ScoreDoc> ranked = limited(searcher, top.scoreDocs, perGroup, Integer.MAX_VALUE);
            return collect(searcher, ranked, top.scoreDocs.length, ranked.size(), textQuery, skip, pageSize, mode);
        }
    }

    /** The query embedded by the model of a collection, checked against its manifest. */
    private float[] queryVector(Manifest manifest, String text) throws IOException {
        Manifest.Vectors vectors = manifest.vectors();
        if (models == null) {
            throw new ModelUnavailableException("Collection '" + manifest.name()
                    + "' needs its embedding model for vector and hybrid search, and none is available here; "
                    + "search with mode lexical");
        }
        VectorModel model = models.verified(manifest.name(), vectors.model(), vectors.parameters(),
                vectors.dimension(), vectors.files());
        return model.query(text);
    }

    /**
     * The records nearest to the query in the collections of one model, with doc ids of the
     * reader of all searched collections. Scores of one model are comparable across collections.
     */
    private static TopDocs nearest(Opened opened, List<Integer> collections, float[] query, Query filter, int window)
            throws IOException {
        List<ScoreDoc> all = new ArrayList<>();
        for (int i : collections) {
            IndexSearcher one = new IndexSearcher(opened.readers.get(i));
            TopDocs top = one.search(new KnnFloatVectorQuery(IndexFields.VECTOR, query, window, filter), window);
            for (ScoreDoc scoreDoc : top.scoreDocs) {
                all.add(new ScoreDoc(scoreDoc.doc + opened.bases.get(i), scoreDoc.score));
            }
        }
        all.sort(Comparator.comparingDouble((ScoreDoc d) -> d.score).reversed().thenComparingInt(d -> d.doc));
        List<ScoreDoc> best = all.subList(0, Math.min(window, all.size()));
        return new TopDocs(new TotalHits(best.size(), TotalHits.Relation.EQUAL_TO), best.toArray(ScoreDoc[]::new));
    }

    /** Collections whose queries are embedded the same way and whose scores are comparable. */
    private record VectorGroup(String model, ModelParameters parameters, int dimension, Map<String, String> files) {
        static VectorGroup of(Manifest.Vectors vectors) {
            return new VectorGroup(vectors.model(), vectors.parameters(), vectors.dimension(),
                    vectors.files() == null ? Map.of() : Map.copyOf(vectors.files()));
        }
    }

    /**
     * Records of which a key field ({@code map.keys}) has the value, compared as filters compare:
     * without surrounding blanks and ignoring case. Results come in index order.
     *
     * @param collections names of built collections; at least one has key fields
     * @throws IllegalArgumentException for an unknown collection, a blank value or collections
     *                                  without key fields
     */
    public SearchPage lookup(List<String> collections, String value, Integer limit, Integer offset)
            throws IOException {
        List<Manifest> manifests = resolve(collections);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value is required");
        }
        if (value.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("value is longer than " + MAX_QUERY_CHARS + " characters");
        }
        if (manifests.stream().allMatch(m -> m.keys().isEmpty())) {
            throw new IllegalArgumentException(String.join(", ", manifests.stream().map(Manifest::name).toList())
                    + " has no key fields to look up (map.keys). Collections with keys: " + withKeys());
        }
        Query query = new TermQuery(new Term(IndexFields.LOOKUP, IndexBuilder.filterTerm(value)));
        return page(manifests, query, null, limit, offset, null, Integer.MAX_VALUE);
    }

    private String withKeys() {
        List<String> names = store.built().stream().filter(m -> !m.keys().isEmpty()).map(Manifest::name).toList();
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    /**
     * One page of the records a query matches, with snippets of the words of {@code textQuery}.
     * When records of one document are limited, more are ranked until the page and one record
     * after it are found or the matches run out.
     */
    private SearchPage page(List<Manifest> manifests, Query full, Query textQuery, Integer limit, Integer offset,
                            SearchMode mode, int perGroup) throws IOException {
        int pageSize = pageSize(limit);
        int skip = offset == null ? 0 : Math.max(0, offset);
        try (Opened opened = open(manifests)) {
            IndexSearcher searcher = new IndexSearcher(opened.reader);
            int total = searcher.count(full);
            int needed = skip + pageSize + 1;
            if (perGroup == Integer.MAX_VALUE) {
                TopDocs top = searcher.search(full, Math.max(1, skip + pageSize));
                return collect(searcher, List.of(top.scoreDocs), total, total, textQuery, skip, pageSize, mode);
            }
            int depth = needed;
            while (true) {
                TopDocs top = searcher.search(full, Math.max(1, depth));
                List<ScoreDoc> ranked = limited(searcher, top.scoreDocs, perGroup, needed);
                if (ranked.size() >= needed || top.scoreDocs.length < depth || depth >= total) {
                    return collect(searcher, ranked, total, ranked.size(), textQuery, skip, pageSize, mode);
                }
                depth = (int) Math.min(total, (long) depth * 4);
            }
        }
    }

    /**
     * The ranked records with at most {@code perGroup} of one group ({@link IndexFields#STORED_GROUP}
     * in its collection), stopping at {@code wanted} records.
     */
    private static List<ScoreDoc> limited(IndexSearcher searcher, ScoreDoc[] ranked, int perGroup, int wanted)
            throws IOException {
        List<ScoreDoc> kept = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        var stored = searcher.storedFields();
        Set<String> fields = Set.of(IndexFields.STORED_GROUP, IndexFields.COLLECTION);
        for (ScoreDoc scoreDoc : ranked) {
            if (kept.size() >= wanted) {
                break;
            }
            if (perGroup != Integer.MAX_VALUE) {
                Document doc = stored.document(scoreDoc.doc, fields);
                String group = doc.get(IndexFields.STORED_GROUP);
                String key = doc.get(IndexFields.COLLECTION) + "\u0000" + (group == null ? "#" + scoreDoc.doc : group);
                if (seen.merge(key, 1, Integer::sum) > perGroup) {
                    continue;
                }
            }
            kept.add(scoreDoc);
        }
        return kept;
    }

    private int pageSize(Integer limit) {
        return limit == null ? properties.searchDefaultLimit()
                : Math.min(properties.searchMaxLimit(), Math.max(1, limit));
    }

    /**
     * The page of ranked records from {@code skip} on, with snippets, within the response size cap.
     *
     * @param total     what the result reports as the number of matches
     * @param available how many results there are to page through; a next page exists below it
     */
    private SearchPage collect(IndexSearcher searcher, List<ScoreDoc> ranked, int total, int available,
                               Query textQuery, int skip, int pageSize, SearchMode mode) throws IOException {
        UnifiedHighlighter highlighter = textQuery == null ? null : highlighter();
        List<SearchHit> hits = new ArrayList<>();
        int budget = properties.responseMaxChars();
        boolean truncated = false;
        int end = Math.min(ranked.size(), skip + pageSize);
        for (int i = skip; i < end; i++) {
            ScoreDoc scoreDoc = ranked.get(i);
            Document doc = searcher.storedFields().document(scoreDoc.doc);
            String body = doc.get(IndexFields.STORED_TEXT);
            String snippet = snippet(highlighter, textQuery, body);
            if (!hits.isEmpty() && snippet.length() > budget) {
                truncated = true;
                break;
            }
            budget -= snippet.length();
            hits.add(hit(doc, textQuery == null ? 0f : scoreDoc.score, snippet, null));
        }
        int next = skip + hits.size();
        return new SearchPage(total, List.copyOf(hits), next < available ? next : null, truncated, mode);
    }

    /** One record by its id, with the full passage cut to the response size cap. */
    public Optional<SearchHit> get(String collection, String id) throws IOException {
        List<Manifest> manifests = resolve(List.of(collection));
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        try (Opened opened = open(manifests)) {
            IndexSearcher searcher = new IndexSearcher(opened.reader);
            TopDocs top = searcher.search(new TermQuery(new Term(IndexFields.ID, id.strip())), 1);
            if (top.scoreDocs.length == 0) {
                return Optional.empty();
            }
            Document doc = searcher.storedFields().document(top.scoreDocs[0].doc);
            String body = doc.get(IndexFields.STORED_TEXT);
            int cap = properties.responseMaxChars();
            return Optional.of(hit(doc, 0f, null, body.length() > cap ? body.substring(0, cap) + "…" : body));
        }
    }

    /**
     * Record ids of one collection that start with a prefix, in id order: at most {@code max} of
     * them, and whether more exist. For completing an id (docs/decisions.md D48).
     */
    public IdPage ids(String collection, String prefix, int max) throws IOException {
        List<Manifest> manifests = resolve(List.of(collection));
        BytesRef start = new BytesRef(prefix == null ? "" : prefix);
        List<String> ids = new ArrayList<>();
        boolean more = false;
        try (Opened opened = open(manifests)) {
            Terms terms = MultiTerms.getTerms(opened.reader, IndexFields.ID);
            if (terms == null) {
                return new IdPage(List.of(), false);
            }
            TermsEnum each = terms.iterator();
            if (each.seekCeil(start) == TermsEnum.SeekStatus.END) {
                return new IdPage(List.of(), false);
            }
            for (BytesRef term = each.term(); term != null && startsWith(term, start);
                 term = each.next()) {
                if (ids.size() == max) {
                    more = true;
                    break;
                }
                ids.add(term.utf8ToString());
            }
        }
        return new IdPage(List.copyOf(ids), more);
    }

    private static boolean startsWith(BytesRef term, BytesRef prefix) {
        return term.length >= prefix.length && java.util.Arrays.equals(term.bytes, term.offset,
                term.offset + prefix.length, prefix.bytes, prefix.offset, prefix.offset + prefix.length);
    }

    /** Ids found by {@link #ids}, and whether the limit left some out. */
    public record IdPage(List<String> ids, boolean more) {
    }

    /**
     * Every record of the collections, without text or snippet, in index order. For measuring
     * search (which records a question set names), not for a tool: it is not bounded.
     */
    public List<SearchHit> all(List<String> collections) throws IOException {
        List<Manifest> manifests = resolve(collections);
        try (Opened opened = open(manifests)) {
            var stored = opened.reader.storedFields();
            var live = org.apache.lucene.index.MultiBits.getLiveDocs(opened.reader);
            List<SearchHit> hits = new ArrayList<>();
            for (int doc = 0; doc < opened.reader.maxDoc(); doc++) {
                if (live == null || live.get(doc)) {
                    hits.add(hit(stored.document(doc), 0f, null, null));
                }
            }
            return hits;
        }
    }

    /** Names of the collections whose manifest records vectors. */
    public List<String> withVectors(List<String> collections) {
        return resolve(collections).stream().filter(m -> m.vectors() != null).map(Manifest::name).toList();
    }

    List<Manifest> resolve(List<String> collections) {
        if (collections == null || collections.isEmpty()) {
            throw new IllegalArgumentException("Name at least one collection. Available: " + available());
        }
        List<Manifest> manifests = new ArrayList<>();
        for (String name : new LinkedHashSet<>(collections)) {
            manifests.add(store.manifest(name == null ? "" : name.strip()).orElseThrow(() ->
                    new IllegalArgumentException("Unknown collection '" + name + "'. Available: " + available())));
        }
        return manifests;
    }

    private String available() {
        List<Manifest> built = store.built();
        return built.isEmpty() ? "none (no collection is built)"
                : built.stream().map(Manifest::name).collect(Collectors.joining(", "));
    }

    private Query parse(String text) {
        var parser = new SimpleQueryParser(analyzer, WEIGHTS, SimpleQueryParser.PHRASE_OPERATOR);
        parser.setDefaultOperator(BooleanClause.Occur.SHOULD);
        Query parsed = parser.parse(text);
        return parsed == null ? new MatchNoDocsQuery() : parsed;
    }

    private UnifiedHighlighter highlighter() {
        return UnifiedHighlighter.builderWithoutSearcher(analyzer)
                .withFormatter(new DefaultPassageFormatter("**", "**", "… ", false))
                .withMaskedFieldsFunc(field -> IndexFields.TEXT.equals(field) ? Set.of(IndexFields.TEXT_EN) : Set.of())
                .withMaxNoHighlightPassages(1)
                .withWeightMatches(false)
                .withHighlightPhrasesStrictly(true)
                .withMaxLength(Integer.MAX_VALUE - 1)
                .build();
    }

    static String snippet(UnifiedHighlighter highlighter, Query query, String body) throws IOException {
        // Bold markers of the source would be indistinguishable from the highlighting.
        body = body == null ? null : body.replace("**", "");
        String snippet = null;
        if (highlighter != null && body != null && !body.isBlank()) {
            snippet = (String) highlighter.highlightWithoutSearcher(IndexFields.TEXT, query, body, 2);
        }
        if (snippet == null) {
            snippet = body == null ? "" : body;
        }
        // A passage cut at a sentence boundary may start with the previous sentence's full stop.
        snippet = snippet.strip().replaceFirst("^[.!?…]+\s*", "");
        if (snippet.length() <= SNIPPET_MAX_CHARS) {
            return snippet;
        }
        String cut = snippet.substring(0, SNIPPET_MAX_CHARS);
        // An odd number of markers means the cut fell inside a highlighted word: close it.
        int markers = (cut.length() - cut.replace("**", "").length()) / 2;
        return cut + (markers % 2 == 1 ? "**" : "") + "…";
    }

    static SearchHit hit(Document doc, float score, String snippet, String text) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (IndexableField field : doc.getFields()) {
            if (field.name().startsWith(IndexFields.VALUE_PREFIX)) {
                fields.computeIfAbsent(field.name().substring(IndexFields.VALUE_PREFIX.length()),
                        k -> new ArrayList<>()).add(field.stringValue());
            }
        }
        Number ordinal = doc.getField(IndexFields.STORED_ORDINAL).numericValue();
        return new SearchHit(doc.get(IndexFields.COLLECTION), doc.get(IndexFields.ID),
                doc.get(IndexFields.DOCUMENT), doc.get(IndexFields.STORED_TITLE),
                doc.get(IndexFields.STORED_BREADCRUMB), doc.get(IndexFields.STORED_SOURCE),
                ordinal.intValue(), score, snippet, text, fields);
    }

    Opened open(List<Manifest> manifests) throws IOException {
        Opened opened = new Opened();
        try {
            List<IndexReader> readers = new ArrayList<>();
            for (Manifest manifest : manifests) {
                Directory directory = FSDirectory.open(store.indexDir(manifest.name()));
                opened.resources.add(directory);
                DirectoryReader reader = DirectoryReader.open(directory);
                opened.resources.add(reader);
                opened.bases.add(readers.stream().mapToInt(IndexReader::maxDoc).sum());
                readers.add(reader);
            }
            opened.readers = List.copyOf(readers);
            opened.reader = readers.size() == 1 ? readers.getFirst()
                    : new MultiReader(readers.toArray(IndexReader[]::new), false);
            return opened;
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e;
        }
    }

    /** Readers and directories of one call, closed in reverse order of opening. */
    static final class Opened implements Closeable {
        final List<Closeable> resources = new ArrayList<>();
        /** The index of each collection, in the order of the call. */
        List<IndexReader> readers;
        /** The first doc id of each collection's index in {@link #reader}. */
        final List<Integer> bases = new ArrayList<>();
        /** All searched collections as one index. */
        IndexReader reader;

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int i = resources.size() - 1; i >= 0; i--) {
                try {
                    resources.get(i).close();
                } catch (IOException e) {
                    failure = failure == null ? e : failure;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
