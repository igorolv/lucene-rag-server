package ru.it_spectrum.ai.rag.mcp.collections;

/**
 * One entry of a source's {@code join}: a lookup whose units give values to the units of the
 * source ({@code docs/collections.md}, "Joining values from another file").
 *
 * @param name   the prefix of the values the lookup gives: {@code <name>.<value>}
 * @param source what the lookup reads and how: origin, {@code convert}, {@code format} and
 *               {@code split}; its {@code map} and {@code where} are not used
 * @param key    template over the values of a unit of the lookup: what the unit is found by
 * @param on     template over the values of a unit of the source: the key it looks for
 */
public record JoinDefinition(String name, SourceDefinition source, String key, String on) {
}
