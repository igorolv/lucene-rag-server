package ru.it_spectrum.ai.rag.mcp.index;

/**
 * Names of the Lucene fields of one record. Changing the set or the analysis of a field means
 * raising {@link Manifest#SCHEMA}: an index of another schema is not opened.
 */
public final class IndexFields {

    /** Exact record id, indexed and stored. */
    public static final String ID = "id";
    /** Key of the document the record belongs to, indexed and stored. */
    public static final String DOCUMENT = "document";
    /** Name of the collection, stored: tells records apart when several collections are searched. */
    public static final String COLLECTION = "collection";

    /** Passage, Russian analysis; not stored. */
    public static final String TEXT = "text";
    /** Passage, English analysis; not stored. */
    public static final String TEXT_EN = "text_en";
    /** Breadcrumb (document title and headings), Russian analysis; not stored. */
    public static final String TITLE = "title";
    /** Breadcrumb, English analysis; not stored. */
    public static final String TITLE_EN = "title_en";

    public static final String STORED_TEXT = "s.text";
    public static final String STORED_TITLE = "s.title";
    public static final String STORED_BREADCRUMB = "s.breadcrumb";
    public static final String STORED_SOURCE = "s.source";
    public static final String STORED_ORDINAL = "s.ordinal";

    /** Prefix of a document field indexed as one lower-cased exact term per value. */
    public static final String FILTER_PREFIX = "f.";
    /** Every value of the key fields of the record ({@code map.keys}) as one lower-cased exact term. */
    public static final String LOOKUP = "k";
    /** Prefix of the stored original values of a document field. */
    public static final String VALUE_PREFIX = "v.";

    private IndexFields() {
    }
}
