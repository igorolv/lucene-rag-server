package ru.it_spectrum.ai.rag.mcp.collections;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * How one source is cut into units, before the units are laid out as records
 * ({@link MapSettings}).
 *
 * @param level          heading level whose sections become units, 1–6; deeper sections stay
 *                       inside their parent unit. {@link #DOCUMENT} makes the whole file one unit
 * @param headingPattern regular expression a heading at {@code level} must contain to become a
 *                       unit, or {@code null}. With a pattern, everything else in the file is
 *                       left out; named groups of the pattern are available to the mapping as
 *                       {@code heading.<group>}
 * @param maxChars       a longer unit is split at paragraph boundaries
 * @param minChars       a shorter unit is joined to the previous one under the same parent
 *                       heading; 0, the default, joins nothing: a joined section loses its own
 *                       title and breadcrumb, which is what a query usually matches
 */
public record SplitSettings(int level, String headingPattern, int maxChars, int minChars) {

    /** {@link #level()} of a source whose files are not split by headings. */
    public static final int DOCUMENT = 0;

    public static final SplitSettings DEFAULT = new SplitSettings(2, null, 2500, 0);

    public SplitSettings {
        if (level < DOCUMENT || level > 6) {
            throw new IllegalArgumentException("split.level must be h1…h6 or \"document\"");
        }
        if (maxChars < 200) {
            throw new IllegalArgumentException("split.maxChars must be at least 200");
        }
        if (minChars < 0 || minChars >= maxChars) {
            throw new IllegalArgumentException("split.minChars must be between 0 and split.maxChars");
        }
        if (headingPattern != null) {
            if (level == DOCUMENT) {
                throw new IllegalArgumentException("split.headingPattern needs a heading level, not \"document\"");
            }
            try {
                Pattern.compile(headingPattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("split.headingPattern is not a valid regular expression: "
                        + e.getDescription());
            }
        }
    }

    /** The compiled {@link #headingPattern()}, or {@code null}. */
    public Pattern compiledHeadingPattern() {
        return headingPattern == null ? null : Pattern.compile(headingPattern);
    }
}
