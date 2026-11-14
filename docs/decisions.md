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
| 2026-11-14 | **D9. Source directories are indexed directly.** Pipeline: a format reader produces a tree of sections, one shared chunker cuts the tree into records. Built-in formats: `markdown` (front matter becomes fields), `docbook`, `tsv`, `yaml`, `jsonl`. Format and chunking parameters are written per source in `collections.json` | Prepared knowledge already has structure (headings, front matter); a pre-export step per project is not required. A large single-file DocBook manual is a reader, not a special chunker |
| 2026-11-14 | **D10. A custom chunker is an external command** named in the source entry (`format: command`) that prints JSONL records to stdout. It runs only while building an index | Any language; no interpreter inside the server; nothing extra to install where a ready index is served |
| 2026-11-14 | **D11. Embeddings are computed in-process by default** (ONNX Runtime, a multilingual E5 model). A remote embedding endpoint is a second implementation of the same interface | Environments may have no embedding endpoint at all; indexed text then never leaves the machine; indexes for every environment are built with the same model |
| 2026-11-14 | **D12. Hybrid search fuses BM25 and vector results with reciprocal rank fusion (RRF).** A reranking model is added only if measurements on a question set show it is needed | RRF needs no model and no score calibration; a reranker costs CPU on every query |
| 2026-11-14 | **D13. A `rag-search` command searches from the command line without a model** | On a site with a slow model an engineer still needs fast lookups |

## 3. Agent proposals awaiting a user decision

| Date | Proposal | Reason |
|---|---|---|
| 2026-11-14 | P1. License Apache 2.0. The `LICENSE` file is already in the repository | Same as the sibling public servers |
| 2026-11-14 | P2. Tool groups: `search` (`search`, `getDocument`), `collections` (`listCollections`), `lookup` (exact catalog lookups), `browse` (filters, facets, related records), `debug` (analysis, score explanation), `admin` (`rebuildCollection`) | Mentioned in discussion and not objected to; the exact tool list is fixed per stage |
| 2026-11-14 | P3. Setting names `RAG_MCP_COLLECTIONS` (allowed collections), `RAG_MCP_DEFAULT_COLLECTIONS`, `RAG_MCP_SEARCH_DEFAULT_LIMIT`, `RAG_MCP_SEARCH_MAX_LIMIT`, `RAG_MCP_RESPONSE_MAX_CHARS`. Server limits cap per-call arguments. The three limit settings are implemented under these names (`docs/search.md`) | A weak model cannot overflow its context by asking for more |
| 2026-11-14 | P4. Vectors are cached under the data directory by a hash of model and text | Rebuilds and overlapping collections do not recompute unchanged chunks |
| 2026-11-14 | P5. Collections of one project do not overlap (`docs`, `catalogs`, `data`, `maps`); an audience is a list of collections. Overlapping collections stay possible | No duplicate passages when several collections are searched together |
| 2026-11-14 | P7. Search words are optional (ranked by BM25) instead of all required as in `mail-mcp-server`. Implemented this way | A model passes whole questions; requiring every word of a question finds nothing |
| 2026-11-14 | P8. Short sections are not joined by default (`chunk.minChars` 0) | A joined section loses its own breadcrumb, and the test collection showed the wrong record winning because of it |
| 2026-11-14 | P9. User word-form rules of `mail-mcp-server` (`word-forms.txt`) are not ported until irregular forms are shown to matter here | Keeps the index schema and the manifest simple; vectors may cover the same misses |
| 2026-11-14 | P6. No `VectorStore` implementation of Spring AI; Spring AI supplies the MCP server and the `EmbeddingModel` interface | `VectorStore` has no hybrid search, and clients reach the server through MCP |
