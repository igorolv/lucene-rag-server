package ru.it_spectrum.ai.rag.mcp.source;

import java.util.List;

/**
 * One node of the section tree every tree format is read into.
 *
 * @param level    heading level, 1–6
 * @param title    heading text without markup
 * @param text     the section's own text, before its first child heading; may be empty
 * @param children nested sections in document order
 */
public record Section(int level, String title, String text, List<Section> children) {
}
