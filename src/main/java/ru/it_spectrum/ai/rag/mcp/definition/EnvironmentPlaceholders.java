package ru.it_spectrum.ai.rag.mcp.definition;

import java.util.function.UnaryOperator;

/**
 * Resolves {@code ${ENV_VAR}} placeholders inside {@code collections.json} string values, so a
 * path can differ between machines without editing the file. A missing variable is a hard error
 * naming both the variable and the field.
 *
 * <p>Copied from mail-mcp-server {@code connections/EnvironmentPlaceholders} @ {@code 0.1.1}; the
 * lookup here is the {@code vars} of the collections file, then the environment, and the message
 * says so.
 */
public final class EnvironmentPlaceholders {

    private EnvironmentPlaceholders() {
    }

    /**
     * @param value  raw string from the file, may be {@code null}
     * @param field  human-readable location used in the error message
     * @param lookup environment lookup, normally {@code System::getenv}
     * @throws IllegalStateException when a referenced variable is not set or the placeholder is unterminated
     */
    public static String resolve(String value, String field, UnaryOperator<String> lookup) {
        if (value == null || value.indexOf("${") < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) {
                out.append(value, i, value.length());
                break;
            }
            out.append(value, i, start);
            int end = value.indexOf('}', start + 2);
            if (end < 0) {
                throw new IllegalStateException("Unterminated ${...} placeholder in " + field);
            }
            String name = value.substring(start + 2, end).trim();
            if (name.isEmpty()) {
                throw new IllegalStateException("Empty ${} placeholder in " + field);
            }
            String resolved = lookup.apply(name);
            if (resolved == null) {
                throw new IllegalStateException("Variable '" + name + "' referenced by "
                        + field + " is not set, neither in \"vars\" nor in the environment");
            }
            out.append(resolved);
            i = end + 1;
        }
        return out.toString();
    }
}
