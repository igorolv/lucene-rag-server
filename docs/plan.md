# docs/plan.md — idea, architecture and stages

Read this for any design question and when starting or finishing a stage. Decisions and their
reasons are in `docs/decisions.md` (cited here as D1…D13, proposals as P1…P6).

## 1. Idea

Project knowledge — requirements, articles, troubleshooting playbooks, user manuals, catalogs of
error messages and parameters, descriptions of tables — lives in directories next to the code.
This server makes it searchable for AI agents and for people:

- the operator describes **collections** in `collections.json`;
- a command builds each collection into a Lucene index that holds both the text and the vectors;
- the server answers search requests over MCP with passages and their sources.

It retrieves and never generates (D1). The same server runs where a strong large-context model
reads the results and where a weak local model does; the difference is configuration (D5).

## 2. Status

Stage 1, 2026-11-14: collections described in `collections.json` are built from Markdown sources
into one Lucene index each and searched lexically.

- Implemented: the collections file loader, the `markdown` reader, the chunker, the index with its
  manifest, BM25 search with filters and snippets, server-side response limits, the commands
  `rag-index` and `rag-search`, the tool groups `collections` (`listCollections`) and `search`
  (`search`, `getDocument`). User documents: `docs/collections.md`, `docs/search.md`.
- Verified by `gradlew build` (42 tests on the synthetic knowledge in `testdata/`), by a stdio
  session calling every tool including error cases, and by building two collections from a real
  knowledge directory: 304 Markdown files gave 2,376 records in about 2 seconds, and a
  `rag-search` run takes under a second including JVM start.
- Not measured: search quality. That is stage 4.

In section 3, everything about formats other than Markdown, `where`, models, vectors, hybrid
search and the tool groups after `search` is the target design, not implemented.

## 3. Architecture

### 3.1 Components and data flow

```
build (our side)                                   serve (any site)

collections.json ─┐
source dirs ──────┼─► rag-index ─► <data-dir>/<collection>/ ─► MCP server ─► MCP client
model files ──────┘                 index + manifest             ▲            (assistant, agent)
                                                                 └─ rag-search (command line)
```

Per source, while building:

```
file ─► format reader ─► tree of sections ─► chunker ─► records ─► analyzer + embedding ─► Lucene
        (markdown, docbook)                             ▲
        tsv / yaml / jsonl / command ───────────────────┘   (already records)
```

- A **reader** knows one format and produces a tree: section, title, stable id, text, children,
  plus document fields (Markdown front matter, DocBook ids and page names).
- The **chunker** is shared by tree formats: it takes sections, merges small ones, splits large
  ones at paragraph boundaries and prefixes each chunk with a breadcrumb of ancestor titles.
- **Record formats** skip the chunker: one TSV row, YAML list item or JSONL line is one record.
- `command` runs an external program that prints JSONL records (D10).

### 3.2 Collections file

`<data-dir>/collections.json`, overridden by `RAG_MCP_COLLECTIONS_FILE`. What is implemented is
described in `docs/collections.md`, which owns the format: `description` and `sources` with
`path`, `include`, `exclude`, `format: markdown` and `chunk`. The sketch below is the target
shape; the remaining field names are fixed by the stage that implements them:

```json
{
  "models": {
    "e5-base": { "type": "onnx", "path": "models/multilingual-e5-base",
                 "queryPrefix": "query: ", "passagePrefix": "passage: " }
  },
  "collections": {
    "docs@billing": {
      "description": "Billing: requirements, glossary, articles, troubleshooting playbooks",
      "sources": [
        { "path": "C:/projects/billing/knowledge/articles", "include": "**/*.md",
          "format": "markdown", "chunk": { "splitBy": "h2", "maxChars": 2500 },
          "where": { "status": { "not": "skeleton" } } },
        { "path": "C:/projects/billing-doc/manual.xml", "format": "docbook" },
        { "format": "command", "command": ["python", "-I", "tools/export_help.py"],
          "timeoutSeconds": 300 }
      ],
      "language": "ru",
      "vectors": { "model": "e5-base" }
    },
    "catalogs@billing": {
      "description": "Billing: menu, error messages, checks, parameters",
      "sources": [ { "path": "C:/projects/billing/knowledge/catalogs", "include": "**/*.tsv", "format": "tsv" } ]
    }
  }
}
```

