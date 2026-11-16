package ru.it_spectrum.ai.rag.mcp.search;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.BytesRef;
import ru.it_spectrum.ai.rag.mcp.config.RagMcpProperties;
import ru.it_spectrum.ai.rag.mcp.index.Analyzers;
import ru.it_spectrum.ai.rag.mcp.index.IndexBuilder;
import ru.it_spectrum.ai.rag.mcp.index.IndexFields;
import ru.it_spectrum.ai.rag.mcp.index.Manifest;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Looking around an index without a query (docs/decisions.md D72): the values of a field with
 * the number of records of each, and the records related to one record — the other sections of its
 * document and the records most like it.
 */
public final class BrowseService {

    /** The most values one call returns. */
    public static final int MAX_VALUES = 200;

    /** Values a call returns when it does not say. */
    public static final int DEFAULT_VALUES = 50;

    private final SearchService search;
    private final RagMcpProperties properties;

    public BrowseService(SearchService search, RagMcpProperties properties) {
        this.search = search;
        this.properties = properties;
    }

    /**
     * One value of a field.
     *
     * @param value as a record has it; filters compare it ignoring case
     * @param count records with the value
     */
    public record Value(String value, int count) {
    }

    /**
     * Values of a field, most frequent first.
     *
     * @param distinct values of the field in the counted records, also those not returned
     * @param values   the values returned
     */
    public record FieldValues(String field, int distinct, List<Value> values) {
    }

    /**
     * Records related to one record.
     *
     * @param record    the record, without text
     * @param sections  other records of its document, nearest by position first; empty for a row,
     *                  which stands alone
     * @param similar   records most like it outside its document, best first, with the start of
     *                  their text as the snippet
     * @param similarBy {@code vector} when compared by the stored vectors, {@code words} when by the
     *                  words of the text, for an index without vectors
     */
    public record Related(SearchHit record, List<SearchHit> sections, List<SearchHit> similar, String similarBy) {
    }

    /**
     * The values of a field over the records of the indexes that pass the filters (docs/decisions.md
     * D87). A value is counted from the same terms a filter matches, so a value passed in
     * {@code filters} finds exactly the records counted for it.
     *
     * @param indexes          names of built indexes, or {@code null} for every built index
     * @param filters          exact field values, as for search; {@code null} for none
     * @param filterExpression a filter expression, as for search; {@code null} for none
     * @param prefix           only values starting with it, ignoring case; {@code null} for all
     * @param limit            values to return; {@value #DEFAULT_VALUES} when {@code null}, at most
     *                         {@value #MAX_VALUES}
     * @throws IllegalArgumentException for an unknown index, a field no index has or a bad filter
     */
    public FieldValues fieldValues(List<String> indexes, String field, Map<String, String> filters,
                                   String filterExpression, String prefix, Integer limit) throws IOException {
        List<Manifest> manifests = search.resolve(indexes);
        TreeSet<String> known = new TreeSet<>();
        manifests.forEach(m -> known.addAll(m.fields()));
        if (field == null || !known.contains(field.strip())) {
            throw new IllegalArgumentException("No index has the field '" + field + "'; fields: "
                    + String.join(", ", known));
        }
        String name = field.strip();
        String start = prefix == null ? "" : IndexBuilder.filterTerm(prefix);
        int size = limit == null ? DEFAULT_VALUES : Math.min(MAX_VALUES, Math.max(1, limit));
        Query filter = SearchService.filter(manifests, filters, filterExpression);
        try (SearchService.Opened opened = search.open(manifests)) {
            IndexSearcher searcher = new IndexSearcher(opened.reader);
            Map<String, Integer> counts = count(searcher, filter == null ? new MatchAllDocsQuery() : filter,
                    IndexFields.FILTER_PREFIX + name);
            List<Value> all = new ArrayList<>();
            for (var entry : counts.entrySet()) {
                if (entry.getKey().startsWith(start)) {
                    all.add(new Value(entry.getKey(), entry.getValue()));
                }
            }
            all.sort(Comparator.comparingInt(Value::count).reversed().thenComparing(Value::value));
            List<Value> shown = new ArrayList<>();
            for (Value value : all.subList(0, Math.min(size, all.size()))) {
                shown.add(new Value(original(searcher, name, value.value()), value.count()));
            }
            return new FieldValues(name, all.size(), List.copyOf(shown));
        }
    }

