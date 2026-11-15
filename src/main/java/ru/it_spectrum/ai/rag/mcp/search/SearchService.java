package ru.it_spectrum.ai.rag.mcp.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.index.Term;
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
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.embedding.EmbeddingModels;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelParameters;
import ru.it_spectrum.ai.rag.mcp.embedding.ModelUnavailableException;
import ru.it_spectrum.ai.rag.mcp.embedding.VectorModel;
import ru.it_spectrum.ai.rag.mcp.index.Analyzers;
import ru.it_spectrum.ai.rag.mcp.index.CollectionIndexer;
import ru.it_spectrum.ai.rag.mcp.index.CollectionStore;
import ru.it_spectrum.ai.rag.mcp.index.IndexFields;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
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
 *   <li>Filters are exact matches on document fields, case-insensitive; every filter must hold.</li>
 *   <li>Several collections are searched as one index, with shared term statistics.</li>
 *   <li>The number of results and the total size of the returned text are capped by the server
 *       settings whatever a call asks for.</li>
 * </ul>
 */
public final class SearchService {

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

    private final CollectionStore store;
    private final RagMcpProperties properties;
    private final EmbeddingModels models;
    private final Analyzer analyzer = Analyzers.analyzer();

    /** A search without models: lexical only. */
    public SearchService(CollectionStore store, RagMcpProperties properties) {
        this(store, properties, null);
    }

    /**
     * @param models the embedding models queries of vector and hybrid search are embedded with;
     *               {@code null} allows lexical search only
     */
    public SearchService(CollectionStore store, RagMcpProperties properties, EmbeddingModels models) {
        this.store = store;
        this.properties = properties;
        this.models = models;
    }

    /** A search in the default mode. */
    public SearchPage search(List<String> collections, String query, Map<String, String> filters,
                             Integer limit, Integer offset) throws IOException {
        return search(collections, query, filters, limit, offset, null);
    }

    /**
     * @param collections names of built collections; at least one
     * @param query       words to look for; blank lists the records matching the filters
     * @param filters     field name to required value; may be {@code null}
     * @param limit       page size; {@code null} for the server default, capped by the server maximum
     * @param offset      results to skip; {@code null} for none
     * @param mode        {@code lexical}, {@code vector} or {@code hybrid}; blank for hybrid when a
     *                    searched collection has vectors and lexical otherwise
     * @throws IllegalArgumentException  for an unknown collection, an unknown filter field or a bad argument
     * @throws ModelUnavailableException when the model of a collection is needed and cannot be used
     */
    public SearchPage search(List<String> collections, String query, Map<String, String> filters,
                             Integer limit, Integer offset, String mode) throws IOException {
        SearchMode requested = SearchMode.parse(mode);
        List<Manifest> manifests = resolve(collections);
        String text = query == null ? "" : query.strip();
        if (text.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("query is longer than " + MAX_QUERY_CHARS + " characters");
        }
        Query textQuery = text.isEmpty() ? null : parse(text);
        SearchMode effective = effectiveMode(requested, manifests, textQuery != null);
        var builder = new BooleanQuery.Builder();
        builder.add(textQuery == null ? new MatchAllDocsQuery() : textQuery, BooleanClause.Occur.MUST);
        var filterBuilder = new BooleanQuery.Builder();
        boolean filtered = false;
        if (filters != null) {
            Set<String> known = manifests.stream().flatMap(m -> m.fields().stream()).collect(Collectors.toSet());
            for (var filter : filters.entrySet()) {
                if (filter.getValue() == null || filter.getValue().isBlank()) {
                    continue;
                }
                if (!known.contains(filter.getKey())) {
                    throw new IllegalArgumentException("Unknown filter field '" + filter.getKey()
                            + "'. Fields of the searched collections: " + String.join(", ", new java.util.TreeSet<>(known)));
                }
                Query term = new TermQuery(new Term(IndexFields.FILTER_PREFIX + filter.getKey(),
                        CollectionIndexer.filterTerm(filter.getValue())));
                builder.add(term, BooleanClause.Occur.FILTER);
                filterBuilder.add(term, BooleanClause.Occur.FILTER);
                filtered = true;
            }
        }
        if (effective == SearchMode.LEXICAL) {
            return page(manifests, builder.build(), textQuery, limit, offset, SearchMode.LEXICAL);
        }
        return fused(manifests, effective, text, builder.build(), filtered ? filterBuilder.build() : null,
                textQuery, limit, offset);
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
                             Query textQuery, Integer limit, Integer offset) throws IOException {
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
            return collect(searcher, top, top.scoreDocs.length, textQuery, skip, pageSize, mode);
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
        Query query = new TermQuery(new Term(IndexFields.LOOKUP, CollectionIndexer.filterTerm(value)));
        return page(manifests, query, null, limit, offset, null);
    }

    private String withKeys() {
        List<String> names = store.built().stream().filter(m -> !m.keys().isEmpty()).map(Manifest::name).toList();
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    /** One page of the records a query matches, with snippets of the words of {@code textQuery}. */
    private SearchPage page(List<Manifest> manifests, Query full, Query textQuery, Integer limit, Integer offset,
                            SearchMode mode) throws IOException {
        int pageSize = pageSize(limit);
        int skip = offset == null ? 0 : Math.max(0, offset);
        try (Opened opened = open(manifests)) {
            IndexSearcher searcher = new IndexSearcher(opened.reader);
            int total = searcher.count(full);
            TopDocs top = searcher.search(full, Math.max(1, skip + pageSize));
            return collect(searcher, top, total, textQuery, skip, pageSize, mode);
        }
    }

    private int pageSize(Integer limit) {
        return limit == null ? properties.searchDefaultLimit()
                : Math.min(properties.searchMaxLimit(), Math.max(1, limit));
    }

    /** The page of ranked records from {@code skip} on, with snippets, within the response size cap. */
    private SearchPage collect(IndexSearcher searcher, TopDocs top, int total, Query textQuery, int skip,
                               int pageSize, SearchMode mode) throws IOException {
        UnifiedHighlighter highlighter = textQuery == null ? null : highlighter();
        List<SearchHit> hits = new ArrayList<>();
        int budget = properties.responseMaxChars();
        boolean truncated = false;
        int end = Math.min(top.scoreDocs.length, skip + pageSize);
        for (int i = skip; i < end; i++) {
            ScoreDoc scoreDoc = top.scoreDocs[i];
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
        return new SearchPage(total, List.copyOf(hits), next < total ? next : null, truncated, mode);
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

    private List<Manifest> resolve(List<String> collections) {
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

    private static String snippet(UnifiedHighlighter highlighter, Query query, String body) throws IOException {
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

    private static SearchHit hit(Document doc, float score, String snippet, String text) {
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

    private Opened open(List<Manifest> manifests) throws IOException {
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
    private static final class Opened implements Closeable {
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
