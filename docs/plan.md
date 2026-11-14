# docs/plan.md — idea, architecture and stages

Read this for any design question and when starting or finishing a stage. Decisions and their
reasons are in `docs/decisions.md` (cited here as D1…D21, proposals as P1…P11).

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

Stage 1 and the start of stage 2, 2026-11-15: collections described in `collections.json` are
built from Markdown sources into one Lucene index each and searched lexically; every source
configures how it is cut (`split`) and how a unit becomes a record (`map`), and a source is built
by the five-step pipeline of section 3.1, with Markdown, TSV, YAML and JSONL as formats; a source
may take its reading keys from a named reader.

- Implemented: the collections file loader with named readers; the step interfaces, the registry and
  `SourcePipeline` in `ingest/`; the `files` origin, the formats `markdown`, `tsv`, `yaml` and `jsonl`, the heading splitter
  and the template mapper as step beans; the index with its manifest; BM25 search with filters
  and snippets; server-side response limits; the commands `rag-index` (in a Spring context
  without the MCP server) and `rag-search`; the tool groups `collections` (`listCollections`) and
  `search` (`search`, `getDocument`). User documents: `docs/collections.md`, `docs/search.md`.
- Verified by `gradlew build` (64 tests on the synthetic knowledge in `testdata/`) and by running
  the jar: `rag-index` builds a collection in under 2 seconds including the context start and
  writes nothing but its report, a misspelled template value and an unknown format are reported
  as configuration errors by `rag-index` and by `listCollections`, `rag-search` and a stdio
  session work as before. Before the pipeline was introduced, collections were built from a real
  knowledge directory: 304 Markdown files gave 2,376 records in about 2 seconds, and requirement
  files without front matter were laid out by `split.headingPattern` and `map` alone (141 files,
  601 records). That build has not been repeated on the pipeline.
- Not measured: search quality. That is stage 4.

In section 3, everything about origins other than files, `convert`, the `sql` format, `where`, models, vectors, hybrid search and the tool groups after `search` is the
target design, not implemented.

## 3. Architecture

### 3.1 Components and data flow

```
build (our side)                                   serve (any site)

collections.json ─┐
source dirs ──────┼─► rag-index ─► <data-dir>/<collection>/ ─► MCP server ─► MCP client
model files ──────┘                 index + manifest             ▲            (assistant, agent)
                                                                 └─ rag-search (command line)
```

Per source, while building, a fixed pipeline of five steps (D15). It is extract, transform, load:
origin and `convert` extract, `format`, `split` and `map` transform, the indexer loads.

```
origin ─► convert ─► format ─┬─► section tree ─► split ─► units ─┐
 items     text       parse  └─► rows ──────────────────────────┴─► map ─► records ─► Lucene
```

| Step | In → out | Configured by | Implementations |
|---|---|---|---|
| origin | source entry → items | `path`, `include`, `exclude`, or `command` | `files`; `command` (one run, its output is the only item) |
| `convert` | text → text, a chain, empty by default | `convert` array | `command` (per item) |
| `format` | text → section tree or rows | `format`, `split.list` for YAML | tree: `markdown` (`docbook` later, D17); rows: `tsv`, `yaml`, `jsonl`, `sql` |
| `split` | section tree → units | `split` | one: by heading level and pattern |
| `map` | unit → records | `map` | one: templates (D14) |

- Three shapes travel between the steps: **text** (with the item's relative path), a **section
  tree** (section, title, text, children, plus document fields such as Markdown front matter) and
  a **unit** of named values.
- A row format skips the splitter: one TSV row, YAML file or item of a named YAML list (D22),
  JSONL line or SQL file is one unit whose values are its columns, keys or header lines. A format
  receives the `split` block of its source and checks it, so YAML can take `split.list`.
- A format declares the names of the values it gives, its default `map` templates and its default
  `include`; the template check of a source uses that declaration instead of a class of rules per
  format.
- A command is never a format (D10). As the origin it replaces `path`; in `convert` it runs for
  every item, receives the file path or the previous step's text, and prints text. Both are an
  argument array without a shell, with a timeout, in the directory of `collections.json`.
- origin, `convert` and `format` have named implementations found through one registry; `split`
  and `map` are interfaces with one implementation each until a second one is needed.
- `mail-mcp-server` reads attachments the same way: Pandoc turns many formats into Markdown. Here
  Pandoc is an example of a `convert` command and not a dependency (D17).

### 3.2 Collections file

`<data-dir>/collections.json`, overridden by `RAG_MCP_COLLECTIONS_FILE`. What is implemented is
described in `docs/collections.md`, which owns the format: `readers`, `description` and
`sources` with `path`, `include`, `exclude`, `format: markdown`, `split`, `map` and `reader`. The sketch below is the
target shape; the remaining field names are fixed by the stage that implements them:

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
          "format": "markdown", "split": { "level": "h2", "maxChars": 2500 },
          "map": { "fields": { "area": "{path[0]}" } },
          "where": { "status": { "not": "skeleton" } } },
        { "path": "C:/projects/billing/knowledge/queries", "include": "**/*.sql", "format": "sql" },
        { "path": "C:/projects/billing/manuals", "include": "**/*.docx",
          "convert": [ { "command": ["pandoc", "-f", "docx", "-t", "gfm", "{file}"],
                         "timeoutSeconds": 60 } ],
          "format": "markdown" },
        { "command": ["python", "-I", "tools/export_help.py"], "timeoutSeconds": 300,
          "format": "jsonl" }
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
- A source has either `path` (files) or `command` (one run) as its origin.
- `format` says how the text is parsed; without it the file extension decides. With `convert` it
  is the format of the converted text.
