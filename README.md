# lucene-rag-server

An MCP server that turns directories of prepared project knowledge into searchable collections.
Text and embedding vectors live in one Apache Lucene index per collection; a search combines BM25
and vector similarity. The server returns passages and their sources and leaves answering to the
client.

> **Status: scaffold.** The server starts and answers `listCollections` with an empty list.
> Indexing and search are not implemented yet; see [docs/plan.md](docs/plan.md).

## What it is for

- Describe collections in one `collections.json`: which directories to read, what to take from
  them, whether to compute vectors and with which model.
- Build each collection into a self-contained index directory that can be copied to another
  machine and served there without the sources.
- Serve the same index to a large-context model with every tool enabled, or to a small local
  model with a short tool list and tight response limits, by environment variables alone.

## Build and run

Requires JDK 25 (Gradle downloads it when missing).

```bash
./gradlew build
```

```bash
java -jar build/libs/lucene-rag-server.jar
```

The server speaks MCP over stdio. Register it in an MCP client as
`java -jar <absolute path>/lucene-rag-server.jar`.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `RAG_MCP_DATA_DIR` | `~/.lucene-rag-server` | Root of local data: one directory per collection, plus `logs/` |
| `RAG_MCP_COLLECTIONS_FILE` | `<data-dir>/collections.json` | Collection definitions (not read yet) |
| `RAG_MCP_TOOLS_COLLECTIONS` | `true` | Expose the `listCollections` tool |

## License

Apache License 2.0, see [LICENSE](LICENSE).
