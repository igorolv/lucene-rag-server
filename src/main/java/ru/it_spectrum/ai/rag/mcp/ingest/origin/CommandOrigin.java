package ru.it_spectrum.ai.rag.mcp.ingest.origin;

import org.springframework.stereotype.Component;
import ru.it_spectrum.ai.rag.mcp.definition.SourceDefinition;
import ru.it_spectrum.ai.rag.mcp.ingest.Origin;
import ru.it_spectrum.ai.rag.mcp.ingest.SourceItem;
import ru.it_spectrum.ai.rag.mcp.ingest.exec.CommandRunner;

import java.io.IOException;
import java.util.List;

/**
 * The origin of a source with a {@code command}: the program runs once and what it prints is the
 * only item of the source. The path of the item, what templates see as {@code {path}}, is the
 * command line as written.
 */
@Component
public final class CommandOrigin implements Origin {

    public static final String NAME = "command";

    private final CommandRunner runner;

    public CommandOrigin(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SourceItem> items(SourceDefinition source, String defaultInclude) throws IOException {
        List<String> command = source.command().command();
        String output = runner.run(source.command(), command, null);
        return List.of(new SourceItem(String.join(" ", command), null, output));
    }
}
