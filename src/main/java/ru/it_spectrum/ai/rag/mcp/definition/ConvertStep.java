package ru.it_spectrum.ai.rag.mcp.definition;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry of the {@code convert} array of a source, as written. Which converter it is and
 * whether its settings fit is decided by the registry of steps: the entry names a converter by
 * one of its keys, as {@code {"command": [...]}} names the {@code command} converter.
 *
 * @param settings  the keys of the entry with their values read from JSON: strings, numbers,
 *                  booleans, lists and maps
 * @param directory the directory of {@code indexes.json}, against which a converter resolves
 *                  what it runs
 */
public record ConvertStep(Map<String, Object> settings, Path directory) {

    public ConvertStep {
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }
}