- The key is the collection name, the directory name under the data directory and the value
  tools take as `collection`. Form `<part>@<project>`; the same character rules as connection
  names in `jdbc-mcp-server`.
- `format` selects the reader; without it the file extension decides.
- `where` selects records by field values.
- A collection without `vectors` is lexical only (catalogs of codes and names).
- Relative paths resolve against the directory of `collections.json`. String values may reference
  `${VAR}`.
- A file with `command` sources is executable configuration: do not use one from an untrusted
  origin.

### 3.3 Collection directory and manifest

```
<data-dir>/
  collections.json            build side only
  models/<model>/             ONNX model and tokenizer files
  cache/embeddings/           vectors by hash of model and text (P4)
  logs/
  <collection>/
    manifest.json             name, description, field schema, analyzer, model id and its
                              parameters, chunking parameters, source revision, build time, counts
    index/                    Lucene index
```

Implemented so far: `<collection>/manifest.json` (schema, name, description, build time, server
version, counts, field names, sources with their chunking parameters) and `<collection>/index/`.
The model, the analyzer description and the source revision are added to the manifest by the
stages that need them.

- The server serves every collection directory it finds, filtered by the allowed list (P3, not
  implemented). It reads the manifest, not `collections.json` (D7).
- Nothing is cached between calls: the data directory is listed and an index is opened per call,
  so a rebuilt collection is seen at once.
- A build writes a temporary sibling directory and replaces the collection only when complete.
  The replacement is a delete and a move, not atomic: a call in flight on Windows makes it fail,
  and a call between the two steps sees no collection. Stage 5 makes it atomic.
- Searching vectors requires the model named in the manifest to be present; a mismatch is an error
  for vector and hybrid search, while lexical search keeps working.
- Delivery to another site: the collection directories and `models/`.

### 3.4 Search and tools

- One Lucene document per record: the text and the breadcrumb, each analyzed with Russian and with
  English stemming (modeled on `mail-mcp-server`), one exact lower-cased term per value of a
  document field for filters, the stored record, a vector field when the collection has vectors.
  Field names are in `index/IndexFields`; the behavior a user sees is in `docs/search.md`.
- Query words are optional and ranked by BM25, with the breadcrumb weighted twice the text (P7).
- Modes: `lexical` (BM25), `vector` (kNN), `hybrid` — both, fused by RRF (D12). Lucene 10 has a
  built-in RRF helper on `TopDocs`; the exact class and the version that introduced it are to
  verify in stage 3.
- A call may search several collections; results carry their collection.
- Responses are bounded: a default and a maximum number of results and a total size cap set by the
  server; a search returns snippets, `getDocument` returns the full record (P3).

Tool groups, each switched by `RAG_MCP_TOOLS_<GROUP>` (P2):

| Group | Tools | Stage |
|---|---|---|
| `collections` | `listCollections` | done |
| `search` | `search`, `getDocument` | done |
| `lookup` | exact lookups in catalog collections | 2 |
| `admin` | `rebuildCollection` | 5 |
| `browse` | list by filters, facets, related records | 6 |
| `debug` | analyzer output, score explanation, mode comparison | 6 |

### 3.5 Environments

| | Site with a weak local model | Development with a strong model |
|---|---|---|
| Tool groups | `collections`, `search`, `lookup` | all |
| Limits | few results, snippets, small size cap | defaults |
| Instances | one per audience over one index directory (D8) | one |
| Index | delivered ready (D7) | built in place, `rebuildCollection` available |
| Embedding model | in-process, same as at build time (D11) | in-process |

### 3.6 Packages

Root `ru.it_spectrum.ai.rag.mcp`. Dependencies flow downward only:

```
tools/ ──► search/ ──► index/
  └──────► collections/        (listCollections shows collections that are not built)
cli/ ───► collections/, index/, search/     (commands, without Spring)
index/ ──► source/ ──► collections/         (build: scan, read, chunk, write)
api/     — MCP wire records returned by tools
config/  — Spring wiring, properties, MCP customizer
```

