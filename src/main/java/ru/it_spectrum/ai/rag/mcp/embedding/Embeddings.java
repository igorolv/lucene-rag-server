package ru.it_spectrum.ai.rag.mcp.embedding;

/**
 * Vectors of a batch of texts.
 *
 * @param vectors one unit-length vector per text, in the order of the texts
 * @param cut     per text: it was longer than the model's input limit and only its beginning was
 *                embedded
 */
public record Embeddings(float[][] vectors, boolean[] cut) {

    /** How many texts were cut. */
    public int truncated() {
        int count = 0;
        for (boolean c : cut) {
            if (c) {
                count++;
            }
        }
        return count;
    }
}
