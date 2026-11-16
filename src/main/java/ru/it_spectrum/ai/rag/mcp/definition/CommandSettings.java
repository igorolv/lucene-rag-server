package ru.it_spectrum.ai.rag.mcp.definition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * An external command as {@code collections.json} writes it (docs/decisions.md D10): an argument
 * array run without a shell, a timeout, and the directory it runs in.
 *
 * @param command        the program and its arguments, as written
 * @param timeoutSeconds the run is stopped and fails after this many seconds
 * @param directory      the working directory: the directory of {@code collections.json}
 */
public record CommandSettings(List<String> command, int timeoutSeconds, Path directory) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    public CommandSettings {
        command = List.copyOf(command);
        if (command.isEmpty() || command.getFirst().isBlank()) {
            throw new IllegalArgumentException("command must be a non-empty array of strings");
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be at least 1");
        }
    }

    /**
     * Settings from values read from JSON.
     *
     * @param command the value of {@code command}: a list of strings
     * @param timeout the value of {@code timeoutSeconds}, or {@code null} for the default
     * @throws IllegalArgumentException when a value has the wrong shape
     */
    public static CommandSettings of(Object command, Object timeout, Path directory) {
        if (!(command instanceof List<?> items)) {
            throw new IllegalArgumentException("command must be a non-empty array of strings");
        }
        List<String> arguments = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String argument)) {
                throw new IllegalArgumentException("command must be a non-empty array of strings");
            }
            arguments.add(argument);
        }
        int seconds = DEFAULT_TIMEOUT_SECONDS;
        if (timeout != null) {
            if (!(timeout instanceof Integer || timeout instanceof Long)) {
                throw new IllegalArgumentException("timeoutSeconds must be a whole number");
            }
            seconds = (int) Math.min(Integer.MAX_VALUE, ((Number) timeout).longValue());
        }
        return new CommandSettings(arguments, seconds, directory);
    }
}
