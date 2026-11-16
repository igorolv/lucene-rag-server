package ru.it_spectrum.ai.rag.mcp.ingest.exec;

import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.definition.CommandSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external program while an index is built (docs/decisions.md D10): the argument array
 * as given, without a shell, in the directory of {@code collections.json}, stopped after its
 * timeout. Standard input, output and error go through temporary files, so a program that writes
 * much never blocks on a full pipe. The output is read as UTF-8.
 *
 * <p>Only the build runs commands, and only as {@code collections.json} writes them; nothing a
 * client sends reaches a command line (AGENTS.md, invariant 6).
 */
@Component
public final class CommandRunner {

    private static final int ERROR_TAIL = 2000;

    /**
     * @param settings  the command as written, for its timeout and directory
     * @param arguments the program and its arguments, after the caller's substitutions
     * @param input     text given to the program on standard input, or {@code null} for none
     * @return what the program printed on standard output
     * @throws IOException when the program cannot be started, exits with a code other than 0 or
     *                     does not finish in time
     */
    public String run(CommandSettings settings, List<String> arguments, String input) throws IOException {
        Path stdin = Files.createTempFile("rag-command-", ".in");
        Path stdout = Files.createTempFile("rag-command-", ".out");
        Path stderr = Files.createTempFile("rag-command-", ".err");
        try {
            Files.writeString(stdin, input == null ? "" : input, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(arguments)
                    .directory(settings.directory().toFile())
                    .redirectInput(stdin.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            Process process;
            try {
                process = builder.start();
            } catch (IOException e) {
                throw new IOException("Cannot run " + describe(arguments) + ": " + e.getMessage(), e);
            }
            boolean finished;
            try {
                finished = process.waitFor(settings.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop(process);
                throw new IOException("Interrupted while running " + describe(arguments), e);
            }
            if (!finished) {
                stop(process);
                throw new IOException(describe(arguments) + " did not finish in " + settings.timeoutSeconds()
                        + " seconds");
            }
            if (process.exitValue() != 0) {
                throw new IOException(describe(arguments) + " exited with code " + process.exitValue()
                        + tail(stderr));
            }
            return Files.readString(stdout, StandardCharsets.UTF_8);
        } finally {
            deleteQuietly(stdin);
            deleteQuietly(stdout);
            deleteQuietly(stderr);
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(List<String> arguments) {
        return "the command " + String.join(" ", arguments);
    }

    private static String tail(Path stderr) {
        try {
            String text = new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                return "";
            }
            return ": " + (text.length() > ERROR_TAIL ? "…" + text.substring(text.length() - ERROR_TAIL) : text);
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            file.toFile().deleteOnExit();
        }
    }
}
