# Setting up the indexes of a project — a guide for agents

You are an agent working in a project whose knowledge should be searchable through
lucene-rag-server: Markdown documents, catalogs in TSV, YAML or JSONL, SQL files, manuals in PDF,
HTML or Office. This guide is the order of work for writing the project's indexes file,
`rag-indexes.yaml`, building it and checking that search finds what it should.

It is a procedure, not a reference. Every key, value and default is described once, in
[indexes.md](indexes.md) (the indexes file, sources, `split`, `map`, building) and
[search.md](search.md) (filters, results, `rag-search`, `rag-eval`); follow the links for details
instead of guessing. The commands below are written as `rag-index …`; run them as
`java -jar lucene-rag-server.jar rag-index …`.

- [1. Find out what the machine already has](#1-find-out-what-the-machine-already-has)
- [2. Survey the knowledge](#2-survey-the-knowledge)
- [3. Decide on the indexes](#3-decide-on-the-indexes)
- [4. Write one source at a time](#4-write-one-source-at-a-time)
- [5. Build and read the report](#5-build-and-read-the-report)
- [6. Check search](#6-check-search)
- [7. Hand over](#7-hand-over)
- [Pitfalls](#pitfalls)

## 1. Find out what the machine already has

The indexes file of the machine belongs to the operator; the file of a project belongs to the
project and is imported by it ([Importing](indexes.md#importing-the-indexes-of-projects)).

- Find the machine's indexes file: `RAG_MCP_INDEXES_FILE`, else `indexes.json`, `indexes.yaml` or
  `indexes.yml` in the data directory (`RAG_MCP_DATA_DIR`, default `~/.lucene-rag-server`).
- Note its `models`: is one marked `"default": true`? Are its files in
  `<data-dir>/models/<name>/` (`rag-model list`)? Without a default model, a project's
  `"vectors": true` is a configuration error.
- Note the index names already taken: names are shared by every file, and a later index with a
  taken name is left out.
- If the project already has a `rag-indexes.yaml` (or `.json`), you are changing it, not
  writing one: read it and the build report first.

Do not edit the machine's file beyond what the user asked. Adding one `import` entry for the
project is the usual change; adding a model, switching on `"commands": true` for an import or
pointing at a remote embedding endpoint is the operator's decision: ask the user.

## 2. Survey the knowledge

Before writing anything, look at the files the way the readers will see them. For each directory
that holds knowledge, write down:

| Question | Why it matters |
|---|---|
| Which formats, by extension, and how many files of each | One source reads one format; the extension picks the reader ([Sources](indexes.md#sources)) |
| For Markdown: front matter keys and their values; is there an `id`; which heading level holds one subject | Decides between the [defaults](indexes.md#writing-markdown-for-the-defaults) and a `split` and `map` of your own |
| For TSV: the header line; for YAML: the top-level keys and which of them is a list of items; for JSONL: the keys of a line; for SQL: the `-- key: value` header | These name the values a `map` can use ([TSV, YAML, JSONL](indexes.md#reading-tsv-yaml-and-jsonl), [SQL](indexes.md#reading-sql)) |
| Which value identifies a unit stably: a front matter `id`, an `**ID:**` line, a code column | Record ids are addressed as `<index>/<record id>`; an id from a file path changes when the file moves |
| Which values a user would filter by: a type of document, a subsystem, a table name | They become `fields`; the same key and spelling across files lets one filter find them all |
| Which values a user would look up exactly: codes, error texts, abbreviations | They become `keys` for `lookup` |
| Drafts, generated files, templates, scripts next to the data | Leave them out with `exclude` or [`where`](indexes.md#selecting-records) |
| How long a typical section is | A record over the model's limit (512 tokens, about 1,500–2,500 characters) is embedded by its beginning only ([What is embedded](indexes.md#what-is-embedded)) |

Read a few whole files of each kind, not only their names. The shape a file seems to have from
its first screen is often not the shape of all of them.

## 3. Decide on the indexes

- **An index is a unit of building and delivery**, with one embedding model or none. Make one
  index per kind of knowledge that is built and changes together: documents in one, catalogs in
  another. Do not make one index per directory or per file.
- **Name** it `<part>@<project>`: `docs@billing`, `catalogs@billing`, `specs@billing`
  ([The indexes file](indexes.md#the-indexes-file)).
- **Set `project`** once at the top of the project's file. Every record then has the field
  `project`, and a search over several projects can be narrowed to this one.
- **Vectors.** Prose (articles, requirements, manuals) gets `"vectors": true`. Catalogs of codes,
  names and messages are usually found by their words and by `lookup`, and may stay lexical;
  give them vectors when users ask about them in their own words.
- **`description`** is shown to the assistant by `describeKnowledge`, which decides what to filter
  by from it. Say what the index holds and what it answers, in one or two sentences:
  "Billing: articles on payments and invoices, and troubleshooting playbooks", not "Docs".
- **The kind of a record** is a field, by convention `kind`: `article`, `playbook`, `spec`,
  `term`, `table`. An assistant narrows a search by it, never by naming an index
  ([search.md](search.md#what-is-searched)). Give every source a `kind`, from front matter or as
  a constant in `map.fields`.

## 4. Write one source at a time

Create `rag-indexes.yaml` next to the knowledge, usually at the root of its directory, so that
`path` values are short and relative ([YAML](indexes.md#yaml)). Start with one index and one
source, build it, look at the records, then add the next.

```yaml
# Billing knowledge for lucene-rag-server; imported by the machine's indexes file.
project: billing
indexes:
  docs@billing:
    description: 'Billing: articles on payments and invoices, and troubleshooting playbooks'
    vectors: true
    sources:
      - path: articles
        where: { status: { not: draft } }
      - path: playbooks
        map:
          fields: { kind: playbook }
```

For each source, take the first row of this table that fits and add only what it says:

| What the files are | What the source needs |
|---|---|
| Markdown with front matter (`id`, `kind`, …), a `#` title and one subject per `##` section | `path` only: the [defaults](indexes.md#writing-markdown-for-the-defaults) |
| Markdown cut at another level, or only some headings are units (`### Requirement: …`) | `split.level`, `split.headingPattern`; `map.title` from its group ([Splitting](indexes.md#splitting)) |
| Markdown where an identifier is written in the text (`**ID:** …`) | `map.bodyPattern`, and `map.id` / `map.fields` / `map.keys` from its groups ([Examples](indexes.md#examples)) |
| One file holding many independent items (a glossary) | `map.document` from a value of the unit, so each item is a document of its own ([Mapping](indexes.md#mapping), "One document per unit") |
| A table inside Markdown whose rows are the items | `split.tableRows: true`, `map.title` from a column ([Table rows](indexes.md#table-rows)) |
| Short notes that should stay whole | `split.level: document` |
| TSV, JSONL | `map.text` (required), `map.title`, `map.fields` from columns or keys |
| YAML, one item per file | `map.text` (`'{body}'` keeps the whole file), `map.title`, `map.fields` from `{yaml.<key>}` |
| YAML, a list of items in a file | `split.list` and `map` from `{item.<key>}` ([Examples](indexes.md#examples)) |
| SQL with a `-- key: value` header | Nothing, or `map.fields` from `{header.<key>}` |
| PDF with bookmarks | `split.outline: true`; `map.fields` with a `kind` |
| HTML | `split.selector` for the parts of a page, and `map.title` that tells them apart |
| Word, Excel, PowerPoint | `format: office`; for headings convert to Markdown with a `convert` step ([Commands](indexes.md#commands)) |
| A value kept in another file (a menu path in a catalog of screens) | `join` ([Joining values](indexes.md#joining-values-from-another-file)) |
| Several sources read the same way | A [named reader](indexes.md#named-readers) in `readers` |

Rules that save a rebuild:

- **Prefer the defaults.** Write a `map` key only when its default gives a wrong result you have
  seen in a built index. A Markdown `map` with `fields` keeps the front matter fields, since
  `inheritFields` defaults to all.
- **Stable ids.** Base `map.id` and `map.document` on an identifier from the content, with an
  alternative for a unit that lacks it: `'spec:{body.id|slug}'`.
- **Section size.** For an index with vectors, keep `split.maxChars` at about 1,500–2,000
  for Russian or English prose rather than the default 2,500.
- **Breadcrumb.** It is searched with twice the weight of the text. When the file path or a
  heading says more than the document title, put it in `map.breadcrumb`.
- **Commands** (`command`, `convert`, a `join` with a command) run programs while building. In an
  imported file they work only when the operator's import says `"commands": true`; ask before
  relying on one. They are an argument array without a shell ([Commands](indexes.md#commands)).

Finally, make the project's file reachable: an entry in `import` of the machine's indexes file,
for example `- ${projects}/billing/knowledge/rag-indexes.yaml`.

## 5. Build and read the report

```bash
rag-index docs@billing
```

Read the whole output ([Building](indexes.md#building)), not only `RESULT:`:

- **Exit code 2 or an `IMPORT:` line**: a configuration error, with the key and the reason. Fix
  and build again. A problem of another index or import does not concern yours, but say so to the
  user.
- **Counts.** Compare `files` with the number of files you counted in step 2: fewer means
  `include`, `exclude`, `format` or `where` left some out. `records` far above `documents` with
  the default `split` usually means long sections cut by `maxChars`; `records` equal to
  `documents` for Markdown usually means the files have no headings at the split level.
- **`cut to N tokens`** above a handful of records: lower `split.maxChars`.
- **Exit code 4**: a file could not be read or a command failed; the message names it.

Then look at the records themselves. With `RAG_MCP_TOOLS_ADMIN` on, `listIndexes` shows the index
as built; `fieldValues` lists the values of a field across records, which shows a misspelled or
missing `kind` at once ([Browsing](search.md#browsing)). Read a few records whole with
`getDocument`, or with `rag-search` on a word you know they contain:

- Does every record have a title that says what it is about, and a breadcrumb that places it?
- Does the text start where the unit starts, without the heading of the next one?
- Are the fields there, with the values you expected, and no field you did not mean?

## 6. Check search

Search the way a user asks, not by words copied from the files:

```bash
rag-search docs@billing "why was the payment not matched to an invoice" --limit 5
```

```bash
rag-search docs@billing "payment status" --filter kind=playbook
```

```bash
rag-search catalogs@billing --lookup PAYMENT_STATUS_ENUM
```

For knowledge that will be used for a while, write a question set: a JSONL file, a line per
question with the records that answer it, kept with the project (for example
`eval/questions.jsonl`), and measure it with `rag-eval`
([Measuring search](search.md#measuring-search)). Ten to twenty real questions are enough to tell
whether a change of `split` or `map` helped. When an expected record ranks low,
`explainSearch` shows why ([Explaining a search](search.md#explaining-a-search)).

## 7. Hand over

Tell the user:

- which indexes you described, with their sources and record counts, and what each one leaves out
  on purpose;
- what you changed in the machine's indexes file, and anything that needs the operator: a model
  to fetch, `"commands": true` for the import;
- what search found and what it missed, with the questions you tried;
- how to rebuild: `rag-index docs@billing`, or `rag-index --all`, or `rebuildIndex` through MCP.

The built index lives in the data directory, not in the project; commit `rag-indexes.yaml` and
the question set with the project if the user asks for it.

## Pitfalls

- **Unknown keys are ignored without a warning.** A misspelled key (`exlude`, `maxChar`) does
  nothing. When a setting seems to have no effect, check its spelling against
  [indexes.md](indexes.md). Inside `map` a misspelled value name is an error.
- **Quote YAML values that start with `{` or `*`**: `title: '{heading.name}'`,
  `include: '**/spec.md'`. Write regular expressions in single quotes
  ([YAML](indexes.md#yaml)).
- **Relative paths** resolve against the directory of the file they are written in, and so does
  the working directory of a command.
- **`split` and `map` of a source replace those of its reader whole**; they are not merged.
- **`project` and names starting with `rag.` are reserved** as field names.
- **Two records with one id fail the build**: make `map.id` or `map.document` tell them apart.
- **Text before the first heading** of a Markdown file is a record of its own (`#intro`); a
  template or a boilerplate block at the top of every file becomes a record in every document.
  Leave it out with `split.headingPattern` or keep it short.
- **A rebuilt index replaces the old one whole.** A failed build keeps the previous index, so a
  configuration error never empties a served index.
- **No real values in shared files.** A project's indexes file is committed with the project;
  write machine paths as `${name}` variables of the machine's `vars`
  ([Variables](indexes.md#variables)), and keep secrets out of commands.