    /** Records per term of a field among the documents a query matches, from the doc values of the field. */
    private static Map<String, Integer> count(IndexSearcher searcher, Query query, String field) throws IOException {
        Weight weight = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1);
        Map<String, Integer> counts = new HashMap<>();
        for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
            SortedSetDocValues values = leaf.reader().getSortedSetDocValues(field);
            Scorer scorer = values == null ? null : weight.scorer(leaf);
            if (scorer == null) {
                continue;
            }
            Map<Long, Integer> byOrd = new HashMap<>();
            DocIdSetIterator docs = scorer.iterator();
            var live = leaf.reader().getLiveDocs();
            for (int doc = docs.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = docs.nextDoc()) {
                if ((live == null || live.get(doc)) && values.advanceExact(doc)) {
                    for (int i = 0; i < values.docValueCount(); i++) {
                        byOrd.merge(values.nextOrd(), 1, Integer::sum);
                    }
                }
            }
            for (var entry : byOrd.entrySet()) {
                counts.merge(values.lookupOrd(entry.getKey()).utf8ToString(), entry.getValue(), Integer::sum);
            }
        }
        return counts;
    }

    /** A value as a record that has it writes it, rather than lower-cased as it is indexed. */
    private static String original(IndexSearcher searcher, String field, String term) throws IOException {
        TopDocs top = searcher.search(new TermQuery(new Term(IndexFields.FILTER_PREFIX + field, term)), 1);
        if (top.scoreDocs.length == 0) {
            return term;
        }
        Document doc = searcher.storedFields().document(top.scoreDocs[0].doc);
        for (String value : doc.getValues(IndexFields.VALUE_PREFIX + field)) {
            if (IndexBuilder.filterTerm(value).equals(term)) {
                return value.strip();
            }
        }
        return term;
    }

    /**
     * Sections and similar records stay inside the index of the record: vectors of different
     * models are not comparable.
     *
     * @param address the record, {@code <index>/<record id>}
     * @param limit   sections and similar records to return, each; capped as a page of search is
     * @throws IllegalArgumentException for a wrong id, an unknown index or record
     */
    public Related related(String address, Integer limit) throws IOException {
        RecordAddress parsed = RecordAddress.parse(address);
        Manifest manifest = search.resolve(List.of(search.builtIndex(parsed))).getFirst();
        String id = parsed.id();
        int size = limit == null ? Math.min(5, properties.searchMaxLimit())
                : Math.min(properties.searchMaxLimit(), Math.max(1, limit));
        try (SearchService.Opened opened = search.open(List.of(manifest))) {
            IndexSearcher searcher = new IndexSearcher(opened.reader);
            var stored = searcher.storedFields();
            TopDocs found = searcher.search(new TermQuery(new Term(IndexFields.ID, id.strip())), 1);
            if (found.scoreDocs.length == 0) {
                throw new IllegalArgumentException("No record '" + id.strip() + "' in index '"
                        + manifest.name() + "'");
            }
            int self = found.scoreDocs[0].doc;
            Document record = stored.document(self);
            String documentKey = record.get(IndexFields.DOCUMENT);
            // A section shares its group with its document; a row is a group of its own (D38).
            boolean section = documentKey != null && documentKey.equals(record.get(IndexFields.STORED_GROUP));
            List<SearchHit> sections = section ? sections(searcher, documentKey, self, size) : List.of();

            Query exclude = section ? new TermQuery(new Term(IndexFields.DOCUMENT, documentKey))
                    : new TermQuery(new Term(IndexFields.ID, id.strip()));
            Query others = new BooleanQuery.Builder().add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                    .add(exclude, BooleanClause.Occur.MUST_NOT).build();
            float[] vector = manifest.vectors() == null ? null : vector(opened, self);
            final TopDocs top;
            final String by;
            if (vector != null) {
                top = searcher.search(new KnnFloatVectorQuery(IndexFields.VECTOR, vector, size, others), size);
                by = "vector";
            } else {
                MoreLikeThis like = new MoreLikeThis(opened.reader);
                like.setAnalyzer(Analyzers.analyzer());
                like.setFieldNames(new String[]{IndexFields.TEXT, IndexFields.TITLE});
                like.setMinTermFreq(1);
                like.setMinDocFreq(1);
                String text = record.get(IndexFields.STORED_BREADCRUMB) + "\n" + record.get(IndexFields.STORED_TEXT);
                Query words = like.like(IndexFields.TEXT, new StringReader(text));
                top = searcher.search(new BooleanQuery.Builder().add(words, BooleanClause.Occur.MUST)
                        .add(exclude, BooleanClause.Occur.MUST_NOT).build(), size);
                by = "words";
            }
            List<SearchHit> similar = new ArrayList<>();
            for (ScoreDoc scoreDoc : top.scoreDocs) {
                Document doc = stored.document(scoreDoc.doc);
                similar.add(SearchService.hit(doc, scoreDoc.score,
                        SearchService.snippet(null, null, doc.get(IndexFields.STORED_TEXT)), null));
            }
            return new Related(SearchService.hit(record, 0f, null, null), sections, List.copyOf(similar), by);
        }
    }

    /** The other records of a document, nearest to the record by position first, then in order. */
    private static List<SearchHit> sections(IndexSearcher searcher, String documentKey, int self, int size)
            throws IOException {
        var stored = searcher.storedFields();
        TopDocs all = searcher.search(new TermQuery(new Term(IndexFields.DOCUMENT, documentKey)), 10_000);
        int position = stored.document(self).getField(IndexFields.STORED_ORDINAL).numericValue().intValue();
        List<SearchHit> sections = new ArrayList<>();
        for (ScoreDoc scoreDoc : all.scoreDocs) {
            if (scoreDoc.doc != self) {
                sections.add(SearchService.hit(stored.document(scoreDoc.doc), 0f, null, null));
            }
        }
        sections.sort(Comparator.comparingInt((SearchHit h) -> Math.abs(h.ordinal() - position))
                .thenComparingInt(SearchHit::ordinal));
        List<SearchHit> nearest = new ArrayList<>(sections.subList(0, Math.min(size, sections.size())));
        nearest.sort(Comparator.comparingInt(SearchHit::ordinal));
        return List.copyOf(nearest);
    }

    /** The stored vector of a record, or {@code null} when it has none. */
    private static float[] vector(SearchService.Opened opened, int doc) throws IOException {
        List<LeafReaderContext> leaves = opened.reader.leaves();
        LeafReaderContext leaf = leaves.get(ReaderUtil.subIndex(doc, leaves));
        LeafReader reader = leaf.reader();
        FloatVectorValues values = reader.getFloatVectorValues(IndexFields.VECTOR);
        if (values == null) {
            return null;
        }
        KnnVectorValues.DocIndexIterator iterator = values.iterator();
        if (iterator.advance(doc - leaf.docBase) != doc - leaf.docBase) {
            return null;
        }
        return values.vectorValue(iterator.index()).clone();
    }
}
