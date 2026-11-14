package ru.it_spectrum.ai.rag.mcp.ingest.convert;

import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.collections.CommandSettings;
import ru.it_spectrum.ai.rag.mcp.collections.ConvertStep;
import ru.it_spectrum.ai.rag.mcp.ingest.Converter;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceText;
import ru.it_spectrum.ai.rag.mcp.ingest.exec.CommandRunner;

import java.io.IOException;
import java.util.List;

/**
 * The {@code command} converter: a program runs for every item and what it prints is the new
 * text. An argument containing {@code {file}} gets the absolute path of the item's file in its
 * place, and the program reads that file; without {@code {file}} the text so far is given on
 * standard input.
 */
@Component
public final class CommandConverter implements Converter {

    public static final String NAME = "command";

    /** Replaced in an argument by the path of the item's file. */
    static final String FILE = "{file}";

    private final CommandRunner runner;

    public CommandConverter(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Conversion prepare(ConvertStep step) {
        CommandSettings settings = CommandSettings.of(step.settings().get(NAME), step.settings().get("timeoutSeconds"),
                step.directory());
        boolean readsFile = settings.command().stream().anyMatch(argument -> argument.contains(FILE));
        return new Conversion() {
            @Override
            public boolean readsFile() {
                return readsFile;
            }

            @Override
            public SourceText convert(SourceItem item, SourceText text) throws IOException {
                if (!readsFile) {
                    return new SourceText(item.path(), runner.run(settings, settings.command(), text.text()));
                }
                if (item.file() == null) {
                    throw new IOException("{file} in a convert command needs a file, and " + item.location()
                            + " is not one");
                }
                String file = item.file().toAbsolutePath().toString();
                List<String> arguments = settings.command().stream().map(a -> a.replace(FILE, file)).toList();
                return new SourceText(item.path(), runner.run(settings, arguments, null));
            }
        };
    }
}
