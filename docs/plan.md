# docs/plan.md — idea, architecture and stages

Read this for any design question and when starting or finishing a stage. Decisions and their
reasons are in `docs/decisions.md` (cited here as D1…D71; agent proposals as P28 onwards).

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

Stages 1 to 3, 2026-11-15: collections described in `collections.json` are
built into one Lucene index each and searched lexically, by vectors or by both fused; every source
configures how it is cut (`split`) and how a unit becomes a record (`map`), and a source is built
by the five-step pipeline of section 3.1, with Markdown, TSV, YAML, JSONL, SQL and PDF as formats and
external commands as an origin and as `convert` steps; a source may take its reading keys from a
named reader and select its records with `where`; catalog records are found by an exact value of
their key fields; a collection with `vectors` gets an embedding of every record from an in-process
ONNX model, used through Spring AI's `EmbeddingModel` (D29); search filters take Spring AI filter
expressions next to the map of field to value (D30); the records of a source reach the index as
Spring AI documents (D31); PDF files are read by Spring AI's PDF reader (D32).

- Implemented: the collections file loader with named readers; the step interfaces, the
  registry and `SourcePipeline` in `ingest/`; the origins `files` and `command`, the `command`
  converter, the formats `markdown`, `tsv`, `yaml`, `jsonl`, `sql` and `pdf`, the heading splitter and
  the template mapper as step beans; `where`; the index with its manifest; BM25 search with
  filters, filter expressions and snippets; exact lookup by key fields; server-side response limits; the commands
  `rag-index`, `rag-search`, `rag-eval` and `rag-model` (D70), each in a Spring context without the MCP server (D42); the tool groups
  `collections` (`listCollections`), `search` (`search`, `getDocument`, the record resource),
  `lookup` (`lookup`) and `admin` (`rebuildCollection`);
  the `models` object and `vectors` per collection, the ONNX embedding model, the vector field, the
  modes `lexical`, `vector` and `hybrid` with reciprocal rank fusion, the model check against the
  manifest and the embedding cache. User documents: `docs/collections.md`, `docs/search.md`.
- Verified by `gradlew build` (198 tests on the synthetic knowledge in `testdata/`, vectors from a
  deterministic test model; one of them runs the real model and only when `RAG_MCP_TEST_MODEL_DIR` is
  set) and by running the jar: `rag-index` writes nothing but its report, and a stdio session lists
  the four tools, answers `lookup` and `search` in every mode and drops `lookup` with
  `RAG_MCP_TOOLS_LOOKUP=false`. Every format, `where`, `lookup` and the vectors were checked by
  building collections from the first real corpus (numbers in section 4, stages 2 and 3). Requirement files without front matter (stage 1: 141 files, 601 records) have not
  been rebuilt on the pipeline.
- Measured: one question set of the first corpus with `rag-eval` (stage 4, section 4); tuning is
  not done.

In section 3, the tool groups after `lookup`, a provider starter for a remote embedding endpoint, the allowed
collections and the rows of section 3.7 marked "not implemented" are the target design.

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
| `format` | text → section tree or rows; a binary format reads the file instead (D34) | `format`, `split.list` for YAML | tree: `markdown` (`docbook` later, D17); rows: `tsv`, `yaml`, `jsonl`, `sql`, `pdf` (Spring AI reader, D32) |
| `split` | section tree → units | `split` | one: by heading level and pattern, optionally into table rows (D37) |
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
described in `docs/collections.md`, which owns the format: `models`, `readers`, `description`,
`vectors` and `sources` with `path` or `command`, `timeoutSeconds`, `include`, `exclude`,
`convert`, `format`, `split`, `map`, `where` and `reader`. Everything in the sketch below is
implemented except `language`, which nothing reads yet:

