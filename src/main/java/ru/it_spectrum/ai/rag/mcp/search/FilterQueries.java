package ru.it_spectrum.ai.rag.mcp.search;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import ru.it_spectrum.ai.rag.mcp.index.CollectionIndexer;
import ru.it_spectrum.ai.rag.mcp.index.IndexFields;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Search filters as Spring AI filter expressions (docs/decisions.md D30), turned into a Lucene
 * query over the exact terms of the document fields.
 *
 * <p>A value is compared as the indexer stores it: as text, without surrounding blanks and
 * ignoring case; a number or a boolean is compared as the text it is written as. A field with
 * several values satisfies {@code ==} and {@code in} when one of them does; {@code !=},
 * {@code nin} and {@code not} are the negations, so a record without the field satisfies them,
 * as {@code not} of a {@code where} condition does. {@code <}, {@code <=}, {@code >} and
 * {@code >=} compare text, so they take quoted values only: {@code date >= '2026-01-01'}.
 */
public final class FilterQueries {

    /** The longest expression text accepted. */
    public static final int MAX_CHARS = 1_000;

    private final Set<String> known;

    /** @param known names of the fields of the searched collections */
    public FilterQueries(Set<String> known) {
        this.known = known;
    }

    /**
     * Parses the text syntax of Spring AI ({@code kind == 'faq' && tags in ['a', 'b']}).
     *
     * @throws IllegalArgumentException when the text is too long or not an expression
     */
    public static Filter.Expression parse(String text) {
        if (text.length() > MAX_CHARS) {
            throw new IllegalArgumentException("filterExpression is longer than " + MAX_CHARS + " characters");
        }
        try {
            // A new parser per call: a shared one keeps every text it has parsed.
            return new FilterExpressionTextParser().parse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("filterExpression is not a filter expression: " + e.getMessage()
                    + ". Example: kind == 'article' && tags in ['refunds', 'payments']", e);
        }
    }

    /** The equality conditions of a {@code filters} map joined by "and"; blank values are skipped. */
    public static Filter.Expression of(Map<String, String> filters) {
        Filter.Expression result = null;
        if (filters == null) {
            return null;
        }
        for (var filter : filters.entrySet()) {
            if (filter.getValue() == null || filter.getValue().isBlank()) {
                continue;
            }
            var equals = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key(filter.getKey()),
                    new Filter.Value(filter.getValue()));
            result = and(result, equals);
        }
        return result;
    }

    /** Both expressions, either of which may be {@code null}. */
    public static Filter.Expression and(Filter.Expression left, Filter.Expression right) {
        if (left == null) {
            return right;
        }
        return right == null ? left : new Filter.Expression(Filter.ExpressionType.AND, left, right);
    }

    /**
     * @throws IllegalArgumentException for an unknown field or an operand that does not fit its
     *                                  operator
     */
    public Query query(Filter.Expression expression) {
        return switch (expression.type()) {
            case AND -> combine(expression, BooleanClause.Occur.FILTER);
            case OR -> combine(expression, BooleanClause.Occur.SHOULD);
            case NOT -> not(operand(expression.left()));
            case EQ -> term(field(expression), single(expression));
            case NE -> not(term(field(expression), single(expression)));
            case IN -> anyOf(field(expression), list(expression));
            case NIN -> not(anyOf(field(expression), list(expression)));
            case ISNULL -> not(exists(field(expression)));
            case ISNOTNULL -> exists(field(expression));
            case GT -> range(expression, false, false);
            case GTE -> range(expression, false, true);
            case LT -> range(expression, true, false);
            case LTE -> range(expression, true, true);
        };
    }

    private Query operand(Filter.Operand operand) {
        return switch (operand) {
            case Filter.Expression expression -> query(expression);
            case Filter.Group group -> query(group.content());
            case null, default -> throw new IllegalArgumentException(
                    "filterExpression: expected a condition, found " + operand);
        };
    }

    private Query combine(Filter.Expression expression, BooleanClause.Occur occur) {
        var builder = new BooleanQuery.Builder();
        builder.add(operand(expression.left()), occur);
        builder.add(operand(expression.right()), occur);
        if (occur == BooleanClause.Occur.SHOULD) {
            builder.setMinimumNumberShouldMatch(1);
        }
        return builder.build();
    }

    private static Query not(Query query) {
        return new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
                .add(query, BooleanClause.Occur.MUST_NOT)
                .build();
    }

    private static Query term(String field, String value) {
        return new TermQuery(new Term(field, CollectionIndexer.filterTerm(value)));
    }

    private static Query anyOf(String field, List<String> values) {
        var builder = new BooleanQuery.Builder();
        for (String value : values) {
            builder.add(term(field, value), BooleanClause.Occur.SHOULD);
        }
        return builder.setMinimumNumberShouldMatch(1).build();
    }

    /** Any value of the field. */
    private static Query exists(String field) {
        return TermRangeQuery.newStringRange(field, null, null, true, true);
    }

    private Query range(Filter.Expression expression, boolean upper, boolean inclusive) {
        String field = field(expression);
        Object raw = value(expression);
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("filterExpression: " + key(expression) + " " + symbol(expression)
                    + " " + raw + " — values are compared as text, so write the value in quotes, of the same"
                    + " width as the field's values ('2026-01-01', '007')");
        }
        String bound = CollectionIndexer.filterTerm(text);
        return upper ? TermRangeQuery.newStringRange(field, null, bound, true, inclusive)
                : TermRangeQuery.newStringRange(field, bound, null, inclusive, true);
    }

    /** The index field of the key of a comparison, checked against the known fields. */
    private String field(Filter.Expression expression) {
        String name = key(expression);
        if (!known.contains(name)) {
            throw new IllegalArgumentException("Unknown filter field '" + name
                    + "'. Fields of the searched collections: " + String.join(", ", new TreeSet<>(known)));
        }
        return IndexFields.FILTER_PREFIX + name;
    }

    private static String key(Filter.Expression expression) {
        if (!(expression.left() instanceof Filter.Key key)) {
            throw new IllegalArgumentException("filterExpression: a comparison needs a field name on its left, found "
                    + expression.left());
        }
        String name = key.key().strip();
        if (name.length() >= 2 && (name.startsWith("'") && name.endsWith("'")
                || name.startsWith("\"") && name.endsWith("\""))) {
            name = name.substring(1, name.length() - 1);
        }
        return name;
    }

    private static Object value(Filter.Expression expression) {
        if (!(expression.right() instanceof Filter.Value value) || value.value() == null) {
            throw new IllegalArgumentException("filterExpression: " + key(expression) + " " + symbol(expression)
                    + " needs a value");
        }
        return value.value();
    }

    private static String single(Filter.Expression expression) {
        Object value = value(expression);
        if (value instanceof Collection<?>) {
            throw new IllegalArgumentException("filterExpression: " + key(expression) + " " + symbol(expression)
                    + " takes one value; use in [...] for a list");
        }
        return String.valueOf(value);
    }

    private static List<String> list(Filter.Expression expression) {
        Object value = value(expression);
        List<String> values = new ArrayList<>();
        if (value instanceof Collection<?> items) {
            items.forEach(item -> values.add(String.valueOf(item)));
        } else {
            values.add(String.valueOf(value));
        }
        return values;
    }

    private static String symbol(Filter.Expression expression) {
        return switch (expression.type()) {
            case EQ -> "==";
            case NE -> "!=";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case IN -> "in";
            case NIN -> "nin";
            default -> expression.type().name();
        };
    }
}
