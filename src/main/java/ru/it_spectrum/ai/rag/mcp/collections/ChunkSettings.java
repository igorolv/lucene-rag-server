package ru.it_spectrum.ai.rag.mcp.collections;

/**
 * How one source is cut into records.
 *
 * @param splitBy  heading level whose sections become records, 1–6; deeper sections stay inside
 *                 their parent record
 * @param maxChars a longer record is split at paragraph boundaries
 * @param minChars a shorter record is joined to the previous one under the same parent heading;
 *                 0, the default, joins nothing: a merged section loses its own title and
 *                 breadcrumb, which is what a query usually matches
 */
public record ChunkSettings(int splitBy, int maxChars, int minChars) {

    public static final ChunkSettings DEFAULT = new ChunkSettings(2, 2500, 0);

    public ChunkSettings {
        if (splitBy < 1 || splitBy > 6) {
            throw new IllegalArgumentException("chunk.splitBy must be h1…h6");
        }
        if (maxChars < 200) {
            throw new IllegalArgumentException("chunk.maxChars must be at least 200");
        }
        if (minChars < 0 || minChars >= maxChars) {
            throw new IllegalArgumentException("chunk.minChars must be between 0 and chunk.maxChars");
        }
    }
}