```json
{
  "models": {
    "e5-small": { "type": "onnx", "queryPrefix": "query: ", "passagePrefix": "passage: ",
                  "maxTokens": 512 }
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
      "vectors": { "model": "e5-small" }
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
- A collection without `vectors` is lexical only (catalogs of codes and names). The files of a
  model are in `<data-dir>/models/<name>/` on every machine (D26).
- Relative paths resolve against the directory of `collections.json`. String values may reference
  `${VAR}`.
- A file with commands is executable configuration: do not use one from an untrusted
  origin.

### 3.3 Collection directory and manifest

```
<data-dir>/
  collections.json            build side only
  models/<model>/             model.onnx and tokenizer.json (D25)
  cache/embeddings/           vectors by hash of model and text, a Lucene index (D27)
  cache/djl/                  native code of the tokenizer library
  logs/
  <collection>/
    manifest.json             name, description, field schema, analyzer, model id and its
                              parameters, chunking parameters, source revision, build time, counts
    index/                    Lucene index
```

Implemented: `<collection>/manifest.json` (schema, name, description, build time, server version,
counts, field names, key fields, sources with their chunking parameters, and for a collection with
vectors the model name, parameters, vector size, file hashes and the number of records cut to the
input limit) and `<collection>/index/`. The analyzer description and the source revision are not
in the manifest.

- The server serves every collection directory it finds (D49). It reads the manifest, not
  `collections.json` (D7).
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
- Query words are optional and ranked by BM25, with the breadcrumb weighted twice the text (D56).
- A page holds the best section of each document unless `perDocument` asks for more (D38): the
  group of a record is stored in the index (`IndexFields.STORED_GROUP`), the document key for a
  section and the record id for a row; candidates are ranked deeper until the page is full.
- Modes: `lexical` (BM25), `vector` (kNN on the `vector` field, cosine), `hybrid` — both, fused
  by RRF (D12, D26) with `TopDocs.rrf(topN, k, TopDocs[])` of Lucene, present in the 10.5.2 we
  use (the release that introduced it is to verify). Collections of one model form one kNN list,
  with doc ids moved to the reader of all searched collections; the lists and the BM25 list are
  fused by doc id. The query vector is computed by the model of the manifest, loaded once per
  process by `EmbeddingModels`.
- A call may search several collections; results carry their collection and the mode used.
- Responses are bounded: a default and a maximum number of results and a total size cap set by the
  server; a search returns snippets, `getDocument` returns the full record (D54).

Tool groups, each switched by `RAG_MCP_TOOLS_<GROUP>` (D53):

| Group | Tools | Stage |
|---|---|---|
| `collections` | `listCollections` | done |
| `search` | `search`, `getDocument`; the resource `rag://{collection}/{id}` and its completion (D48) | done |
| `lookup` | `lookup`: exact lookups by the key fields of catalog collections (D23) | done |
| `admin` | `rebuildCollection` (D50) | done |
| `browse` | list by filters, facets, related records | 6 |
| `debug` | analyzer output, score explanation, mode comparison | 6 |

### 3.5 Environments

| | Site with a weak local model | Development with a strong model |
|---|---|---|
| Tool groups | `collections`, `search`, `lookup` | all |
| Limits | few results, snippets, small size cap | defaults |
| Instances | one per audience, each with its own data directory (D8, D49) | one |
| Index | delivered ready (D7) | built in place, `rebuildCollection` available |
| Embedding model | in-process, same as at build time (D11) | in-process |

### 3.6 Packages

Root `ru.it_spectrum.ai.rag.mcp`. Dependencies flow downward only:

```
tools/ ──► search/ ──► index/
  └──────► collections/, ingest/    (listCollections shows collections that are not built)
cli/ ───► collections/, ingest/, index/, search/, embedding/, eval/
eval/ ──► search/                              (question sets, references, measures)
index/ ──► document/ ──► ingest/ ──► collections/   (build: list, convert, parse, split, map, write)
search/, index/ ──► embedding/                (embed queries and records; the cache)
api/     — MCP wire records returned by tools
config/  — Spring wiring, properties, MCP customizer
```

