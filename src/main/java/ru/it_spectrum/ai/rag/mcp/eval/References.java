package ru.it_spectrum.ai.rag.mcp.eval;

import ru.it_spectrum.ai.rag.mcp.search.SearchHit;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which records a reference of a question set names. A question set is written for its corpus,
 * not for this server, so no layout of references is fixed in code (docs/decisions.md D14); the
 * prefixes a set uses are mapped to fields by the operator.
 *
 * <p>A reference matches a record when it is
 * <ul>
 *   <li>the record's document key or id;</li>
 *   <li>the record's source path, or a path ending in {@code /} and that path (a reference written
 *       from the root of the corpus, a source path relative to the source);</li>
 *   <li>{@code <prefix>:<value>} and the field the prefix is mapped to, or the field named as the
 *       prefix, has the value, compared as filters compare: without surrounding blanks and
 *       ignoring case.</li>
 * </ul>
 */
public final class References {

    private final Map<String, String> fields;

    /** @param fields field names by reference prefix: {@code data} to {@code table} */
    public References(Map<String, String> fields) {
        this.fields = Map.copyOf(fields);
    }

    public boolean matches(String ref, SearchHit hit) {
        if (ref.equals(hit.document()) || ref.equals(hit.id())) {
            return true;
        }
        String source = hit.source();
        if (source != null && !source.isEmpty() && (ref.equals(source) || ref.endsWith("/" + source))) {
            return true;
        }
        if (hit.document() != null && ref.endsWith("/" + hit.document())) {
            return true;
        }
        int colon = ref.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String prefix = ref.substring(0, colon);
        String value = normalized(ref.substring(colon + 1));
        List<String> values = hit.fields().get(fields.getOrDefault(prefix, prefix));
        return values != null && values.stream().anyMatch(v -> normalized(v).equals(value));
    }

    /** Whether the record matches one of the references. */
    public boolean matchesAny(List<String> refs, SearchHit hit) {
        return refs.stream().anyMatch(ref -> matches(ref, hit));
    }

    /** The prefix of a reference, or an empty string. */
    public static String prefix(String ref) {
        int colon = ref.indexOf(':');
        return colon <= 0 ? "" : ref.substring(0, colon);
    }

    private static String normalized(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
