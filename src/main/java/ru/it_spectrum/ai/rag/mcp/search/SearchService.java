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
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.Analyzers;
import ru.it_spectrum.ai.rag.mcp.index.CollectionIndexer;
import ru.it_spectrum.ai.rag.mcp.index.CollectionStore;
import ru.it_spectrum.ai.rag.mcp.index.IndexFields;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lexical search over built collections.
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

    private final CollectionStore store;
    private final RagMcpProperties properties;
    private final Analyzer analyzer = Analyzers.analyzer();

    public SearchService(CollectionStore store, RagMcpProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * @param collections names of built collections; at least one
     * @param query       words to look for; blank lists the records matching the filters
     * @param filters     field name to required value; may be {@code null}
     * @param limit       page size; {@code null} for the server default, capped by the server maximum
     * @param offset      results to skip; {@code null} for none
     * @throws IllegalArgumentException for an unknown collection, an unknown filter field or a bad argument
     */
    public SearchPage search(List<String> collections, String query, Map<String, String> filters,
                             Integer limit, Integer offset) throws IOException {
        List<Manifest> manifests = resolve(collections);
        String text = query == null ? "" : query.strip();
        if (text.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("query is longer than " + MAX_QUERY_CHARS + " characters");
        }
        int pageSize = limit == null ? properties.searchDefaultLimit()
                : Math.min(properties.searchMaxLimit(), Math.max(1, limit));
        int skip = offset == null ? 0 : Math.max(0, offset);

        Query textQuery = text.isEmpty() ? null : parse(text);
        var builder = new BooleanQuery.Builder();
        builder.add(textQuery == null ? new MatchAllDocsQuery() : textQuery, BooleanClause.Occur.MUST);
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
                builder.add(new TermQuery(new Term(IndexFields.FILTER_PREFIX + filter.getKey(),
                        CollectionIndexer.filterTerm(filter.getValue()))), BooleanClause.Occur.FILTER);
            }
        }
        Query full = builder.build();

        try (Opened opened = open(manifests)) {
            IndexSearcher searcher = new IndexSearcher(opened.reader);
            int total = searcher.count(full);
            TopDocs top = searcher.search(full, Math.max(1, skip + pageSize));
            UnifiedHighlighter highlighter = textQuery == null ? null : highlighter();
            List<SearchHit> hits = new ArrayList<>();
            int budget = properties.responseMaxChars();
            boolean truncated = false;
            for (int i = skip; i < top.scoreDocs.length; i++) {
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
            return new SearchPage(total, List.copyOf(hits), next < total ? next : null, truncated);
        }
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
                readers.add(reader);
            }
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
