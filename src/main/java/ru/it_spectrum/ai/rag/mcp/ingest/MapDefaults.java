package ru.it_spectrum.ai.rag.mcp.ingest;

/**
 * Templates a format uses for the parts of a record that the {@code map} block of a source
 * leaves out. They are ordinary templates: a format fixes no layout in code
 * (docs/decisions.md D14).
 *
 * @param text {@code null} when the format has no default passage and {@code map.text} is
 *             required
 */
public record MapDefaults(String document, String title, String id, String text) {
}
