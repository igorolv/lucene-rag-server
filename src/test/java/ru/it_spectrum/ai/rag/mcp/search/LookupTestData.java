package ru.it_spectrum.ai.rag.mcp.search;

import ru.it_spectrum.ai.rag.mcp.TestKnowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A catalog collection with key fields from three formats, and a collection without keys. */
public final class LookupTestData {

    public static final String CATALOGS = "catalogs@billing";
    public static final String NOTES = "notes@billing";

    private LookupTestData() {
    }

    public static Path writeCollectionsFile(Path dataDir) throws IOException {
        String root = TestKnowledge.root().toString().replace('\\', '/');
        Path file = dataDir.resolve("collections.json");
        Files.createDirectories(dataDir);
        Files.writeString(file, """
                {"collections": {
                  "%1$s": {"sources": [
                    {"path": "%2$s/catalogs/messages", "format": "tsv",
                     "map": {"title": "{column.text}", "text": "{column.text} ({column.method})",
                             "fields": {"text": "{column.text}", "method": "{column.method}", "kind": "{column.kind}"},
                             "keys": ["text", "method"]}},
                    {"path": "%2$s/catalogs/menu", "format": "tsv",
                     "map": {"title": "{column.name}", "text": "{column.name} {column.help}",
                             "fields": {"id": "{column.id}", "view": "{column.view}"}, "keys": ["id", "view"]}},
                    {"path": "%2$s/data/enums", "format": "yaml", "split": {"list": "values"},
                     "map": {"document": "{yaml.enum}", "title": "{item.label}", "text": "{item.label}",
                             "fields": {"code": "{item.code}", "constants": "{item.constant}"},
                             "keys": ["constants", "absent"]}}
                  ]},
                  "%3$s": {"sources": [{"path": "%2$s/articles"}]}
                }}
                """.formatted(CATALOGS, root, NOTES));
        return file;
    }
}
