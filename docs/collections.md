# Collections

How to describe collections in `collections.json`, build them and move a built collection to
another machine.

- [The collections file](#the-collections-file)
- [Sources](#sources)
- [From a file to records](#from-a-file-to-records)
- [Reading Markdown](#reading-markdown)
- [Splitting](#splitting)
- [Mapping](#mapping)
- [Examples](#examples)
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
        { "path": "C:/projects/billing/knowledge/playbooks", "exclude": "**/draft-*.md" }
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
- Unknown keys are ignored without a warning: check spelling when a setting has no effect. Inside
  `map`, a misspelled value name is an error.
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
| `split` | see [Splitting](#splitting) | How a file is cut into units |
| `map` | see [Mapping](#mapping) | How a unit is laid out as a record |

Files are read as UTF-8. Source directories are only read, never written.

## From a file to records

A **record** is the unit that is indexed, ranked and returned. Every record has the same shape,
whatever it was made from:

| Part | What it is |
|---|---|
| `id` | Unique in the collection; what `getDocument` takes |
| `document` | Key of the document the record is a part of |
| `title` | Short name of the record |
| `breadcrumb` | Where the record is: searched with twice the weight of the text, and shown with every hit |
| `text` | The passage that is searched and returned |
| fields | Named values search can filter by |

Three steps make records out of a file, and two of them are yours to configure:

1. The **reader** of the source's `format` turns the file into a section tree and document
   fields.
2. **`split`** says where the tree is cut into units.
3. **`map`** says how the values of a unit become the parts of a record.

Without `split` and `map` a source uses the defaults described below, which suit Markdown files
with `##` sections and YAML front matter.

## Reading Markdown

- A YAML front matter block between `---` lines at the top of a file gives the document's
  fields. A scalar is one value, a list of scalars is several values; nested structures and keys
  with characters other than letters, digits, `.`, `_`, `-` are skipped.
- Headings `#` … `######` outside fenced code blocks make the section tree. Underlined (Setext)
  headings are not recognized.
- The document title is the `title` field, else the first `#` heading, else the file name.

## Splitting

| `split` key | Default | Meaning |
|---|---|---|
| `level` | `h2` | Heading level whose sections become units, `h1`…`h6`; deeper sections stay inside their parent unit, with their headings. `document` makes the whole file one unit |
| `headingPattern` | none | Regular expression a heading at `level` must contain to become a unit. With a pattern, the rest of the file is left out. Its named groups `(?<name>…)` become the values `{heading.name}` |
| `maxChars` | `2500` | A longer text is split at blank lines, then at line ends, then inside a line; every part is a record |
| `minChars` | `0` | A shorter unit is joined to the previous one under the same parent heading. `0` joins nothing |

- Without a pattern, a section above `level` contributes its own text as a unit, and text before
  the first heading is a unit too. A heading without text of its own yields nothing.
- Raising `minChars` makes fewer, larger records, but a joined section loses its own title and
  breadcrumb, which is what a query usually matches.

## Mapping

`map` holds templates: text with `{value}` placeholders.

- `{a}` is replaced by the value `a`, or by nothing when there is none.
- `{a|b}` takes the first of the alternatives that has a value.
- A template that is one placeholder and nothing else yields every value of it; this is how a
  field with several values is copied whole. Inside a longer template the first value is used.
- There is no escape for a literal `{`.

| `map` key | Default | Meaning |
|---|---|---|
| `pathPattern` | none | Regular expression searched in the file path relative to the source. Its named groups become the values `{path.<group>}` |
| `document` | `{frontmatter.id\|document.path}` | Document key |
| `title` | `{heading}` | Record title |
| `breadcrumb` | document title, parent headings, title | Array of templates; empty parts are dropped and the rest joined with ` › ` |
| `text` | `{body}` | The passage. A unit whose text comes out empty yields no record |
| `id` | `{document.key}#{slug}` | Record id. Ids are still made unique: a repeated id gets `-2`, `-3`, and the parts of a split text get `~2`, `~3` |
| `fields` | none | Object of field name to template. A template without placeholders is a constant. A field whose template gives nothing is absent |
| `inheritFields` | `["*"]` | Front matter fields copied to the record as they are: a list of names, `["*"]` for all, `[]` for none. A field of the same name in `fields` replaces the inherited one |

Values of a Markdown source, in the order they become available — a template may use only the
values above its own row:

| Value | What it is | Available in |
|---|---|---|
| `{frontmatter.<key>}` | A front matter field | everything |
| `{path}`, `{path[0]}`, `{path[1]}`, … | The file path relative to the source and its segments, from 0 | everything |
| `{path.name}`, `{path.dir}` | The file name without extension; the directory part | everything |
| `{path.<group>}` | A named group of `pathPattern` | everything |
| `{document.path}`, `{document.title}` | The file path; the document title | everything |
| `{document.key}` | The result of `document` | `title` and below |
| `{heading}`, `{heading.<group>}` | The unit's heading; a named group of `split.headingPattern` | `title` and below |
| `{parents}` | The headings above the unit, joined with ` › ` | `title` and below |
| `{title}`, `{slug}` | The result of `title`; the same in lower case with dashes (`intro` for text before the first heading) | `breadcrumb` and below |
| `{breadcrumb}`, `{body}` | The result of `breadcrumb`; the unit's text | `text`, `id`, `fields` |

An unknown value name, a value used too early, a group that the pattern does not define and a
broken template are configuration errors of the collection.

Two records with the same id in one collection fail the build: make the document keys or the
`id` template distinguish them.

## Examples

Requirement files, one directory per capability, no front matter:

```markdown
# payments Specification
## Requirements
### Requirement: Create a payment
The system SHALL create a payment when a bank statement arrives.
```

```json
{
  "path": "C:/projects/billing/openspec/specs",
  "split": { "level": "h3", "headingPattern": "^Requirement: (?<name>.+)$" },
  "map": {
    "pathPattern": "^(?<subsystem>[a-z]+)-",
    "document": "{path[0]}",
    "title": "{heading.name}",
    "breadcrumb": ["{path[0]}", "{title}"],
    "fields": { "kind": "spec", "capability": "{path[0]}", "subsystem": "{path.subsystem}" }
  }
}
```

A file `billing-payments/spec.md` gives the record `billing-payments#create-a-payment` with the
breadcrumb `billing-payments › Create a payment` and the fields `kind=spec`,
`capability=billing-payments`, `subsystem=billing`. Headings that are not requirements are left
out.

Short notes kept whole, with the directory as a field and only two front matter fields kept:

```json
{
  "path": "C:/projects/billing/notes",
  "split": { "level": "document" },
  "map": {
    "fields": { "area": "{path[0]}", "kind": "note" },
    "inheritFields": ["author", "tags"]
  }
}
```

The breadcrumb repeated at the top of the passage, so the passage reads on its own:

```json
{ "path": "C:/projects/billing/knowledge/articles",
  "map": { "text": "{breadcrumb}\n\n{body}" } }
```

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
    manifest.json         name, description, build time, counts, field names,
                          sources with their split and map settings
    index/                Lucene index
```

A collection directory is self-contained. To serve it elsewhere, copy the directory under that
machine's data directory, keeping its name; neither the sources nor `collections.json` are needed
there. A directory is served when its `manifest.json` names the directory and has the index
schema of the running server; after an upgrade that changes the schema, rebuild.