| Package | Responsibility |
|---|---|
| `collections/` | The shape of `collections.json` and nothing about formats: `CollectionsLoader`, `CollectionDefinition`, `SourceDefinition`, `SplitSettings`, `MapSettings`, name rules, `${VAR}` placeholders |
| `ingest/` | The contracts of the build pipeline (D15): the step interfaces `Origin`, `Converter`, `Format`, `Splitter`, `Mapper`; the shapes `SourceItem`, `SourceText`, `ParsedDocument` (`SectionTree` with `Section`, or `Rows`), `Unit`, `ChunkRecord`; what a format declares (`MapDefaults`, `ValueNames`); `StepRegistry` (implementations by name, and the check of a collection's sources); `SourcePipeline` (the steps of one source, checked and ready to run) |
| `ingest/origin/` | `FilesOrigin`, `CommandOrigin` |
| `ingest/convert/` | `CommandConverter` |
| `ingest/format/` | `MarkdownFormat` with `MarkdownReader`; the row formats `TsvFormat`, `YamlFormat`, `JsonlFormat` with `RowValues`, `SqlFormat`, and `PdfFormat` (Spring AI's `PagePdfDocumentReader`, reads the file) |
| `ingest/split/` | `HeadingSplitter`, `TableRows` (the rows of Markdown tables, D37) |
| `ingest/map/` | `TemplateMapper`, `Template` (the `{value}` language), `TemplateCheck` (templates against the value names a format declares), `TextParts` (splitting a long text) |
| `ingest/exec/` | `CommandRunner`: running an external command — argument array, timeout, output capture; shared by `CommandOrigin` and `CommandConverter` |
| `document/` | The boundary to Spring AI's `Document` (D31): `RecordDocuments` (the one mapping between a record and a document), `SourceDocumentReader` (a source, or one item of it, as a `DocumentReader`), `RecordContentFormatter` (what a model sees of a record, D44) |
| `index/` | `Analyzers`, `IndexFields`, `CollectionIndexer` (build and swap; writes the documents of each item through a `DocumentWriter`), `Manifest`, `CollectionStore` (built collections under the data directory) |
| `search/` | `SearchService` (query, filters, modes and fusion, paging, snippets, limits), `FilterQueries` (Spring AI filter expressions to Lucene queries), `SearchMode`, `SearchHit`, `SearchPage` |
| `embedding/` | Models as Spring AI `EmbeddingModel` (D29): `OnnxEmbeddingModel` (ONNX Runtime and the DJL tokenizer, pooling), `EmbeddingModels` (models by type, loaded once, checked against a manifest), `ModelType` with `OnnxModelType` and `BeanModelType` (D43), `VectorModel` (a model with its name, prefixes and identity; the only thing the indexer and search call), `ModelParameters`, `ModelInfo`, `Embeddings` (vectors and which inputs were cut, read from a response), `CountBatchingStrategy` (D44), `EmbeddingCache`, `ModelUnavailableException` |
| `eval/` | `QuestionSet` (a JSONL question set), `References` (which records a reference names), `Evaluation` (ranks per mode and the measures) |
| `cli/` | `RagIndexCommand`, `RagSearchCommand`, `RagEvalCommand`, dispatched by the main class before the server starts; `Cli.context` starts the application context without the MCP server (D42) |
| `tools/` | One `@Service` per tool group: `CollectionTools`, `SearchTools`, `LookupTools`, `AdminTools`; `RecordResources` (the record resource and its completion, D48) |
| `api/` | Response records with `@Schema` |
| `config/` | `RagMcpProperties`, `RagConfig` (the registry bean among others), `McpServerConfig`, `TransportSettings` (stdio or HTTP, D45) |

How the pipeline is wired:

- `collections/` reads a source entry into a definition with the raw settings of each step.
  `StepRegistry.checked` resolves the step names and reports a setting that does not fit as the
  configuration error of the collection; every caller of `CollectionsLoader.load` applies it.
- `CollectionIndexer` asks `SourcePipeline` for items and for the records of an item and names no
  step implementation.
- A step implementation is a `@Component`; the registry is a bean that receives every step bean
  (D19). The step interfaces name no Spring type. `StepRegistry.builtIn()` gives the same steps
  without a context for unit tests, and `StepBeansTest` fails when the two sets differ.
- Every command starts the application context through `Cli.context`, with
  `spring.ai.mcp.server.enabled=false` and `spring.ai.mcp.server.annotation-scanner.enabled=false`,
  so the stdio transport is not opened (D42).
- A model type is a `ModelType` bean (D43): `OnnxModelType` (files under `models/<name>/`) and
  `BeanModelType` (the Spring AI `EmbeddingModel` bean named as the model). `EmbeddingModels`
  receives every type bean; `StepBeansTest` checks the set.
- Adding a format: a class in `ingest/format/` implementing `Format` and annotated `@Component`,
  a line in `StepRegistry.builtIn()`, synthetic files in `testdata/` (D20), the format's section
  in `docs/collections.md`. Nothing else changes.

### 3.7 Spring AI

The server builds on Spring AI wherever it has an abstraction that fits (D28). Before writing an
interface or a mechanism, look for one in Spring AI; a case where it does not fit is recorded in
D28 with its reason.

| Area | Spring AI | State |
|---|---|---|
| MCP server, tools | `spring-ai-starter-mcp-server`, `@McpTool` | used |
| Embedding models | `EmbeddingModel` of `spring-ai-model`; the ONNX model is an own implementation, a remote endpoint a Spring AI one (D29, D67) | used |
| Search filters | `Filter.Expression` and its text parser, `spring-ai-vector-store` (D30); `search/FilterQueries` turns an expression into a Lucene query | used |
| Records at the boundaries | `Document`, `DocumentReader`, `DocumentWriter` of `spring-ai-commons` (D31); `document/` maps records and reads sources, the indexer writes through a `DocumentWriter` | used |
| PDF, Office, HTML sources | the Tika, PDF and Jsoup document readers as formats (D32) | PDF used (`pdf`); Tika and Jsoup not added |
| Model types as beans configured by Spring AI properties | auto-configured `EmbeddingModel` beans: the model type `bean` (D43); every command in a Spring context (D42); an OpenAI-compatible endpoint by `spring-ai-autoconfigure-model-openai` (D67) | used; the remote model is off until configured |
| Embedding input and batches | `Document` content for `MetadataMode.EMBED` by a `ContentFormatter` (`document/RecordContentFormatter`), batches by a `BatchingStrategy` (`embedding/CountBatchingStrategy`), the model called through `EmbeddingRequest` (D44) | used |
| Embedding observations | the observation conventions of Spring AI embedding models (D46) | used; no metrics backend bundled |
| MCP over HTTP | the streamable HTTP transport of the Spring AI MCP server starter (D45), `RAG_MCP_TRANSPORT=http` | used |
| MCP resources and completion | `@McpResource` and `@McpComplete`: a record as `rag://{collection}/{id}`, its values completed (D48) | used; progress notifications not (D66) |
| Storage, search, fusion | none: Lucene (D28 a, b) | own |
| Markdown reading, splitting, the five steps | none (D28 d, e) | own |

Dependencies these decisions allow: `spring-ai-model`, `spring-ai-vector-store` (brings nothing
the build did not have), `spring-ai-pdf-document-reader` (PDFBox 3, four modules) and
`spring-ai-starter-mcp-server-webmvc` (Tomcat and Spring MVC, 18 modules, D45) and
`spring-ai-openai` with `spring-ai-autoconfigure-model-openai` (26 modules, D67), declared;
`spring-ai-jsoup-document-reader` (two modules) and `spring-ai-tika-document-reader` (107 modules)
each with the format that needs it.

- A model type is a `ModelType` bean: it returns an `EmbeddingModel` and the `ModelInfo` that
  identifies its vectors (file hashes, or the bean name and response model of a `bean` model).
  Nothing else changes when a type is added.
- A model reports the inputs it cut under the response metadata key `truncated`
  (`Embeddings.TRUNCATED`); a model that does not is taken to have cut nothing.
- A record is a `Document` with its id and text; its fields are metadata entries under their own
  names, each a list of strings, and its other parts are metadata under `rag.document`,
  `rag.title`, `rag.breadcrumb`, `rag.source` and `rag.ordinal`. A document from elsewhere becomes
  a record the same way: its scalar metadata become fields.
- A Spring AI reader is used inside a format class of `ingest/format/` and gives units, like any
  other library a format uses; the step interfaces and the shapes of `ingest/` name no Spring AI
  type. A reader of binary files makes its format `readsFile` (D34).
- `CollectionIndexer` reads each item with `SourceDocumentReader` and writes it through its
  `DocumentWriter`, which checks unique ids and collects the field and key names; a reader or a
  writer throws `UncheckedIOException` where the interfaces allow no `IOException`.
- The `filters` map of `search` is turned into a Spring AI expression (equality joined by "and")
  and joined to `filterExpression` by "and", so one conversion serves both.
- The indexer embeds the `Document`s it writes: `RecordContentFormatter` gives the passage prefix,
  the breadcrumb, a blank line and the text for `MetadataMode.EMBED`, and that string is also the
  key of the embedding cache. `VectorModel.embed` batches the documents and calls the model per
  batch with an `EmbeddingRequest`, so the response metadata that says which inputs were cut is
  kept; `EmbeddingModel.embed(documents, …)` would embed `getText()` and drop it.
- `EmbeddingModel.call` throws no checked exception; `VectorModel` turns a failure into the
  `IOException` the indexer and search expect.

## 4. Stages

Each stage ends with `gradlew build` passing, the user documents updated and the stage marked
done here. A stage is taken only when the user asks for it.

| # | Stage | Result | State |
|---|---|---|---|
| 0 | Scaffold | Build, stdio server, `listCollections` stub, documents | done 2026-11-14 |
| 1 | Markdown collection, lexical search | `collections.json` loader; `markdown` reader with front matter; chunker; Lucene index and manifest; `rag-index` and `rag-search` commands; tools `listCollections`, `search`, `getDocument`; response limits | done 2026-11-14 |
| 2 | Source pipeline and remaining formats | The five steps as interfaces with a registry; `tsv`, `yaml`, `jsonl`, `sql`; commands as origin and as `convert`; `where` conditions; `lookup` group | done 2026-11-15 |
| 3 | Vectors and hybrid search | ONNX embedding model; vector field; `vector` and `hybrid` modes with RRF; model check against the manifest; embedding cache | done 2026-11-15 |
| 3a | Spring AI alignment (section 3.7) | `EmbeddingModel` as the model interface (D29); filter expressions (D30); `Document` at the boundaries (D31); Spring AI readers as formats (D32) | D29–D31 done 2026-11-15; D32 for PDF done 2026-11-15, Tika and Jsoup when a corpus needs them |
| 3b | Spring AI alignment, second pass (section 3.7) | Records embedded as documents (D44); every command in a Spring context (D42), then model types as Spring AI beans (D43); observations of the in-process model (D46). The HTTP transport of D45 belongs to stage 5 | done 2026-11-15 (D42–D46; D45 ahead of stage 5) |
| 4 | Measurement | Question set with expected sources; recall and rank per mode; tuning of chunk size and limits for a small-context model; decision on a reranker (D12) | in progress: `rag-eval` done 2026-11-15 |
| 5 | Delivery | Atomic rebuild and `rebuildCollection` (both done 2026-11-15); streamable HTTP transport for MCP, Spring AI's (D45, done ahead of the stage); delivery procedure for indexes and models (`docs/collections.md`, "Delivering collections"); Docker image not needed for now (D51) | done 2026-11-15 |
| 6 | Convenience and release | `browse` and `debug` groups; CI and release workflow; registry entries; third-party notices | ⬜ |

Stage 4, in progress:

1. Done 2026-11-15: `rag-eval` (D64, D65), the measuring command; user description in
   `docs/search.md`, "Measuring search". On the question set of the first corpus (20 questions:
   10 expect a Markdown document, the others tables by `data:`, requirements by `spec:` and
   symptoms by `symptom:`) it reproduces the check of stage 3 on the Markdown collection: MRR 0.80,
   0.60 and 0.85 for lexical, vector and hybrid. With a collection of the 16 YAML table
   descriptions (29 records, 19 of them longer than 512 tokens) and `--ref data=table`, 12
   questions are measured: MRR 0.71, 0.50 and 0.71, recall@10 0.67, 0.56 and 0.56; the 9 `spec:`
   and 8 `symptom:` references then named nothing indexed.
2. Done 2026-11-15: `spec:` references stay unreachable (D36); symptoms are indexed as table
   rows (D37). With the 35 symptoms as a third collection and `--ref symptom=number`, 17 of 20
   questions are measured: MRR 0.67, 0.53 and 0.66, recall@10 0.69, 0.69 and 0.72 for lexical,
   vector and hybrid; 15 of 17 are found within 10 in every mode.
3. Done 2026-11-15, first tuning pass on the same 17 questions (documents, table descriptions,
   symptoms; hybrid MRR 0.66, 15 of 17 within 10):
   - `split.maxChars` 1000, 1500 and 2500 for the documents, and 1000, 1500 and 2500 for the table
     descriptions, move hybrid MRR within 0.65–0.67 and recall@10 within 0.66–0.75. Fewer records
     cut at 512 tokens (123 → 40 → 0 for the documents) changes no rank that matters. The default
     2500 stays (D39).
   - At depth 100 every measured question is found in every mode; the misses at 10 are ranks 11–38.
     They are pushed down by other records of the same documents: several sections of one process
     map fill the top. Keeping only the best record of each document (simulated on the same
     searches) puts all 17 within 10 in hybrid and vector and raises hybrid MRR to 0.69 (now D38).
   - A response of 10 hits carries about 4,300 characters of snippets (median; at most 5,100), 5
     hits about 2,000; a snippet is 380 characters in the median and at most about 600. Settings
     for a small-context model follow from that (D40, `docs/search.md`).
   - Hybrid ranks like lexical here, and vector is weaker (MRR 0.53). 17 questions cannot show a
     difference of a few hundredths, so a reranker is not decided on them (D41).
4. Done 2026-11-15: one section per document (D38). Measured with the server: hybrid 17 of 17
   within 10, MRR 0.69, recall@10 0.76; lexical 16 of 17, 0.69, 0.75; vector 17 of 17, 0.55,
   0.76. A limit of 2 is no better than none (hybrid 15 of 17).
5. Next: more questions in the set of the first corpus (at least 50 measured, D41), then the
   reranker question again.

Stage 3, done 2026-11-15 (D24–D27):

- Dependencies: ONNX Runtime 1.20.0 (pinned because later versions do not load on Windows with
  the C++ runtime JDKs ship, D24) and DJL Hugging Face tokenizers 0.40.0; the jar manifest has
  `Enable-Native-Access: ALL-UNNAMED`, so the JVM prints no warning when they load native code.
- Model: `multilingual-e5-small` FP32 (D25); SHA-256 of `model.onnx` and `tokenizer.json` matched
  the hashes Hugging Face publishes.
- Checked on the first corpus: the Markdown collection of 239 files gave 1,031 records with 384
  dimensions in 61 s on a 16-thread desktop CPU, 123 records longer than 512 tokens; a rebuild from
  the cache took 3.2 s. On the 10 questions of its question set that expect a Markdown document,
  the expected document was first in 7 (lexical), 4 (vector) and 8 (hybrid), and within the top
  5 in 9 for each mode; MRR 0.80, 0.60 and 0.85. `rag-search` takes 0.8 s per lexical query and
  2.7 s per vector or hybrid query, JVM start and model load included. A collection served from a
  data directory without its model answers `lexical` and reports the missing model for `vector`
  and `hybrid`.

Stage 2 in more detail:

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
3. Done 2026-11-15: `sql` (D21): one file is one unit. The leading block of `--` comment lines is
   the header: a line `-- key: value` gives the value `{header.<key>}`, the whole block without the
   dashes is `{comment}`, the statement after it is `{sql}`, and `{body}` is the file as it is.
   Defaults: the document key is `{header.id|document.path}`, the title is
   `{header.purpose|path.name}`, the text is `{body}`, the id is `{document.key}`. No SQL parsing.
   Checked on the first corpus: 9 query files gave 12 records, 8 catalog generators with a free
   comment 9 records.
4. Done 2026-11-15: commands (D10): `ingest/exec/`, the `command` origin and the `command`
   converter; argument array, no shell, timeout (default 60 seconds), working directory of
   `collections.json`, output read as UTF-8. A `convert` step names its converter by a key and is
   resolved by the registry; a converter prepares a checked step from its settings. `{file}` in
   an argument is the path of the item's file; without it the text so far goes to standard
   input. The manifest keeps `command` and `convert`. `${VAR}` is resolved in `path` only.
   Checked with `java` as the command in the tests; Pandoc is a documented example only.
5. Done 2026-11-15: `where`: selection of records by field values, applied after `map` in
   `SourcePipeline`; equality and `not`, compared as filters compare. The manifest keeps the
   conditions. Checked on the first corpus: 222 Markdown files gave 133 documents without one
   `kind`, 5 with another.
6. Done 2026-11-15: the `lookup` tool group (D23): `map.keys` names the key fields of a source,
   their values are indexed as one exact lower-cased term each in a field of their own, and the
   manifest lists the keys; `lookup` and `rag-search --lookup` match a value against them.
   Checked on the first corpus: 6 catalog files gave 3,416 records, and a parameter code in
   either case, a message text, a job class and a menu id and address were each found.

Every format step adds synthetic files of the same shape as the first real corpus to `testdata/`
and is checked by building a collection from that corpus (D20). The corpus has Markdown, YAML,
TSV, SQL and JSONL files, and these are the formats of this stage.

`docbook` is not in this stage (D17). If it is written, it is a StAX reader of nested `section`
elements into the section tree, with `xml:id` and the page name from `<?dbhtml filename?>` as
values.

Left out of stage 1 and not assigned to a stage: the source revision in the manifest (it needs a
decision on calling `git`, which invariant 6 of `AGENTS.md` does not allow today) and user
word-form rules (`docs/decisions.md` D58).

Observed while verifying stage 1, for stage 4: a lexical query phrased differently from the text
ranks an unrelated record first; Spring AI repeats the message of a tool error twice in the
response text. Observed in stage 3: 12% of the Markdown records exceed the 512-token input of E5,
so their ends have no vector of their own; `split.maxChars` is a stage 4 tuning question. Lucene
logs that the incubating Vector API is not enabled (`--add-modules jdk.incubator.vector` would
speed up kNN); not measured.

## 5. Open questions

- How the assistant service connects to this server and to other MCP servers (D1).
- Which model and API the target site provides; whether it has an embedding endpoint (a Spring AI
  `EmbeddingModel` bean as a model type, D11, D43).
- When JDKs ship `msvcp140.dll` 14.40 or later, so that ONNX Runtime can move past 1.20 (D24).
- Whether pages are enough for real PDF files or sections by outline are needed (D35), and when
  Tika or Jsoup is added (D32).
- When the DocBook reader is written (D17).
- Legacy HTTP API (D2), if a client needs it.
