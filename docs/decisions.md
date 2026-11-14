# docs/decisions.md — decisions and boundaries

Read this for any design question, and before adding a dependency, a tool, a source format or a
setting. Section 2 holds decisions the user made; section 3 holds agent proposals the user has not
confirmed. Architecture and stages are in `docs/plan.md`.

## 1. What no change may add without a user decision

- Answer generation, a chat model client or any agent loop (D1).
- An HTTP API for non-MCP clients (D2).
- A profile or preset variable that sets several settings at once (D5).
- Authentication or per-caller access rights inside the server (D8).
- A second storage engine next to Lucene: a vector database, SQLite, a cache server (D6).
- An embedded script interpreter for custom chunking (D10).
- A step between a source and the index that is not one of the five of D15, or a format wired
  into the indexer instead of registered as a step implementation (D15).
- Loading step implementations from jars outside the server (D16).
- A converter program bundled with or required by the server (D17).
- Expressions, functions or conditions in `map` templates beyond `{value}` and `{a|b}` (D14): what
  templates cannot express is the job of a `command` source.
- A reranking model enabled by default (D12).
- A dependency `docs/plan.md` does not name.

## 2. User decisions

| Date | Decision | Reason |
|---|---|---|
| 2026-11-14 | **D1. The server retrieves; it does not answer.** An assistant that talks to users is a separate service that uses this server and other MCP servers (for example `jdbc-mcp-server` for database reads). How that assistant connects to MCP servers is open | One search server serves several clients: assistants, coding agents, people |
| 2026-11-14 | **D2. No HTTP API for legacy (Java 1.7) clients in the first stage.** Add it later if a client needs it | Such clients talk to the assistant, not to this server |
| 2026-11-14 | **D3. The project is public** | Same as the sibling MCP servers |
| 2026-11-14 | **D4. Package root `ru.it_spectrum.ai.rag.mcp`**, modeled on `jdbc-mcp-server` | Consistency with the sibling servers; may change later |
| 2026-11-14 | **D5. One server for every environment; behavior is set by separate environment variables**: tool groups (`RAG_MCP_TOOLS_<GROUP>`, as `JDBC_MCP_TOOLS_*`), allowed collections, response limits. No profile variable for now | A strong large-context model and a weak small-context model need different tool lists and limits; which data is served is independent of which model reads it, so a preset would forbid valid combinations |
| 2026-11-14 | **D6. A collection is an indexed connection to data**, configured like a connection in the sibling servers: one `collections.json` in the data directory, names of the form `<part>@<project>`, and inside each collection its sources, selection conditions, vector switch and model. One Lucene index per collection | The model the user already knows from `connections.json`; every developer can describe their own set of indexed directories; other projects become collections without code changes |
| 2026-11-14 | **D7. Indexes are built on our side and delivered ready.** A collection directory is self-describing and is served without the sources and without `collections.json` | The target site has no source repositories |
| 2026-11-14 | **D8. Audiences are separated by running several instances over the same index directory**, each with its own list of allowed collections. No access rights inside one instance | A full collection set must be available for troubleshooting on a site whose assistant gets only a narrow set; hard isolation without an authentication subsystem |
| 2026-11-14 | **D9. Source directories are indexed directly.** The pipeline is D15. Built-in formats: `markdown` (front matter becomes fields), `tsv`, `yaml`, `jsonl`, `sql` (D21); `docbook` is deferred (D17). Format and chunking parameters are written per source in `collections.json` | Prepared knowledge already has structure (headings, front matter); a pre-export step per project is not required |
| 2026-11-15 | **D10. Custom reading is an external command, and a command is a step, not a format.** It produces text that the source's `format` then parses: run once it is the origin of a source (for example JSONL records of an export), run per file it is a `convert` step (for example to Markdown). It runs only while building an index | Any language; no interpreter inside the server; nothing extra to install where a ready index is served. As a format it could not convert each file and hand the result to a built-in reader |
| 2026-11-14 | **D11. Embeddings are computed in-process by default** (ONNX Runtime, a multilingual E5 model). A remote embedding endpoint is a second implementation of the same interface | Environments may have no embedding endpoint at all; indexed text then never leaves the machine; indexes for every environment are built with the same model |
| 2026-11-14 | **D12. Hybrid search fuses BM25 and vector results with reciprocal rank fusion (RRF).** A reranking model is added only if measurements on a question set show it is needed | RRF needs no model and no score calibration; a reranker costs CPU on every query |
| 2026-11-14 | **D13. A `rag-search` command searches from the command line without a model** | On a site with a slow model an engineer still needs fast lookups |
| 2026-11-14 | **D14. Every source says how to cut and how to lay out**: a `split` block (where units end) and a `map` block (templates that turn the values of a unit into the id, title, breadcrumb, text and filter fields of the standard record). The same two blocks serve every format; without them a format's defaults apply | The first Markdown reader fixed one layout in code: the key only from `id`, fields only from front matter, cuts only by level. The mechanism must fit other projects' files without code changes |
| 2026-11-15 | **D15. A source is built by a fixed pipeline of five steps: origin → `convert` → `format` → `split` → `map`.** The origin lists items (files, or one run of a command); `convert` is a chain of text-to-text steps, empty by default; `format` parses text into a section tree or into rows; `split` cuts a tree into units; `map` lays a unit out as a record. Each step is an interface in its own package, and its implementations are found by name through one registry. A new format or converter is a new implementation, never a change of the pipeline or of the indexer | The plan for stage 2 was a closed list of formats, each wired into the indexer, with `command` as one of them. The same extract–transform–load shape with a fixed contract between steps lets steps be combined (convert to Markdown, then read Markdown) and added without touching the others |
| 2026-11-15 | **D16. Step implementations from external jars are provided for, not implemented.** The registry and the step interfaces are designed so that a jar can add an implementation later; no loader is written until it is needed | Not needed now; the architecture must not rule it out |
| 2026-11-15 | **D17. Pandoc is a documented example of a `convert` command, not a part of the server.** A DocBook reader is not written now: the manuals are converted to Markdown inside their own project and indexed as Markdown. The reader may come later, as a reference implementation of a tree format | One program turns many formats into Markdown, which the server already reads; `mail-mcp-server` is built this way. The server stays without a required external program |
| 2026-11-15 | **D18. Named readers.** A top-level `readers` object in `collections.json` holds reusable sets of `convert`, `format`, `split` and `map`; a source names one with `reader` and may override single keys | The same way of reading is written once and used by several sources and collections, as `models` is for vectors |
| 2026-11-15 | **D19. Step implementations are Spring beans, and the registry is a bean that receives them as lists.** `rag-index` starts the application context with the MCP server switched off instead of running before Spring; `rag-search` keeps running without a context (D13). The registry has a plain constructor, so unit tests build it without a context | Building is rare and takes longer than a context start; one wiring mechanism for the whole project; a step can take properties and other beans (the embedding model, the command runner); a jar of D16 adds its steps as beans |
| 2026-11-15 | **D20. Examples and test data are anonymous in content and real in shape.** The synthetic knowledge under `testdata/` and the examples in the documents reproduce the formats, directory layout, front matter keys and file shapes of the first real corpus the server is built for, with invented text and names. That corpus holds Markdown with YAML front matter, YAML data descriptions, TSV catalogs, SQL files and a JSONL question set; its DocBook manuals live in separate directories. Where the corpus is stays outside the repository (`.local/`) | The first collections are built from that corpus, so a format or layout it has must work on the first real build; the repository is public (D3), so nothing of the corpus itself may be copied |
| 2026-11-15 | **D21. SQL files are read by their own format, `sql`, simple at first**: one file is one record, the leading comment lines are its header, the statement is its text. The statement is not parsed | The first corpus keeps prepared queries as SQL files with a header of `-- key: value` lines; a query is found by its purpose and returned whole |

