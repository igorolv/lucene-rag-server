# lucene-rag-server — instructions for agents

An MCP server that indexes prepared project knowledge (Markdown, DocBook, TSV, YAML, JSONL) into
Apache Lucene and serves hybrid search over it: BM25 plus dense vectors kept in the same Lucene
index. It retrieves; it does not generate answers. User-facing overview: `README.md`.

**Current status:** scaffold only. The server starts over stdio and exposes one tool,
`listCollections`, which always returns an empty list. Nothing is indexed or searched yet. The
stages are in `docs/plan.md` section 4.

## Repository contents

| Path | What it is |
|---|---|
| `README.md` | Project overview for users |
| `AGENTS.md` | This file: rules for agents changing the repository |
| `CLAUDE.md` | One line, `@AGENTS.md`, so Claude Code loads this file. Keep all rules here, not there |
| `docs/plan.md` | Idea and status, architecture and data flow, configuration, the stages of work, open questions |
| `docs/decisions.md` | The user's decisions with reasons, what no change may add without one, unconfirmed agent proposals |
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

Name new engineering documents `docs/<topic>-agent.md`, write them in English, and register each
one in this table. Split a topic out of `docs/plan.md` when its stage is implemented: the plan
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
7. **Do not push or rewrite history unless asked.** Commit only when the task asks for it.

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
- Stdio transport: nothing but JSON-RPC may be written to `System.out`. Use SLF4J.
- Tool classes are thin `@Service` beans, one per tool group, each with a
  `@ConditionalOnProperty(prefix = "rag-mcp.tools", ...)` switch and a matching line in
  `application.yml` (D5).
- `api/` records are the MCP wire contract. Every field of a record returned from an
  `@McpTool(generateOutputSchema = true)` method carries `nullable = true` and an explicit
  `requiredMode`; the reason is in `C:\git\jdbc-mcp-server\AGENTS.md`, "`@Schema` annotations on
  response records".
- Code copied from a sibling project names its source project and commit in the class Javadoc.
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
