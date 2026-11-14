# Collections

How to describe collections in `collections.json`, build them and move a built collection to
another machine.

- [The collections file](#the-collections-file)
- [Sources](#sources)
- [Markdown](#markdown)
- [Chunking](#chunking)
- [Building](#building)
- [What a built collection is](#what-a-built-collection-is)

## The collections file

A collection is a named, indexed set of sources. Collections are described in one JSON file,
`<data-dir>/collections.json` (`RAG_MCP_DATA_DIR` sets the data directory, default
`~/.lucene-rag-server`; `RAG_MCP_COLLECTIONS_FILE` points at another file).

```json
{
  "collections": {
    "docs@billing": {
      "description": "Billing: articles and troubleshooting playbooks",
      "sources": [
        { "path": "C:/projects/billing/knowledge/articles" },
        { "path": "C:/projects/billing/knowledge/playbooks", "exclude": "**/draft-*.md" },
        { "path": "${BILLING_REPO}/openspec/specs", "chunk": { "splitBy": "h3" } }
      ]
    }
  }
}
```

- The key is the collection name: what tools and commands take as the collection, and the name of
  its directory under the data directory. Letters, digits, `.`, `_`, `-` and at most one `@`, up
  to 64 characters, not starting with a dot; `logs`, `models` and `cache` are reserved. The
  suggested form is `<part>@<project>`.
- `description` is shown to the model by `listCollections`; say what the collection holds.
- A missing or empty file means no collections can be built. A file that is not valid JSON is an
  error. A single wrong entry is reported for that collection (`configError` in `listCollections`,
  a failure in `rag-index`) and does not affect the others.
- Unknown keys are ignored without a warning: check spelling when a setting has no effect.
- The file is needed only to build. A [built collection](#what-a-built-collection-is) is served
  without it.

## Sources

Each entry of `sources`:

| Key | Default | Meaning |
|---|---|---|
| `path` | required | A directory or one file. A relative path is resolved against the directory of `collections.json`; `~` is the home directory; `${VAR}` is replaced by an environment variable, and an unset variable is an error |
| `include` | `**/*.md` | Glob on the path relative to `path`, with `/` separators. A pattern starting with `**/` also matches files directly in `path` |
| `exclude` | none | Glob of files to skip |
| `format` | `markdown` | Reader. Only `markdown` is supported so far |
| `chunk` | see [Chunking](#chunking) | How the source is cut into records |

Files are read as UTF-8. Source directories are only read, never written.

## Markdown

- A YAML front matter block between `---` lines at the top of a file becomes the document's
  **fields**. A scalar is one value, a list of scalars is several values; nested structures and
  keys with characters other than letters, digits, `.`, `_`, `-` are skipped. Fields are what
  search filters by, and every record of the document inherits them.
- The `id` field, when present, is the document key; otherwise the key is the file path relative
  to the source. The `title` field, the first `#` heading or the file name is the document title.
- Headings `#` … `######` outside fenced code blocks make the section tree. Underlined (Setext)
  headings are not recognized.

```markdown
---
id: article.billing.payments
kind: article
tables: [PAYMENT, INVOICE]
---

# Payments

## How a payment is created
...
```

## Chunking

A **record** is the unit that is indexed, ranked and returned.

| `chunk` key | Default | Meaning |
|---|---|---|
| `splitBy` | `h2` | Heading level whose sections become records, `h1`…`h6`. Deeper sections stay inside their parent record, with their headings |
| `maxChars` | `2500` | A longer record is split at blank lines, then at line ends, then inside a line |
| `minChars` | `0` | A shorter record is joined to the previous one under the same parent heading. `0` joins nothing |

- A section above the `splitBy` level contributes its own text as a record; text before the first
  heading is a record too. A heading without text of its own yields no record.
- Every record carries a **breadcrumb**: the document title and the headings leading to it. The
  breadcrumb is searched with a higher weight than the text.
- Record ids are `<document key>#<heading in lower case with dashes>`; the parts of a split
  section get `~2`, `~3`, and a repeated heading gets `-2`, `-3`. An id stays the same while the
  heading text does.
- Two records with the same id fail the build: give the documents different `id` fields.
- Raising `minChars` makes fewer, larger records, but a joined section loses its own breadcrumb,
  which is what a query usually matches.

## Building

```bash
java -jar lucene-rag-server.jar rag-index docs@billing
```

```bash
java -jar lucene-rag-server.jar rag-index --all
```

The command prints the number of files, documents and records per collection and ends with
`RESULT: OK (...)` or `RESULT: FAILED (...)`.

| Exit code | Meaning |
|---|---|
| 0 | built |
| 1 | unexpected error |
| 2 | wrong usage, unknown collection or wrong configuration |
| 4 | a source could not be read or the index could not be written |

A build always reads every source again and replaces the whole collection. The new index is
written next to the old one and swapped in when complete, so a failed build keeps the previous
collection. On Windows the swap fails while another process is in the middle of reading the old
index; run the build again.

A running server sees a rebuilt collection on its next call; no restart is needed.

## What a built collection is

```
<data-dir>/
  collections.json        needed to build only
  docs@billing/
    manifest.json         name, description, build time, counts, field names, sources
    index/                Lucene index
```

A collection directory is self-contained. To serve it elsewhere, copy the directory under that
machine's data directory, keeping its name; neither the sources nor `collections.json` are needed
there. A directory is served when its `manifest.json` names the directory and has the index
schema of the running server; after an upgrade that changes the schema, rebuild.
