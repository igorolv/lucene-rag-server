package ru.it_spectrum.ai.rag.mcp.search;

/**
 * The id of a record as an assistant sees it: {@code <index>/<record id>}, for example
 * {@code docs@billing/article.billing.refunds#statuses} (docs/decisions.md D84). Inside an index a
 * record keeps its own id; the address is made and taken apart only where search meets a tool or
 * a command. An index name holds no {@code /}, so the address is split at the first one.
 *
 * @param index the name of the index
 * @param id    the id of the record inside it
 */
public record RecordAddress(String index, String id) {

    /** What an address looks like, for messages. */
    public static final String EXAMPLE = "docs@billing/article.billing.refunds#statuses";

    public static RecordAddress of(SearchHit hit) {
        return new RecordAddress(hit.index(), hit.id());
    }

    /**
     * @throws IllegalArgumentException when the value is blank or has no index part
     */
    public static RecordAddress parse(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("id is required: give it as a search hit gives it, "
                    + "<index>/<record id>, for example " + EXAMPLE);
        }
        int slash = text.indexOf('/');
        if (slash <= 0 || slash == text.length() - 1) {
            throw new IllegalArgumentException("The id '" + text + "' is not one a search hit gives: an id is "
                    + "<index>/<record id>, for example " + EXAMPLE);
        }
        return new RecordAddress(text.substring(0, slash), text.substring(slash + 1));
    }

    /** The address as text, {@code <index>/<record id>}. */
    public String text() {
        return index + "/" + id;
    }

    @Override
    public String toString() {
        return text();
    }
}
