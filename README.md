# lucene-rag-server

An MCP server that turns directories of prepared project knowledge into searchable Apache Lucene
indexes that keep text and embedding vectors together; a search combines BM25
and vector similarity. The server returns passages and their sources and leaves answering to the
client.

> **Status: before the first release.** Markdown, TSV, YAML, JSONL, SQL, PDF, HTML and Office sources, lexical,
> vector and hybrid search, MCP over stdio and HTTP, in-process and OpenAI-compatible embedding
> models work. Search quality is measured with `rag-eval` on one real corpus, with a small question
> set so far. See [docs/plan.md](docs/plan.md).

## What it is for

- Describe indexes in one `indexes.json`: which directories to read, how to cut files
  into records and how to lay a record out (`split` and `map`), whether to compute vectors and
  with which model.
- Build each index into a self-contained directory that can be copied to another
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
`java -jar <absolute path>/lucene-rag-server.jar`. Released jars are on the
[Releases](https://github.com/igorolv/lucene-rag-server/releases) page; with
[JBang](https://www.jbang.dev/) the latest release runs as
`jbang lucene-rag-server@igorolv/lucene-rag-server`.

With `RAG_MCP_TRANSPORT=http` it serves the streamable HTTP transport of MCP at
`http://127.0.0.1:8080/mcp` instead, for clients that connect to a running server:

```bash
RAG_MCP_TRANSPORT=http java -jar build/libs/lucene-rag-server.jar
```

The server has no authentication; by default it accepts local connections only. To serve other
machines, set `RAG_MCP_HTTP_ADDRESS` and put the server behind something that controls access.

## Quickstart

1. Describe an index in `~/.lucene-rag-server/indexes.json`
   ([docs/indexes.md](docs/indexes.md)):

   ```json
   {
     "indexes": {
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

### Setting up a project with an agent

A coding agent can write the indexes file of a project for you. Point it at
[docs/setup-agent.md](docs/setup-agent.md), the order of work written for agents: survey the
files, choose indexes and sources, build, check search, report. For example, in the project:

> Read https://github.com/igorolv/lucene-rag-server/blob/main/docs/setup-agent.md and set up
> lucene-rag-server indexes for this project's knowledge in `knowledge/`.

## Tools

| Tool | Group | What it does |
|---|---|---|
| `describeKnowledge` | `knowledge` | The projects and parts of the knowledge with their record counts, descriptions and the fields to filter by, with the values of a field that has few ([docs/search.md](docs/search.md#describing-the-knowledge)) |
| `search` | `search` | Passages matching a query, filters and a filter expression across all indexes, by words, by vectors or both ([docs/search.md](docs/search.md#modes)) |
| `getDocument` | `search` | One record in full, by the id a hit gives (`<index>/<record id>`); with `scope` the whole document or its rest |
| `lookup` | `lookup` | Catalog records whose key fields have an exact value ([docs/search.md](docs/search.md#exact-lookup)) |
| `listIndexes` | `admin` | The indexes for the operator: built or not, when, records, model problems, configuration errors and import problems ([docs/indexes.md](docs/indexes.md#building)) |
| `rebuildIndex` | `admin` | Rebuild an index of `indexes.json` from its sources, as `rag-index` does ([docs/indexes.md](docs/indexes.md#building)) |
| `fieldValues` | `browse` | Values of a field over all indexes, or over the records that pass filters, with the number of records of each, to filter by ([docs/search.md](docs/search.md#browsing)) |
| `relatedRecords` | `browse` | Other sections of a record's document and the records most like it |
| `explainSearch` | `debug` | The query's words after analysis and where records rank in each list, for tuning ([docs/search.md](docs/search.md#explaining-a-search)) |

A record is also the MCP resource `rag://` followed by its id, `<index>/<record id>`
(`rag://{index}/{id}`), and clients that support completion suggest its index and id ([docs/search.md](docs/search.md#results)).

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `RAG_MCP_DATA_DIR` | `~/.lucene-rag-server` | Root of local data: one directory per index, plus `models/` (embedding model files, [docs/indexes.md](docs/indexes.md#vectors)), `cache/` and `logs/` |
| `RAG_MCP_INDEXES_FILE` | `<data-dir>/indexes.json`, or `indexes.yaml` | Index definitions, in JSON or YAML, which may import the indexes files of projects; needed to build, not to serve |
| `RAG_MCP_TOOLS_KNOWLEDGE` | `true` | Expose the `knowledge` tool group (`describeKnowledge`) |
| `RAG_MCP_TOOLS_SEARCH` | `true` | Expose the `search` tool group |
| `RAG_MCP_TOOLS_LOOKUP` | `true` | Expose the `lookup` tool group |
| `RAG_MCP_TOOLS_ADMIN` | `true` | Expose the `admin` tool group (`listIndexes`, `rebuildIndex`) |
| `RAG_MCP_TOOLS_BROWSE` | `true` | Expose the `browse` tool group (`fieldValues`, `relatedRecords`) |
| `RAG_MCP_TOOLS_DEBUG` | `true` | Expose the `debug` tool group (`explainSearch`) |
| `RAG_MCP_SEARCH_DEFAULT_LIMIT` | `10` | Hits per page when a call names no limit |
| `RAG_MCP_SEARCH_MAX_LIMIT` | `50` | The most hits per page a call can get |
| `RAG_MCP_RESPONSE_MAX_CHARS` | `20000` | Characters of passage text in one response |
| `RAG_MCP_TRANSPORT` | `stdio` | MCP transport: `stdio`, or `http` for the streamable HTTP transport at `/mcp` |
| `RAG_MCP_HTTP_ADDRESS` | `127.0.0.1` | Address the `http` transport listens on |
| `RAG_MCP_HTTP_PORT` | `8080` | Port of the `http` transport |

Embedding models run in the server process from files in `<data-dir>/models/`, which
`rag-model fetch e5-small` downloads, or on an
OpenAI-compatible endpoint configured by Spring AI's `SPRING_AI_OPENAI_*` settings
([docs/indexes.md](docs/indexes.md#spring-ai-models)).

## License

Apache License 2.0, see [LICENSE](LICENSE). The licenses of the bundled libraries are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
