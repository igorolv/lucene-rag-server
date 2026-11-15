# Collections

How to describe collections in `collections.json`, build them and move a built collection to
another machine.

- [The collections file](#the-collections-file)
- [Sources](#sources)
- [Named readers](#named-readers)
- [From a file to records](#from-a-file-to-records)
- [Reading Markdown](#reading-markdown)
- [Reading TSV, YAML and JSONL](#reading-tsv-yaml-and-jsonl)
- [Reading SQL](#reading-sql)
- [Reading PDF](#reading-pdf)
- [Commands](#commands)
- [Splitting](#splitting)
- [Mapping](#mapping)
- [Selecting records](#selecting-records)
- [Vectors](#vectors)
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
| `path` | required, unless `command` | A directory or one file. A relative path is resolved against the directory of `collections.json`; `~` is the home directory; `${VAR}` is replaced by an environment variable, and an unset variable is an error |
| `include` | the format's: `**/*.md`, `**/*.tsv`, `**/*.yaml`, `**/*.jsonl`, `**/*.sql`, `**/*.pdf` | Glob on the path relative to `path`, with `/` separators. A pattern starting with `**/` also matches files directly in `path` |
| `exclude` | none | Glob of files to skip |
| `format` | by extension, else `markdown` | How a file is parsed: `markdown`, `tsv`, `yaml`, `jsonl`, `sql` or `pdf`. Without it the extension of `include`, or of `path` when it is one file, decides (`.md`, `.markdown`, `.tsv`, `.yaml`, `.yml`, `.jsonl`, `.sql`, `.pdf`); a directory without `include` is read as Markdown |
| `split` | see [Splitting](#splitting) | How a file is cut into units |
| `map` | see [Mapping](#mapping) | How a unit is laid out as a record |
| `command` | none | Instead of `path`: a program whose output is the only file of the source, see [Commands](#commands) |
| `timeoutSeconds` | `60` | How long `command` may run |
| `convert` | none | Programs every file passes through before it is parsed, see [Commands](#commands) |
| `where` | none | Which records are kept, see [Selecting records](#selecting-records) |
| `reader` | none | Name of an entry of the top-level `readers` object whose keys the source takes, see [Named readers](#named-readers) |

Files are read as UTF-8. Source directories are only read, never written.

## Named readers

When several sources are read the same way, write the way once in the top-level `readers`
object and name it in each source with `reader`:

```json
{
  "readers": {
    "requirements": {
      "split": { "level": "h3", "headingPattern": "^Requirement: (?<name>.+)$" },
      "map": { "document": "{path[0]}", "title": "{heading.name}", "fields": { "kind": "spec" } }
    }
  },
  "collections": {
    "specs@billing": {
      "sources": [
        { "path": "C:/projects/billing/openspec/specs", "reader": "requirements" },
        { "path": "C:/projects/billing/openspec/changes", "reader": "requirements",
          "map": { "document": "{path[0]}", "title": "{heading.name}", "fields": { "kind": "change" } } }
      ]
    }
  }
}
```

- A reader holds the reading keys of a source: `convert`, `format`, `split` and `map`. Other
  keys in a reader are ignored; `path`, `command`, `include` and `exclude` always belong to the
  source.
- A key the source sets itself replaces the reader's key whole. The second source above sets
  `map`, so none of the reader's `map` is used: `split` and `map` are not merged key by key.
- A reader is used only through the sources that name it: a mistake in a reader is reported as
  the configuration error of each collection whose source names it, and an unused reader is not
  checked. A source naming a reader that is not in `readers` is a configuration error of its
  collection.

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

1. The **reader** of the source's `format` turns the file into a section tree (Markdown) or
   into rows (TSV, YAML, JSONL, SQL, PDF).
2. **`split`** says where the tree is cut into units. A row already is a unit; of `split` only
   `maxChars` applies to it, and `list` to YAML.
3. **`map`** says how the values of a unit become the parts of a record.

Without `split` and `map` a Markdown source uses the defaults described below, which suit
Markdown files with `##` sections and YAML front matter, an SQL source is one record per
file, and a PDF source one record per page. A TSV, YAML or JSONL source has no default text: its `map` must give `text`.

## Reading Markdown

- A YAML front matter block between `---` lines at the top of a file gives the document's
  fields. A scalar is one value, a list of scalars is several values; nested structures and keys
  with characters other than letters, digits, `.`, `_`, `-` are skipped.
- Headings `#` … `######` outside fenced code blocks make the section tree. Underlined (Setext)
  headings are not recognized.
- The document title is the `title` field, else the first `#` heading, else the file name.

## Reading TSV, YAML and JSONL

These formats give one unit per row and no section tree. Their values are named by the format;
the values every source has (`{path…}`, `{document…}`, `{title}`, `{breadcrumb}`) are listed in
[Mapping](#mapping). `{row}` is the position of the unit in its file, from 1.

| Format | Unit | Values |
|---|---|---|
| `tsv` | a line after the header line | `{column.<name>}` for every column named by the header |
| `jsonl` | a line, a JSON object | `{record.<key>}` for every key |
| `yaml` | the file, or an item of the list named by `split.list` | `{yaml.<key>}` for the top-level keys of the file; with `split.list` `{item.<key>}` for the keys of an item, or `{item}` for an item that is a scalar; `{body}` |

- **TSV.** UTF-8, cells separated by tabs, no quoting: a `"` is an ordinary character. The first
  non-blank line names the columns; blank lines are skipped. A row with fewer cells than columns
  has empty trailing cells; a row with more cells, or a column named twice, is an error of the
  file.
- **JSONL.** Every non-blank line is one JSON object; a line that is not is an error of the file.
- **YAML.** The top level of the file must be a mapping. Without `split.list` the whole file is
  one unit, and `{body}` is the file as it is. With `split.list` naming a top-level key whose
  value is a list, every item of that list is a unit and `{body}` is the item written back as
  YAML; a file without that key is an error of the file. Lists elsewhere, and mappings such as a
  map of columns, are not cut: they stay in `{body}`.
- A scalar is one value and a list of scalars is several; nested objects are not values. Empty
  cells and blank values give no value, so `{a|b}` falls back to `b`. Keys with characters other
  than letters, digits, `.`, `_`, `-` are skipped.
- These formats give no document fields, so `inheritFields` has nothing to copy: name the fields
  in `map.fields`.
- Defaults: the document key is `{document.path}`, the title is `{document.title}` (the file name
  without extension) for TSV and YAML and `{record.title|record.id}` for JSONL, the id is
  `{document.key}#{row}`, for JSONL `{document.key}#{record.id|row}`.

## Reading SQL

One SQL file is one unit; the statement is not parsed.

```sql
-- id: payments.payment-status-history
-- purpose: history of a payment's statuses with the operator's comments
-- params: :payment_id — payment ID
-- tables: PAYMENT, PAYMENT_STATUS_HIST, USERS
SELECT h.psh_time, h.payment_status_enum, h.psh_note
FROM payment_status_hist h
WHERE h.payment_id = :payment_id
```

| Value | What it is | Available in |
|---|---|---|
| `{header.<key>}` | The value of a header line `-- key: value`; a key repeated on several lines has several values | everything |
| `{comment}` | The header without the `--` | everything |
| `{sql}` | The text after the header | everything |
| `{body}` | The file as it is | `title` and below |

- The header is the block of lines starting with `--` at the top of the file, after any blank
  lines; it ends at the first other line. A comment further down belongs to `{sql}`.
- A header line is a key line when what follows the dashes is a name of letters, digits, `.`,
  `_`, `-`, a colon and a value. Other comment lines, such as a free description of a file that
  generates a catalog, only go into `{comment}`. A key line with an empty value gives no value.
- Header values are not split: `tables: A, B` is one value `A, B`.
- An SQL source gives no document fields: name the fields in `map.fields`.
- Defaults: the document key is `{header.id|document.path}`, the title
  `{header.purpose|path.name}`, the id `{document.key}`, the text `{body}`.

Files that generate catalogs usually sit next to the catalogs and have a free comment instead of
a header. Read them as a separate source or leave them out with `exclude`.

## Reading PDF

Every page of a PDF file is one unit. The file is read by the PDF reader of Spring AI
(`PagePdfDocumentReader`, built on Apache PDFBox).

```json
{ "path": "C:/projects/billing/manuals", "include": "**/*.pdf",
  "map": { "fields": { "kind": "manual" } } }
```

| Value | What it is | Available in |
|---|---|---|
| `{pages}` | The number of pages of the file | everything |
| `{pdf.title}` | The title in the document information of the file, when it has one | everything |
| `{page}` | The number of the page, from 1 | `title` and below |
| `{row}` | The position of the page among the pages that have text, from 1 | `title` and below |
| `{body}` | The text of the page | `title` and below |

- The document title is `{pdf.title}`, else the file name without extension.
- The text keeps the lines of the page; runs of blanks become one blank and runs of empty lines one
  empty line. A page without text, such as a scan without a text layer, gives no record: text is
  not recognized from images.
- Headings and bookmarks are not read, so a record is a page, not a section. To index a manual by
  its sections, convert it to Markdown and read that ([Commands](#commands)).
- The format reads the file itself: a PDF source takes no `convert` steps and has `path`, not
  `command`. A file that is not a readable PDF is an error of the file.
- A PDF source gives no document fields: name the fields in `map.fields`.
- Defaults: the document key is `{document.path}`, the title `page {page}`, so the breadcrumb is
  the document title and `page 3`; the id `{document.key}#page-{page}`, the text `{body}`.

## Commands

A program the server does not know can still feed a collection: it prints text, and the source's
`format` parses that text. A command runs only while `rag-index` builds, never while the server
answers, and only as `collections.json` writes it.

- A command is an array: the program and its arguments, run without a shell, so there are no
  pipes, redirections or wildcards; write a script for those. The program is found on `PATH` or
  by its path; the working directory is the directory of `collections.json`, so relative paths
  in arguments resolve against it.
- What the program prints on standard output is read as UTF-8. A program that exits with a code
  other than 0, or does not finish within `timeoutSeconds` (default 60), fails the build with
  exit code 4 and the end of its error output in the message.
- A collections file with commands runs programs on the machine that builds: do not build from a
  collections file of an untrusted origin.

**A command as the origin.** A source with `command` instead of `path` runs the program once;
its output is the source's only file. `include` and `exclude` do not apply, and the path the
templates see as `{path}` is the command line as written, so give the source a `map.document`.

```json
{ "command": ["python", "-I", "tools/export_help.py"], "timeoutSeconds": 300,
  "format": "jsonl",
  "map": { "document": "help", "title": "{record.title}", "text": "{record.text}" } }
```

**Commands as `convert` steps.** `convert` is an array of steps that every file of a `path`
source passes through before its `format` reads it, in the order written. A step is
`{ "command": [...], "timeoutSeconds": n }`.

- An argument containing `{file}` gets the absolute path of the file in its place, and the
  program reads the file itself: such a step may come first for a binary file, and it reads
  the original file even after other steps.
- A step without `{file}` receives the text so far on standard input.
- `format` is the format of the converted text. A source of `command` cannot use `{file}`: it
  has no files.

Word documents converted to Markdown with [Pandoc](https://pandoc.org), which the build machine
must have; the server does not ship it:

```json
{ "path": "C:/projects/billing/manuals", "include": "**/*.docx",
  "convert": [ { "command": ["pandoc", "-f", "docx", "-t", "gfm", "--wrap=none", "{file}"],
                 "timeoutSeconds": 60 } ],
  "format": "markdown" }
```

The same steps written once as a [named reader](#named-readers):

```json
{
  "readers": {
    "word": { "convert": [ { "command": ["pandoc", "-f", "docx", "-t", "gfm", "{file}"] } ],
              "format": "markdown", "split": { "level": "h2" } }
  },
  "collections": {
    "manuals@billing": {
      "sources": [ { "path": "C:/projects/billing/manuals", "include": "**/*.docx", "reader": "word" } ]
    }
  }
}
```

## Splitting

| `split` key | Default | Meaning |
|---|---|---|
| `level` | `h2` | Heading level whose sections become units, `h1`…`h6`; deeper sections stay inside their parent unit, with their headings. `document` makes the whole file one unit |
| `headingPattern` | none | Regular expression a heading at `level` must contain to become a unit. With a pattern, the rest of the file is left out. Its named groups `(?<name>…)` become the values `{heading.name}` |
| `maxChars` | `2500` | A longer text is split at blank lines, then at line ends, then inside a line; every part is a record |
| `minChars` | `0` | A shorter unit is joined to the previous one under the same parent heading. `0` joins nothing |
| `list` | none | YAML only: the top-level key whose list items become units. Without it a YAML file is one unit. Any other format reports it as a configuration error |
| `tableRows` | `false` | Markdown only: every row of a table in a unit becomes a unit of its own, and the rest of the unit is left out ([Table rows](#table-rows)) |

- Without a pattern, a section above `level` contributes its own text as a unit, and text before
  the first heading is a unit too. A heading without text of its own yields nothing.
- Raising `minChars` makes fewer, larger records, but a joined section loses its own title and
  breadcrumb, which is what a query usually matches.

### Table rows

With `"tableRows": true` a Markdown source makes one record of each row of its tables: a list of
symptoms, of error codes or of settings kept as a table in a document.

```json
{ "path": "C:/projects/billing/knowledge/queue/symptoms.md",
  "split": { "tableRows": true },
  "map": { "title": "{column.2}", "id": "{document.key}#{column.1}",
           "fields": { "number": "{column.1}" } } }
```

| Value | What it is |
|---|---|
| `{column.<n>}` | The cell in column `n` of the row, from 1 |
| `{column.<name>}` | The same cell by its header, when the header has only letters, digits, `.`, `_`, `-` and is not a number |
| `{row}` | The position of the row in its table, from 1 |
| `{body}` | A table of the header line and this row alone, so the passage keeps the column names |
| `{heading}`, `{parents}`, `{heading.<group>}` | Those of the section the table is in |

- The units are cut first by `level` and `headingPattern`, then each unit gives the rows of its
  tables; text that is not a table row is left out. `headingPattern` therefore chooses which
  tables are read.
- A table is a header line, a delimiter line of dashes (`|---|:--:|`) and the lines after it up
  to a blank line or a line without `|`. Tables inside fenced code blocks are not read. `\|` in a
  cell is a `|` of its text. A row with fewer cells than the header has no values for the missing
  ones.
- The breadcrumb ends with the section heading and the record title: set `map.title` to a column,
  since the default title is the section heading and would be the same for every row. The
  default id is made unique with `-2`, `-3`; an id from a column, as above, is stable.

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
| `fields` | none | Object of field name to template. A template without placeholders is a constant. A field whose template gives nothing is absent. Names starting with `rag.` are reserved: a record with such a field, written or inherited, fails the build |
| `keys` | none | Field names, from `fields` or inherited, whose values the `lookup` tool finds the record by ([search.md](search.md#exact-lookup)). A listed field a record does not have is skipped |
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

A TSV, YAML, JSONL, SQL or PDF source has the same values except `{frontmatter.…}`, `{heading…}` and
`{parents}`. Its document values (`{yaml.<key>}`, `{header.<key>}`, `{comment}`, `{sql}`) are
available everywhere; its unit values (`{column.…}`, `{record.…}`, `{item…}`, `{row}`, and
`{body}` of YAML and SQL) from `title` on.

An unknown value name, a value used too early, a group that the pattern does not define and a
broken template are configuration errors of the collection.

Two records with the same id in one collection fail the build: make the document keys or the
`id` template distinguish them.

## Selecting records

`where` keeps the records whose fields fit every condition, after `map`: it sees the fields a
record has in the index, inherited and mapped.

```json
{ "path": "C:/projects/billing/knowledge/articles",
  "where": { "status": { "not": "skeleton" }, "kind": "article" } }
```

| Condition | A record is kept when |
|---|---|
| `"field": value` | the field has the value among its values |
| `{ "not": value }` | the field does not have the value; a record without the field is kept |

- Values are compared as filters compare them: without surrounding blanks and ignoring case. A
  number or `true` is compared as its text.
- No other condition exists: no lists of values, patterns or comparisons. Several conditions
  must all hold.
- A file whose records are all dropped counts as a file but not as a document in the build
  report.

## Vectors

A collection with `vectors` gets an embedding of every record, so that `search` can find passages
close in meaning to the query even when they share no words with it
([search.md](search.md#modes)). A collection without `vectors` is searched by words only, which
suits catalogs of codes and names.

```json
{
  "models": {
    "e5-small": { "type": "onnx", "queryPrefix": "query: ", "passagePrefix": "passage: ",
                  "maxTokens": 512 }
  },
  "collections": {
    "docs@billing": {
      "description": "Billing: articles and troubleshooting playbooks",
      "vectors": { "model": "e5-small" },
      "sources": [ { "path": "C:/projects/billing/knowledge/articles" } ]
    }
  }
}
```

The top-level `models` object names the embedding models; `vectors.model` of a collection names
one of them. Several collections may share a model.

| Key of a model | Default | Meaning |
|---|---|---|
| `type` | `onnx` | How the model runs. `onnx`: in the server process, by ONNX Runtime, from its files; the text never leaves the machine. `bean`: a Spring AI embedding model of the application, named as this model ([Spring AI models](#spring-ai-models)) |
| `queryPrefix` | empty | Put before a search query. The E5 models expect `query: ` |
| `passagePrefix` | empty | Put before the text of a record. The E5 models expect `passage: ` |
| `maxTokens` | 512 | Input limit in tokens; a longer record is cut and its vector covers only its beginning. 512 is the limit of the E5 models |

The model name is also a directory name: letters, digits, `.`, `_` and `-`, up to 64 characters,
not starting with a dot. A model that is not defined, or a wrong entry in `models`, is a
configuration error of the collections that name it.

### Model files

The files of a model named `e5-small` are in `<data-dir>/models/e5-small/`, both where collections
are built and where they are served; there is no setting for another place. An `onnx` model
needs two files there:

| File | What it is |
|---|---|
| `model.onnx` | The encoder graph. Its output `last_hidden_state` is averaged over the tokens and scaled to unit length, as the E5 models are trained; a graph that outputs `sentence_embedding` is only scaled |
| `tokenizer.json` | The Hugging Face tokenizer that belongs to the graph |

The server never downloads anything. The default model is `intfloat/multilingual-e5-small`
(384 dimensions); download the two files from the `onnx/` folder of that model on Hugging Face:

```bash
curl -L -o model.onnx https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx/model.onnx
```

```bash
curl -L -o tokenizer.json https://huggingface.co/intfloat/multilingual-e5-small/resolve/main/onnx/tokenizer.json
```

`intfloat/multilingual-e5-base` (768 dimensions, about three times slower to embed) has the same
layout. Its `model_qint8_avx512_vnni.onnx` variant needs a processor with AVX-512 VNNI.

On first use the tokenizer library unpacks its native code into `<data-dir>/cache/djl/`. The
server switches off that library's usage reporting and its downloads before loading it.

### Spring AI models

A model of type `bean` is a Spring AI `EmbeddingModel` that the application configures, for
example a remote endpoint. The key of the model under `models` is the name of the bean; the model
has no files under `models/`. The prefixes apply as to any model; `maxTokens` does not, and such a
model reports no records as cut.

```json
"models": {
  "openAiEmbeddingModel": { "type": "bean", "queryPrefix": "", "passagePrefix": "" }
}
```

- The bean comes from a Spring AI model starter on the class path and its own properties, for
  example `spring.ai.openai.embedding.*`. The server includes no such starter yet; a site that needs
  one gets it added to the build (`docs/decisions.md` D43). Records then leave the machine, which
  is why the model has to be named in `collections.json`.
- When the model is loaded it embeds one probe text. Its vector size and the model name its
  response gives are recorded in the manifest with the bean name, and a collection is searched by
  vectors only with a bean that gives the same.
- A bean that does not exist is an error that lists the embedding model beans there are.

### What is embedded

Each record is embedded as its breadcrumb, a blank line and its text, after `passagePrefix`, in
batches of 64 records. Keep
records within the model's limit: about 512 tokens is 1,500–2,500 characters of Russian or English
prose, so a `split.maxChars` above that leaves the end of long records without a vector of their
own. `rag-index` reports how many records were cut.

### The embedding cache

Vectors are kept in `<data-dir>/cache/embeddings/` by a hash of the model files, `maxTokens` and
the embedded text. A rebuild embeds only records whose text changed, and collections with the same
model share the entries. The cache is never pruned; deleting the directory only makes the next
build slower. While one build uses it, a build in another process embeds every record and logs a
warning.

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

A catalog of messages, one record per row, with the kind of a message as a filter field:

```text
kind	call	text	file	line	method
error	ValidationException	Сумма платежа должна быть больше нуля	billing/src/payments/PaymentValidator.java	42	checkAmount
```

```json
{ "path": "C:/projects/billing/knowledge/catalogs/messages", "format": "tsv",
  "map": { "title": "{column.text}",
           "text": "{column.text}\nClass: {column.file}, method {column.method}",
           "fields": { "kind": "{column.kind}", "catalog": "messages",
                       "text": "{column.text}", "method": "{column.method}" },
           "keys": ["text", "method"] } }
```

With `keys`, `lookup` finds the row by the whole text of the message or by the name of the
method that raises it.

Descriptions of enumerations, one record per value, with the enumeration as the document:

```yaml
enum: PaymentStatusEnum
meaning: "status of an incoming payment"
values:
  - {code: "1", constant: UNRECOGNIZED, label: "not recognized"}
  - {code: "2", constant: MATCHED, label: "matched to an invoice"}
```

```json
{ "path": "C:/projects/billing/knowledge/data/enums", "format": "yaml",
  "split": { "list": "values" },
  "map": { "document": "{yaml.enum}", "id": "{document.key}#{item.code}",
           "title": "{yaml.enum} {item.code} {item.label}",
           "text": "{yaml.enum} {item.code} {item.constant}: {item.label}. {yaml.meaning}",
           "fields": { "enum": "{yaml.enum}" } } }
```

Descriptions of tables, one file per table, kept whole:

```json
{ "path": "C:/projects/billing/knowledge/data/tables", "format": "yaml",
  "map": { "title": "{yaml.table} {yaml.business_name}", "text": "{body}",
           "fields": { "table": "{yaml.table}" } } }
```

A question set, one record per line:

```json
{ "path": "C:/projects/billing/knowledge/eval/questions.jsonl",
  "map": { "title": "{record.question}", "text": "{record.question}\n{record.answer}",
           "fields": { "expected": "{record.expected}" } } }
```

## Building

```bash
java -jar lucene-rag-server.jar rag-index docs@billing
```

```bash
java -jar lucene-rag-server.jar rag-index --all
```

The command prints the number of files, documents and records per collection; for a collection
with vectors also the model, the vector size, how many records were embedded, how many vectors
came from the [cache](#the-embedding-cache) and how many records were cut to `maxTokens`. It ends
with `RESULT: OK (...)` or `RESULT: FAILED (...)`.

```
Collection docs@billing
  files 3, documents 3, records 12, 4.1 s
  vectors: model e5-small, 384 dimensions, 12 embedded, 0 from cache, 0 cut to 512 tokens
RESULT: OK (1 collection built)
```

| Exit code | Meaning |
|---|---|
| 0 | built |
| 1 | unexpected error |
| 2 | wrong usage, unknown collection or wrong configuration |
| 4 | a source could not be read, a command failed, the model could not be loaded or the index could not be written |

A build always reads every source again and replaces the whole collection. The new index is
written next to the old one and swapped in when complete, so a failed build keeps the previous
collection. On Windows the swap fails while another process is in the middle of reading the old
index; run the build again.

A running server sees a rebuilt collection on its next call; no restart is needed.

## What a built collection is

```
<data-dir>/
  collections.json        needed to build only
  models/e5-small/        model.onnx, tokenizer.json
  cache/                  embedding cache and the tokenizer's native code; derived
  docs@billing/
    manifest.json         name, description, build time, counts, field names,
                          sources with their command, convert, split and map settings,
                          the model with its parameters and the SHA-256 of its files
    index/                Lucene index, with the vectors
```

A collection directory is self-contained. To serve it elsewhere, copy the directory under that
machine's data directory, keeping its name; neither the sources nor `collections.json` are needed
there. A collection with vectors needs its model for vector and hybrid search: copy
`models/<model>/` too. Search compares the files with the hashes in the manifest, so a collection
built with other model files is searched lexically only until it is rebuilt. A directory is served when its `manifest.json` names the directory and has the index
schema of the running server; after an upgrade that changes the schema, rebuild.