## 3. Agent proposals awaiting a user decision

| Date | Proposal | Reason |
|---|---|---|
| 2026-11-14 | P1. License Apache 2.0. The `LICENSE` file is already in the repository | Same as the sibling public servers |
| 2026-11-14 | P2. Tool groups: `search` (`search`, `getDocument`), `collections` (`listCollections`), `lookup` (exact catalog lookups), `browse` (filters, facets, related records), `debug` (analysis, score explanation), `admin` (`rebuildCollection`) | Mentioned in discussion and not objected to; the exact tool list is fixed per stage |
| 2026-11-14 | P3. Setting names `RAG_MCP_COLLECTIONS` (allowed collections), `RAG_MCP_DEFAULT_COLLECTIONS`, `RAG_MCP_SEARCH_DEFAULT_LIMIT`, `RAG_MCP_SEARCH_MAX_LIMIT`, `RAG_MCP_RESPONSE_MAX_CHARS`. Server limits cap per-call arguments. The three limit settings are implemented under these names (`docs/search.md`) | A weak model cannot overflow its context by asking for more |
| 2026-11-14 | P4. Vectors are cached under the data directory by a hash of model and text | Rebuilds and overlapping collections do not recompute unchanged chunks |
| 2026-11-14 | P5. Collections of one project do not overlap (`docs`, `catalogs`, `data`, `maps`); an audience is a list of collections. Overlapping collections stay possible | No duplicate passages when several collections are searched together |
| 2026-11-14 | P7. Search words are optional (ranked by BM25) instead of all required as in `mail-mcp-server`. Implemented this way | A model passes whole questions; requiring every word of a question finds nothing |
| 2026-11-14 | P8. Short sections are not joined by default (`split.minChars` 0) | A joined section loses its own breadcrumb, and the test collection showed the wrong record winning because of it |
| 2026-11-14 | P9. User word-form rules of `mail-mcp-server` (`word-forms.txt`) are not ported until irregular forms are shown to matter here | Keeps the index schema and the manifest simple; vectors may cover the same misses |
| 2026-11-14 | P10. The Markdown reader is our own, not `MarkdownDocumentReader` of Spring AI. Checked against the 2.1.0-RC1 source: it starts a new document at every heading of any level, keeps only the current heading as metadata (no parents, so no breadcrumb), joins text nodes into a string without Markdown (tables and lists are flattened), and registers no CommonMark extension, so front matter is read as text. `TokenTextSplitter` cuts by OpenAI token counts, not by structure. Replacing our line scanner with the CommonMark parser that reader uses stays possible if heading edge cases show up | D14 needs the section tree, heading levels, front matter and the original Markdown text |
| 2026-11-14 | P11. `map.pathPattern` gives named groups of the file path as values. Implemented | Fields often live in directory names (`billing-payments/` holds the subsystem), and path segments alone cannot take a part of a name |
| 2026-11-14 | P6. No `VectorStore` implementation of Spring AI; Spring AI supplies the MCP server and the `EmbeddingModel` interface | `VectorStore` has no hybrid search, and clients reach the server through MCP |
