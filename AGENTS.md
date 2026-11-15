# lucene-rag-server — instructions for agents

An MCP server that indexes prepared project knowledge (Markdown, TSV, YAML, JSONL, SQL) into
Apache Lucene and serves hybrid search over it: BM25 plus dense vectors kept in the same Lucene
index. It retrieves; it does not generate answers. User-facing overview: `README.md`.

**Current status:** stages 1 to 3 of `docs/plan.md` section 4 are implemented: Markdown, TSV,
YAML, JSONL, SQL and PDF collections with configurable `split` and `map`, built by the five-step source
pipeline in `ingest/`, external commands as an origin and as `convert` steps, named readers,
`where`, vectors from an in-process ONNX model with an embedding cache, `lexical`, `vector` and
`hybrid` search, the `rag-index`, `rag-search` and `rag-model` commands and the tools `listCollections`,
`search`, `getDocument` and `lookup`. Embedding models are used through Spring AI's
`EmbeddingModel`, search filters take Spring AI filter expressions, records reach the index as
Spring AI documents and PDF is read by Spring AI's reader (`docs/plan.md` section 3.7).
Measurement (stage 4) is in progress: `rag-eval`, a first tuning pass and one section per
document in search results are done; results and open proposals are in `docs/plan.md` section 4.
A second pass of the Spring AI alignment (stage 3b: D42–D46) is implemented, the HTTP transport
of MCP among it. Stage 5 (delivery) is done: a rebuild swaps a collection in whole,
`rebuildCollection` rebuilds through MCP, a record is also an MCP resource, and the delivery of
collections and models is described in `docs/collections.md`; no Docker image (D51).

## Repository contents

