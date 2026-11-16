package ru.it_spectrum.ai.rag.mcp.definition;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for an index name. The name is also a directory under the data directory and the
 * value tools take as {@code collection}, so it is restricted to characters that are safe in a
 * path on every platform. The optional {@code @} separates the part from the project:
 * {@code docs@billing}. Same rules as connection names in jdbc-mcp-server.
 */
public final class IndexNames {

    public static final int MAX_LENGTH = 64;

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?");

    /** Directories the server keeps next to the collections. */
    private static final Set<String> RESERVED = Set.of("logs", "models", "cache");

    private IndexNames() {
    }

    /** @return {@code null} when the name is valid, otherwise what is wrong with it */
    public static String problem(String name) {
        if (name == null || name.isEmpty()) {
            return "the name is empty";
        }
        if (name.length() > MAX_LENGTH) {
            return "the name is longer than " + MAX_LENGTH + " characters";
        }
        if (name.startsWith(".")) {
            return "the name starts with a dot";
        }
        if (!VALID.matcher(name).matches()) {
            return "the name may contain only letters, digits, '.', '_', '-' and one '@'";
        }
        if (RESERVED.contains(name.toLowerCase())) {
            return "the name is reserved for a server directory";
        }
        return null;
    }

    public static boolean valid(String name) {
        return problem(name) == null;
    }
}