- A top-level `readers` object holds named sets of `convert`, `format`, `split` and `map`; a
  source names one with `reader` and may override single keys (D18).
- `where` selects records by field values.
- A collection without `vectors` is lexical only (catalogs of codes and names).
- Relative paths resolve against the directory of `collections.json`. String values may reference
  `${VAR}`.
- A file with commands is executable configuration: do not use one from an untrusted
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
  └──────► collections/, ingest/    (listCollections shows collections that are not built)
cli/ ───► collections/, ingest/, index/, search/
index/ ──► ingest/ ──► collections/           (build: list, convert, parse, split, map, write)
api/     — MCP wire records returned by tools
config/  — Spring wiring, properties, MCP customizer
```

| Package | Responsibility |
|---|---|
| `collections/` | The shape of `collections.json` and nothing about formats: `CollectionsLoader`, `CollectionDefinition`, `SourceDefinition`, `SplitSettings`, `MapSettings`, name rules, `${VAR}` placeholders |
| `ingest/` | The contracts of the build pipeline (D15): the step interfaces `Origin`, `Converter`, `Format`, `Splitter`, `Mapper`; the shapes `SourceItem`, `SourceText`, `ParsedDocument` (`SectionTree` with `Section`, or `Rows`), `Unit`, `ChunkRecord`; what a format declares (`MapDefaults`, `ValueNames`); `StepRegistry` (implementations by name, and the check of a collection's sources); `SourcePipeline` (the steps of one source, checked and ready to run) |
| `ingest/origin/` | `FilesOrigin`. Planned: `CommandOrigin` |
| `ingest/convert/` | Planned: `CommandConverter` |
| `ingest/format/` | `MarkdownFormat` with `MarkdownReader`; the row formats `TsvFormat`, `YamlFormat`, `JsonlFormat` with `RowValues`. Planned: `SqlFormat` |
| `ingest/split/` | `HeadingSplitter` |
| `ingest/map/` | `TemplateMapper`, `Template` (the `{value}` language), `TemplateCheck` (templates against the value names a format declares), `TextParts` (splitting a long text) |
| `ingest/exec/` | Planned: running an external command — argument array, timeout, output capture; shared by `CommandOrigin` and `CommandConverter` |
| `index/` | `Analyzers`, `IndexFields`, `CollectionIndexer` (build and swap), `Manifest`, `CollectionStore` (built collections under the data directory) |
| `search/` | `SearchService` (query, filters, paging, snippets, limits), `SearchHit`, `SearchPage` |
| `cli/` | `RagIndexCommand`, `RagSearchCommand`, dispatched by the main class before the server starts |
| `tools/` | One `@Service` per tool group: `CollectionTools`, `SearchTools` |
| `api/` | Response records with `@Schema` |
| `config/` | `RagMcpProperties`, `RagConfig` (the registry bean among others), `McpServerConfig` |

Planned: `embedding/` (model interface, ONNX implementation, cache) in stage 3.

How the pipeline is wired:

- `collections/` reads a source entry into a definition with the raw settings of each step.
  `StepRegistry.checked` resolves the step names and reports a setting that does not fit as the
  configuration error of the collection; every caller of `CollectionsLoader.load` applies it.
- `CollectionIndexer` asks `SourcePipeline` for items and for the records of an item and names no
  step implementation.
- A step implementation is a `@Component`; the registry is a bean that receives every step bean
  (D19). The step interfaces name no Spring type. `StepRegistry.builtIn()` gives the same steps
  without a context for unit tests, and `StepBeansTest` fails when the two sets differ.
- `rag-index` starts the application context with `spring.ai.mcp.server.enabled=false` and
  `spring.ai.mcp.server.annotation-scanner.enabled=false`, so the stdio transport is not opened;
  `rag-search` runs without Spring (D13).
- Adding a format: a class in `ingest/format/` implementing `Format` and annotated `@Component`,
  a line in `StepRegistry.builtIn()`, synthetic files in `testdata/` (D20), the format's section
  in `docs/collections.md`. Nothing else changes.

## 4. Stages

Each stage ends with `gradlew build` passing, the user documents updated and the stage marked
done here. A stage is taken only when the user asks for it.

| # | Stage | Result | State |
|---|---|---|---|
| 0 | Scaffold | Build, stdio server, `listCollections` stub, documents | done 2026-11-14 |
| 1 | Markdown collection, lexical search | `collections.json` loader; `markdown` reader with front matter; chunker; Lucene index and manifest; `rag-index` and `rag-search` commands; tools `listCollections`, `search`, `getDocument`; response limits | done 2026-11-14 |
| 2 | Source pipeline and remaining formats | The five steps as interfaces with a registry; `tsv`, `yaml`, `jsonl`, `sql`; commands as origin and as `convert`; `where` conditions; `lookup` group | ⬜ |
| 3 | Vectors and hybrid search | ONNX embedding model; vector field; `vector` and `hybrid` modes with RRF; model check against the manifest; embedding cache | ⬜ |
| 4 | Measurement | Question set with expected sources; recall and rank per mode; tuning of chunk size and limits for a small-context model; decision on a reranker (D12) | ⬜ |
| 5 | Delivery | Allowed-collections setting; atomic rebuild and `rebuildCollection`; streamable HTTP transport for MCP; Docker image; delivery procedure for indexes and models | ⬜ |
| 6 | Convenience and release | `browse` and `debug` groups; CI and release workflow; registry entries; third-party notices | ⬜ |

Stage 2 in more detail, as the next one:

0. Done 2026-11-14: `split` and `map` on every source (D14), so the formats below only have to
   name their values and their defaults.
1. Done 2026-11-15: the pipeline of section 3.1 in the packages of section 3.6, with the existing
   Markdown reader, chunker and mapper moved behind the step interfaces and no change of
   behavior. `format` is chosen by name, or by file extension when absent; the format gives the
   default `include`, the default `map` templates and the value names a template may use.
   Named readers (D18), done 2026-11-15: `CollectionsLoader` reads the top-level `readers`
   object and resolves a source that names a `reader` before `StepRegistry.checked` sees it; a
   key of the source replaces the reader's key whole.
2. Done 2026-11-15: row formats, no splitter: `tsv` (a row; values `{column.<name>}` from the
   header row), `yaml` (the file, or an item of the list named by `split.list`, D22), `jsonl` (a
   line; values `{record.<key>}`). They have no default `text`, so `map.text` is required.
   Checked on the first corpus: 11 TSV files gave 5,216 records, 37 YAML files 148 records,
   1 JSONL file 20 records.
3. `sql` (D21): one file is one unit. The leading block of `--` comment lines is the header: a
   line `-- key: value` gives the value `{header.<key>}`, the whole block without the dashes is
   `{comment}`, the statement after it is `{sql}`, and `{body}` is the file as it is. Defaults:
   the document key is `{header.id|document.path}`, the title is `{header.purpose|path.name}`, the
   text is `{body}`. No SQL parsing.
4. Commands (D10): `ingest/exec/`, the `command` origin and the `command` converter; argument
   array, no shell, timeout, working directory of `collections.json`. `docs/collections.md` gets
   a Pandoc example.
5. `where`: selection of records by field values, applied after `map`.
6. The `lookup` tool group for exact lookups in catalog collections.

Every format step adds synthetic files of the same shape as the first real corpus to `testdata/`
and is checked by building a collection from that corpus (D20). The corpus has Markdown, YAML,
TSV, SQL and JSONL files, and these are the formats of this stage.

`docbook` is not in this stage (D17). If it is written, it is a StAX reader of nested `section`
elements into the section tree, with `xml:id` and the page name from `<?dbhtml filename?>` as
values.

Left out of stage 1 and not assigned to a stage: the source revision in the manifest (it needs a
decision on calling `git`, which invariant 6 of `AGENTS.md` does not allow today) and user
word-form rules (`docs/decisions.md` P9).

Observed while verifying stage 1, for stage 4: a lexical query phrased differently from the text
ranks an unrelated record first; Spring AI repeats the message of a tool error twice in the
response text.

## 5. Open questions

- How the assistant service connects to this server and to other MCP servers (D1).
- Which model and API the target site provides; whether it has an embedding endpoint.
- Built-in converters for PDF and Office sources (code exists in `mail-mcp-server` `convert/`), if
  a `convert` command with Pandoc turns out not to be enough (D17).
- When the DocBook reader is written (D17).
- The license (P1) and the other proposals in `docs/decisions.md` section 3.
- Legacy HTTP API (D2), if a client needs it.
