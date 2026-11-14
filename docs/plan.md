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

Scaffold, 2026-11-14: Gradle build, Spring Boot entry point, stdio MCP transport, one tool group
(`collections`) with `listCollections` returning an empty list, the settings `RAG_MCP_DATA_DIR`,
`RAG_MCP_COLLECTIONS_FILE` and `RAG_MCP_TOOLS_COLLECTIONS`. Verified by `gradlew build` and by a
stdio session (`initialize`, `tools/list`, `tools/call`), with the group on and off.

Everything in section 3 beyond that is the target design, not implemented.

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

`<data-dir>/collections.json`, overridden by `RAG_MCP_COLLECTIONS_FILE`. Sketch of the target
shape; field names are fixed by the stage that implements them:

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

- The server serves every collection directory it finds, filtered by the allowed list (P3). It
  reads the manifest, not `collections.json` (D7).
- A build writes a new directory and swaps it in whole, so a running server never sees a partial
  index.
- Searching vectors requires the model named in the manifest to be present; a mismatch is an error
  for vector and hybrid search, while lexical search keeps working.
- Delivery to another site: the collection directories and `models/`.

### 3.4 Search and tools

- One Lucene document per record: analyzed text (Russian and English analysis ported from
  `mail-mcp-server`), title, keyword fields for filters, the stored record, a vector field when
  the collection has vectors.
- Modes: `lexical` (BM25), `vector` (kNN), `hybrid` — both, fused by RRF (D12). Lucene 10 has a
  built-in RRF helper on `TopDocs`; the exact class and the version that introduced it are to
  verify in stage 3.
- A call may search several collections; results carry their collection.
- Responses are bounded: a default and a maximum number of results and a total size cap set by the
  server; a search returns snippets, `getDocument` returns the full record (P3).

Tool groups, each switched by `RAG_MCP_TOOLS_<GROUP>` (P2):

| Group | Tools | Stage |
|---|---|---|
| `collections` | `listCollections` | scaffold, filled in stage 1 |
| `search` | `search`, `getDocument` | 1 |
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

Root `ru.it_spectrum.ai.rag.mcp`. Existing: `config/` (properties, MCP customizer), `api/` (MCP
wire records), `tools/` (one `@Service` per tool group). Planned, added by the stage that needs
them: `collections/` (file, definitions, registry), `source/` (readers, tree, chunker, external
command), `index/` (analyzers, writer, manifest, committed readers), `embedding/` (model
interface, ONNX implementation, cache), `search/` (modes, fusion, snippets), `cli/` (commands
dispatched before Spring starts).

## 4. Stages

Each stage ends with `gradlew build` passing, the user documents updated and the stage marked
done here. A stage is taken only when the user asks for it.

| # | Stage | Result | State |
|---|---|---|---|
| 0 | Scaffold | Build, stdio server, `listCollections` stub, documents | done 2026-11-14 |
| 1 | Markdown collection, lexical search | `collections.json` loader; `markdown` reader with front matter; chunker; Lucene index and manifest; `rag-index` and `rag-search` commands; tools `listCollections`, `search`, `getDocument`; response limits | ⬜ |
| 2 | Remaining formats | `tsv`, `yaml`, `jsonl`, `docbook`, `command`; `where` conditions; `lookup` group | ⬜ |
| 3 | Vectors and hybrid search | ONNX embedding model; vector field; `vector` and `hybrid` modes with RRF; model check against the manifest; embedding cache | ⬜ |
| 4 | Measurement | Question set with expected sources; recall and rank per mode; tuning of chunk size and limits for a small-context model; decision on a reranker (D12) | ⬜ |
| 5 | Delivery | Allowed-collections setting; atomic rebuild and `rebuildCollection`; streamable HTTP transport for MCP; Docker image; delivery procedure for indexes and models | ⬜ |
| 6 | Convenience and release | `browse` and `debug` groups; CI and release workflow; registry entries; third-party notices | ⬜ |

Stage 1 in more detail, as the next one:

1. `collections/`: file shape, loader with `${VAR}` placeholders, name validation, registry —
   modeled on `jdbc-mcp-server` package `connection/`.
2. `source/`: section tree, `markdown` reader (front matter scalars and lists become fields),
   chunker with `splitBy`, `maxChars`, `minChars`, stable chunk ids.
3. `index/`: analyzers and word forms ported from `mail-mcp-server` `index/`, writer, manifest,
   committed reader without the writer lock.
4. `search/`: BM25 over text and title, filters, snippets with `UnifiedHighlighter`.
5. `cli/`: `rag-index <collection>`, `rag-search <collection> <query>`.
6. `tools/`: `search` group; server-side limits.
7. Synthetic test knowledge under `testdata/`; no real project data in the repository.

## 5. Open questions

- How the assistant service connects to this server and to other MCP servers (D1).
- Which model and API the target site provides; whether it has an embedding endpoint.
- Converters to Markdown for PDF and Office sources (code exists in `mail-mcp-server` `convert/`).
- A format for SQL files with a metadata header, or describing them through a YAML source.
- The license (P1) and the other proposals in `docs/decisions.md` section 3.
- Legacy HTTP API (D2), if a client needs it.
