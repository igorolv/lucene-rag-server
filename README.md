# lucene-rag-server

An MCP server that turns directories of prepared project knowledge into searchable collections.
Text and embedding vectors live in one Apache Lucene index per collection; a search combines BM25
and vector similarity. The server returns passages and their sources and leaves answering to the
client.

> **Status: early.** Markdown, TSV, YAML, JSONL, SQL and PDF sources, lexical, vector and hybrid search
> work; search quality is not measured yet. See [docs/plan.md](docs/plan.md).

## What it is for

- Describe collections in one `collections.json`: which directories to read, how to cut files
  into records and how to lay a record out (`split` and `map`), whether to compute vectors and
  with which model.
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

## Quickstart

1. Describe a collection in `~/.lucene-rag-server/collections.json`
   ([docs/collections.md](docs/collections.md)):

   ```json
   {
     "collections": {
       "docs@billing": {
         "description": "Billing: articles and troubleshooting playbooks",
         "sources": [ { "path": "C:/projects/billing/knowledge" } ]
       }
     }
   }
   ```

2. Build it:

   ```bash
   java -jar build/libs/lucene-rag-server.jar rag-index docs@billing
   ```

3. Search it from the command line, or through the MCP tools:

   ```bash
   java -jar build/libs/lucene-rag-server.jar rag-search docs@billing "payment was not matched"
   ```

4. Measure how well it finds what a question set expects
   ([docs/search.md](docs/search.md#measuring-search)):

   ```bash
   java -jar build/libs/lucene-rag-server.jar rag-eval docs@billing C:/projects/billing/knowledge/eval/questions.jsonl
   ```

## Tools

| Tool | Group | What it does |
|---|---|---|
| `listCollections` | `collections` | Collections of this server, their descriptions and filter fields |
| `search` | `search` | Passages matching a query, filters and a filter expression in one or more collections, by words, by vectors or both ([docs/search.md](docs/search.md#modes)) |
| `getDocument` | `search` | One record in full |
| `lookup` | `lookup` | Catalog records whose key fields have an exact value ([docs/search.md](docs/search.md#exact-lookup)) |

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `RAG_MCP_DATA_DIR` | `~/.lucene-rag-server` | Root of local data: one directory per collection, plus `models/` (embedding model files, [docs/collections.md](docs/collections.md#vectors)), `cache/` and `logs/` |
| `RAG_MCP_COLLECTIONS_FILE` | `<data-dir>/collections.json` | Collection definitions; needed to build, not to serve |
| `RAG_MCP_TOOLS_COLLECTIONS` | `true` | Expose the `collections` tool group |
| `RAG_MCP_TOOLS_SEARCH` | `true` | Expose the `search` tool group |
| `RAG_MCP_TOOLS_LOOKUP` | `true` | Expose the `lookup` tool group |
| `RAG_MCP_SEARCH_DEFAULT_LIMIT` | `10` | Hits per page when a call names no limit |
| `RAG_MCP_SEARCH_MAX_LIMIT` | `50` | The most hits per page a call can get |
| `RAG_MCP_RESPONSE_MAX_CHARS` | `20000` | Characters of passage text in one response |

## License

Apache License 2.0, see [LICENSE](LICENSE).