| Path | What it is |
|---|---|
| `README.md` | Project overview for users |
| `AGENTS.md` | This file: rules for agents changing the repository |
| `CLAUDE.md` | One line, `@AGENTS.md`, so Claude Code loads this file. Keep all rules here, not there |
| `docs/plan.md` | Idea and status, architecture and data flow, configuration, the stages of work, open questions |
| `docs/decisions.md` | The user's decisions with reasons, what no change may add without one, unconfirmed agent proposals |
| `docs/collections.md`, `docs/search.md` | User documents: the collections file, sources, chunking, building and moving a collection; query syntax, filters, limits, the `rag-search` and `rag-eval` commands |
| `testdata/` | Synthetic knowledge of an invented billing system for the tests; described in `testdata/README.md` |
| `src/main/java/ru/it_spectrum/ai/rag/mcp/` | Server source; packages in `docs/plan.md` section 3.6 |
| `src/test/` | Unit tests; none needs a network |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/` | Gradle build, version, version catalog `gradle/libs.versions.toml`, wrapper |
| `LICENSE`, `NOTICE` | Apache 2.0 license and copyright notice |
| `.local/` | **Ignored by git.** Real environment values. May not exist in your checkout |

## Instruction routing

Read this file first. Then read only the documents whose `When` matches the task.

| Document | When | What it holds |
|---|---|---|
| `docs/plan.md` | any design question; starting or finishing a stage; adding a setting, a format or a tool | status, architecture, configuration sketch, stages, open questions |
| `docs/decisions.md` | any design question; before adding a dependency, a tool, a format or a setting; recording a user decision | decisions with reasons, what not to add without the user, unconfirmed proposals |
| `docs/collections.md` | changing the collections file format, a reader, `split`, `map`, the values a template may use, record ids, `rag-index` output or exit codes, the collection directory layout | the user-facing description; the single source for formats, output and exit codes |
| `docs/search.md` | changing query analysis, ranking, filters, snippets, response limits, a search tool's arguments, `rag-search` or `rag-eval` | the user-facing description of search behavior and limits |

User documents describe what a user sees and does; a change of user-visible behavior updates
them in the same change. Name new engineering documents `docs/<topic>-agent.md`, write them in
English, and register each one in this table. Split a topic out of `docs/plan.md` when its stage is implemented: the plan
then keeps the summary and links to the owner.

## Invariants that apply to every change

1. **No real environment values in tracked files.** The repository is public. Never write real
   host names, IP addresses, logins, customer or internal project names, issue numbers or paths
   into a project's data into any tracked file or commit message. Use neutral examples:
   `docs@billing`, `C:/projects/billing/knowledge`, `example.com`. Real values belong in `.local/`,
   which git ignores. Sibling projects are cited by local path (`C:\git\mail-mcp-server`).
2. **Retrieval only.** No tool generates an answer or calls a chat model. The server returns
   passages with their sources (`docs/decisions.md` D1).
3. **The index is derived.** Everything under `<data-dir>/<collection>/` can be rebuilt from the
   sources and `collections.json`. Never treat it as the only copy of anything, and never write
   to source directories.
4. **Serving needs no sources.** A built collection directory must be usable on a machine that
   has neither the source directories nor `collections.json` (D7). Anything search needs at query
   time goes into the collection's manifest.
5. **Indexed text leaves the machine only when the operator configures it.** The default embedding
   model runs in-process (D11). A remote embedding endpoint is an explicit per-model setting.
6. **External commands run only while building an index** and only as written in
   `collections.json`: an argument array, no shell, with a timeout. No tool argument may reach a
   command line (D10).
7. **Do not push or rewrite history unless asked.** Commit only when the task asks for it. Work
   and commit on `main`; do not create a branch unless the user asks for one.

## Working with the documents

- All project documentation is written **in English**. Keep literal non-English examples where
  they demonstrate language-specific behavior.
- The documents describe the current state, not the history. Replace outdated statements instead
  of appending corrections.
- `docs/decisions.md` section 2 gets a row **only for decisions the user made**. Agent proposals
  go to section 3 until the user confirms them.
- When a stage is accepted, mark it done in `docs/plan.md` section 4 and describe the result in
  the document that owns it, not in the stage list.
- Always use absolute dates in `YYYY-MM-DD` form.
- Mark a fact you have not verified from a primary source "to verify".

## Java code

- The package root is `ru.it_spectrum.ai.rag.mcp` (D4).
- Build on Spring AI (D28): before you write an interface or a mechanism, look for one in Spring
  AI and use it. When it does not fit, say why in D28. What is used and what stays own is in
  `docs/plan.md` section 3.7.
- Stdio transport, the default: nothing but JSON-RPC may be written to `System.out`. Use SLF4J.
  The HTTP transport (`RAG_MCP_TRANSPORT=http`, D45) is switched in `config/TransportSettings`.
- Tool classes are thin `@Service` beans, one per tool group, each with a
  `@ConditionalOnProperty(prefix = "rag-mcp.tools", ...)` switch and a matching line in
  `application.yml` (D5).
- `api/` records are the MCP wire contract. Every field of a record returned from an
  `@McpTool(generateOutputSchema = true)` method carries `nullable = true` and an explicit
  `requiredMode`; the reason is in `C:\git\jdbc-mcp-server\AGENTS.md`, "`@Schema` annotations on
  response records".
- Code copied from a sibling project names its source project and commit in the class Javadoc.
- Tests use the synthetic knowledge under `testdata/` and temporary directories. Never copy text
  from a real project into `testdata/` or a test (invariant 1).
- `testdata/` and the examples in the documents copy the shape of the first real corpus —
  formats, directory layout, front matter keys, column and key structure — with invented content
  (D20). When you add a format or a layout, look at how the real corpus uses it (its location is
  in `.local/` or comes from the user) and make the synthetic files look the same.
- Changing index fields, their analysis or the shape of the manifest raises `Manifest.SCHEMA`; an
  index of another schema is not served.
- No layout of a particular project's files is fixed in code (D14). A reader exposes named
  values; which of them become the id, title, text and fields is the source's `map` block, and
  the defaults of a format are ordinary templates in its `Format.mapDefaults()`.
- A source is built by the five steps of D15 (`docs/plan.md` section 3.1). A new format, origin or
  converter is a step implementation registered by name; do not add a branch for it to the
  indexer or to the collections loader. The recipe is at the end of `docs/plan.md` section 3.6.
- A step implementation is a `@Component` and is also listed in `StepRegistry.builtIn()`, which
  the unit tests use; `StepBeansTest` fails when the two differ. The step interfaces and the
  shapes in `ingest/` name no Spring or Spring AI type other than that annotation; Spring AI's
  `Document` meets the pipeline in `document/` (D31). A step implementation may use a Spring AI
  reader inside its class, as `PdfFormat` does.
- Every command runs inside a Spring context without the MCP server (`Cli.context`, D42); it must
  print nothing but its report to stdout and nothing to stderr on success.
- Unit tests never load a real embedding model: they use `FakeEmbeddingModel` (test sources) through
  `EmbeddingModels`. A test of the real model runs only when `RAG_MCP_TEST_MODEL_DIR` names its
  files, which stay outside the repository.
- A model is a Spring AI `EmbeddingModel` (D29) and is reached only through `embedding/`: the
  indexer and search name `VectorModel` and `EmbeddingModels`, never ONNX Runtime or DJL. A new
  model type is a `ModelType` bean (D43).
- Change dependency versions in `gradle/libs.versions.toml`, not in the build script.

Sibling projects used as references (same author):

| Project | Local path | What to look at |
|---|---|---|
| `mail-mcp-server` | `C:\git\mail-mcp-server` | package `index/` (Lucene analyzers, word forms, snippets, committed readers), `cli/` command dispatch, `docs/storage-agent.md`, `docs/research/RT-07-embeddings.md` |
| `jdbc-mcp-server` | `C:\git\jdbc-mcp-server` | package `connection/` (connections file, `${VAR}` placeholders, lazy registry), tool-group flags in `application.yml` and `tools/` |

## Verification before you finish

1. `.\gradlew.bat build` passes (compile, all tests, `bootJar`).
2. If `.local/` exists, check that none of its real values appear in your diff or commit message.
3. Check that every path and section reference you wrote exists.
4. Commits (only when asked): English, imperative summary line of at most ~72 characters, a body
   that says what changed and why.