| Package | Responsibility |
|---|---|
| `collections/` | `collections.json`: `CollectionsLoader`, `CollectionDefinition`, `SourceDefinition`, `ChunkSettings`, name rules, `${VAR}` placeholders |
| `source/` | `SourceScanner` (files of a source), `MarkdownReader` (file to `SourceDocument` with a `Section` tree), `Chunker` (tree to `ChunkRecord`) |
| `index/` | `Analyzers`, `IndexFields`, `CollectionIndexer` (build and swap), `Manifest`, `CollectionStore` (built collections under the data directory) |
| `search/` | `SearchService` (query, filters, paging, snippets, limits), `SearchHit`, `SearchPage` |
| `cli/` | `RagIndexCommand`, `RagSearchCommand`, dispatched by the main class before Spring starts |
| `tools/` | One `@Service` per tool group: `CollectionTools`, `SearchTools` |
| `api/` | Response records with `@Schema` |
| `config/` | `RagMcpProperties`, `RagConfig`, `McpServerConfig` |

Planned: `embedding/` (model interface, ONNX implementation, cache) in stage 3.

## 4. Stages

Each stage ends with `gradlew build` passing, the user documents updated and the stage marked
done here. A stage is taken only when the user asks for it.

| # | Stage | Result | State |
|---|---|---|---|
| 0 | Scaffold | Build, stdio server, `listCollections` stub, documents | done 2026-11-14 |
| 1 | Markdown collection, lexical search | `collections.json` loader; `markdown` reader with front matter; chunker; Lucene index and manifest; `rag-index` and `rag-search` commands; tools `listCollections`, `search`, `getDocument`; response limits | done 2026-11-14 |
| 2 | Remaining formats | `tsv`, `yaml`, `jsonl`, `docbook`, `command`; `where` conditions; `lookup` group | ⬜ |
| 3 | Vectors and hybrid search | ONNX embedding model; vector field; `vector` and `hybrid` modes with RRF; model check against the manifest; embedding cache | ⬜ |
| 4 | Measurement | Question set with expected sources; recall and rank per mode; tuning of chunk size and limits for a small-context model; decision on a reranker (D12) | ⬜ |
| 5 | Delivery | Allowed-collections setting; atomic rebuild and `rebuildCollection`; streamable HTTP transport for MCP; Docker image; delivery procedure for indexes and models | ⬜ |
| 6 | Convenience and release | `browse` and `debug` groups; CI and release workflow; registry entries; third-party notices | ⬜ |

Stage 2 in more detail, as the next one:

1. A reader interface in `source/` chosen by `format`, or by file extension when `format` is
   absent; `SourceScanner` takes its default `include` from the reader.
2. Record formats, one record per item, no chunker: `tsv` (a row; the header row names the
   fields), `yaml` (an item of a named list), `jsonl` (a line).
3. `docbook`: a StAX reader of nested `section` elements into the section tree, with `xml:id` as
   the stable id and the page name from `<?dbhtml filename?>` as a field.
4. `command`: an external program printing JSONL records; argument array, no shell, timeout,
   working directory of `collections.json` (D10).
5. `where`: selection of records by field values, applied after reading.
6. The `lookup` tool group for exact lookups in catalog collections; which fields of a record
   format are searchable text and which are filters is decided here.

Left out of stage 1 and not assigned to a stage: the source revision in the manifest (it needs a
decision on calling `git`, which invariant 6 of `AGENTS.md` does not allow today) and user
word-form rules (`docs/decisions.md` P9).

Observed while verifying stage 1, for stage 4: a lexical query phrased differently from the text
ranks an unrelated record first; Spring AI repeats the message of a tool error twice in the
response text.

## 5. Open questions

- How the assistant service connects to this server and to other MCP servers (D1).
- Which model and API the target site provides; whether it has an embedding endpoint.
- Converters to Markdown for PDF and Office sources (code exists in `mail-mcp-server` `convert/`).
- A format for SQL files with a metadata header, or describing them through a YAML source.
- The license (P1) and the other proposals in `docs/decisions.md` section 3.
- Legacy HTTP API (D2), if a client needs it.
