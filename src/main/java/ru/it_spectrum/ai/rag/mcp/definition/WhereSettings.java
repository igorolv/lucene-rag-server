package ru.it_spectrum.ai.rag.mcp.definition;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which records of a source are kept: the {@code where} block, checked against the fields of a
 * record after {@code map}. Every condition must hold. A value is compared as a filter compares
 * it: without surrounding blanks and ignoring case.
 *
 * @param conditions the conditions in the order written
 */
public record WhereSettings(List<Condition> conditions) {

    public static final WhereSettings ALL = new WhereSettings(List.of());

    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    public WhereSettings {
        conditions = List.copyOf(conditions);
    }

    /**
     * One condition on one field.
     *
     * @param field  the field name
     * @param value  the value compared
     * @param not    {@code false}: the field has the value; {@code true}: the field does not have
     *               it, which a record without the field satisfies
     */
    public record Condition(String field, String value, boolean not) {

        public Condition {
            if (!FIELD_NAME.matcher(field).matches()) {
                throw new IllegalArgumentException("where: \"" + field + "\" is not a field name");
            }
        }

        boolean matches(Map<String, List<String>> fields) {
            List<String> values = fields.getOrDefault(field, List.of());
            boolean has = values.stream().anyMatch(v -> normalized(v).equals(normalized(value)));
            return has != not;
        }
    }

    /** @param fields the fields of a record, name to values */
    public boolean matches(Map<String, List<String>> fields) {
        for (Condition condition : conditions) {
            if (!condition.matches(fields)) {
                return false;
            }
        }
        return true;
    }

    private static String normalized(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
