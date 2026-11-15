# Search

How the `search`, `lookup` and `getDocument` tools and the `rag-search` command find records, how
the server bounds what one call returns, and how `rag-eval` measures search on a question set.

- [Modes](#modes)
- [Queries](#queries)
- [Filters](#filters)
- [Exact lookup](#exact-lookup)
- [Results](#results)
- [Limits](#limits)
- [The rag-search command](#the-rag-search-command)
- [Measuring search](#measuring-search)
- [Known limits](#known-limits)

## Modes

`search` ranks records in one of three modes, chosen by its `mode` argument (`--mode` of
`rag-search`):

| Mode | Ranks by | Finds |
|---|---|---|
| `lexical` | BM25 over the words of the query | passages with the query's words, in any word form |
| `vector` | similarity of the embedded query to the embedded records | passages close in meaning, also without shared words |
| `hybrid` | both lists fused by reciprocal rank fusion | either; a passage high in both lists ranks first |

- **Default:** `hybrid` when a searched collection has vectors and the call has a query; `lexical`
  otherwise. `listCollections` shows the modes of each collection (`modes`). A collection has
  vectors when its `collections.json` entry names a model ([collections.md](collections.md#vectors)).
- `vector` needs every searched collection to have vectors. In `hybrid`, collections without
  vectors take part through the BM25 list only.
- `vector` and `hybrid` need a query; a call with filters only lists records as in `lexical`.
- **The model must be at hand.** The query is embedded by the model the collection was built
  with, from `<data-dir>/models/<model>/` or the Spring AI bean it names. When the files are
  missing or are not the files the collection was built with (their SHA-256 is in the manifest),
  or the bean cannot embed:
  - a call without `mode` is searched `lexical`, and the result says why in `notice`
    (`rag-search` prints it as a `NOTE:` line);
  - a call that asks for `vector` or `hybrid` is an error that says so.

  `listCollections` shows the model of each collection (`model`) and, in `modelProblem`, why it
  cannot be used as far as is known without loading it: a type the server lacks or missing files;
  once loaded, also other files than the collection was built with. The first search that needs
  the model loads it, which takes a few seconds; a model whose files are replaced is seen after a
  restart.
- **Fusion:** a record at rank *r* of a list scores 1 / (60 + *r*) for that list, and the scores
  of the lists add up (`TopDocs.rrf` of Lucene). Collections built with the same model are one
  vector list; collections of different models are separate lists.
- **Scores:** in `lexical` the BM25 score; in `vector` the cosine similarity scaled to 0…1; in
  `hybrid` the fusion score, below 0.04. Compare scores within one search only.
- **Candidates:** each list of a `vector` or `hybrid` search contributes its best 100 records
  (more when a page reaches further), so `total` is the number of fused candidates, not of all
  matches, and paging ends there.

## Queries

The rules below are those of the lexical part: `lexical` mode and the BM25 list of `hybrid`.
`vector` embeds the query as it is; phrases, stop words and word forms do not apply there.

- **Words are optional.** A record matching more of the words, or matching them in its breadcrumb
  (document title and headings), ranks higher. A whole question can be passed as it is.
- **Quotes make a phrase:** `"очередь разбора"` matches these words next to each other, in this
  order.
- **Word forms match** in Russian and English: `платежей` finds `платёж`, `retrying` finds
  `retries`. Every text is indexed with both stemmers, so the language is not declared.
- **Case** does not matter, and `ё` matches `е`.
- Common **stop words** (`и`, `в`, `the`, …) are ignored; a query of stop words alone finds
  nothing.
- **No wildcards, fuzzy matching or boolean operators** in the query; conditions on fields are
  [filters](#filters).
- A query has at most 1,000 characters.

## Filters

Two arguments of `search` select records by their fields; a call may give either, both or
neither.

- `filters` maps a field name to a value every result must have: `{"kind": "article"}`. Several
  entries must all hold.
- `filterExpression` is a condition in the filter syntax of Spring AI, for what a map cannot say:
  `kind == 'playbook' || tags in ['refunds', 'payments']`. With both, the expression must hold
  together with the map.

What applies to both:

- Fields are the document fields of the records ([collections.md](collections.md#mapping));
  `listCollections` returns the names available in each collection. An unknown field name is an
  error that lists the known ones.
- A value matches exactly, without regard to case and surrounding blanks. A field with several
  values matches when one of them equals the value.
- With filters or an expression the query may be omitted: the call then lists the matching records
  in index order.
- They apply to every mode: the vector list holds only records that pass them.

### Filter expressions

| Write | Holds when |
|---|---|
| `kind == 'article'` | the field has the value |
| `kind != 'article'` | the field does not have the value; a record without the field holds |
| `tags in ['refunds', 'payments']` | the field has one of the values |
| `tags nin ['refunds']` | the field has none of the values; a record without the field holds |
| `tables IS NULL`, `tables IS NOT NULL` | the record has no value of the field, or has one |
| `id >= 'playbook'`, also `>`, `<`, `<=` | a value of the field sorts after or before this text |
| `a && b`, `a AND b` | both hold |
| `a \|\| b`, `a OR b` | either holds |
| `NOT (a)` | `a` does not hold; parentheses group conditions |

- Values are in single or double quotes. A number or `true`/`false` without quotes is compared as
  the text it is written as: `year == 2026` is `year == '2026'`.
- `<`, `<=`, `>` and `>=` compare text, ignoring case, so `'10' < '9'`. They take quoted values
  only and suit values of one width, such as dates written `2026-01-01`; a number without quotes
  is an error.
- An expression has at most 1,000 characters. One that does not parse is an error with an
  example.
- The syntax is Spring AI's `FilterExpressionTextParser`; the server turns the parsed expression
  into a Lucene query.

## Exact lookup

`lookup` finds catalog records by a value without the name of the field it is in: a code, an
identifier, a class or table name, a message text. It is for the case where the caller has the
value, as an error text from a screen or a parameter code, and not the words to describe it.

- The value is compared whole with every value of the key fields of the collections: without
  surrounding blanks and ignoring case, as a filter compares. Words are not matched one by one,
  word forms do not match, and a part of a value finds nothing.
- The key fields are those the operator listed in `map.keys` of the sources
  ([collections.md](collections.md#mapping)); `listCollections` shows them as `keys`. A
  collection without keys is skipped; naming only such collections is an error that lists the
  collections that have keys.
- Results come in index order, with the passage as the snippet, and are paged and capped like
  the results of `search`. `getDocument` reads a hit in full.
- With a field name at hand, `search` with `filters` and no query is the same exact match on that
  field alone.

## Results

`search` returns a page of hits, best first: `collection`, `id`, `breadcrumb`, `source` (the file,
relative to its source), `score` and a `snippet` — part of the passage around the matches of the
query's words, with the matches in `**bold**`; a passage found by its vector alone may have no
bold words. `mode` says how the page was ranked. `total` counts all matching records (fused
candidates in `vector` and `hybrid`); pass `nextOffset` as `offset` with the same arguments for the
next page.

**One section per document.** By default the results hold only the best ranked section of each
document; its other sections drop out, so a page shows more different documents instead of
several sections of one. `perDocument` (`--per-document` of `rag-search`) raises the number, for
example to read more of a document already found. It applies to the sections of a document (a
Markdown source cut by headings). A row of a catalog, a table (`split.tableRows`) or a file of
another format stands alone and is never dropped. `total` still counts every matching record, so a
page may end, without `nextOffset`, before `total` is reached.

`getDocument` takes a hit's `collection` and `id` and returns the full passage with the document's
fields, its key and the record's position in the document.

**A record as a resource.** The same record is the MCP resource `rag://{collection}/{id}`, as
Markdown: the breadcrumb as the heading, a list of the collection, the record id, the source and
the fields (a nested list), then the passage, cut to the response size cap as `getDocument` is. A client can show it
or attach it to a conversation without a tool call. The values in the URI are percent-encoded
(an id holds `#` and often `/`), for example
`rag://docs%40billing/article.billing.refunds%23statuses`. A client that supports completion
suggests both values: `collection` from the built collections whose name starts with what is
typed, ignoring case, and `id` from the ids of the chosen collection that start with it, in id
order, at most 100. The resource and its completion belong to the `search` group.

Several collections in one call are searched as one index, so their BM25 scores are comparable.

## Limits

The server bounds every response, whatever a call asks for. Lower the values for a model with a
small context.

| Variable | Default | Meaning |
|---|---|---|
| `RAG_MCP_SEARCH_DEFAULT_LIMIT` | 10 | Hits per page when a call names no `limit` |
| `RAG_MCP_SEARCH_MAX_LIMIT` | 50 | The most hits per page a call can get |
| `RAG_MCP_RESPONSE_MAX_CHARS` | 20000 | Characters of snippet text in one `search` response, and of passage text in one `getDocument` response; at least 500 |

A snippet is at most about 600 characters. When the size cap ends a page early, the result has
`truncated: true` and `nextOffset` continues from the first hit that did not fit.

**For a model with a small context** (measured on the first real corpus, `docs/plan.md` stage 4):
a page of 10 hits carried about 4,300 characters of snippets, a page of 5 about 2,000, and 5 hits
still held the expected record for 14 of 17 questions against 15 for 10. A starting point:

| Variable | Strong model (defaults) | Small context |
|---|---|---|
| `RAG_MCP_SEARCH_DEFAULT_LIMIT` | 10 | 5 |
| `RAG_MCP_SEARCH_MAX_LIMIT` | 50 | 10 |
| `RAG_MCP_RESPONSE_MAX_CHARS` | 20000 | 4000 |

## The rag-search command

Searches from the command line with the same search and the same limits, without a chat model.
It starts the application without the MCP server, about a second, so its embedding models are the
server's; a `vector` or `hybrid` search loads the model in the command's process:

```bash
java -jar lucene-rag-server.jar rag-search docs@billing "отмена платежа" --limit 5
```

```bash
java -jar lucene-rag-server.jar rag-search docs@billing,api@billing --filter kind=playbook
```

```bash
java -jar lucene-rag-server.jar rag-search docs@billing --filter-expression "kind == 'article' && tags in ['refunds']"
```

```bash
java -jar lucene-rag-server.jar rag-search docs@billing "деньги вернулись клиенту" --mode vector
```

```bash
java -jar lucene-rag-server.jar rag-search catalogs@billing --lookup PAYMENT_STATUS_ENUM
```

The first argument is one or more collection names separated by commas; the remaining words are the
query. `--filter name=value` may be repeated; `--filter-expression` takes one
[filter expression](#filter-expressions). `--per-document N` shows up to N sections of a document
([Results](#results)). `--mode lexical|vector|hybrid` chooses the
[mode](#modes); without it the default applies, and the last line names the mode used.
`--lookup value` makes an [exact lookup](#exact-lookup) instead and takes no query, no filters, no
expression, no mode and no `--per-document`. Exit codes: 0 searched (also with no matches), 1
unexpected error, 2 wrong usage, an unknown collection, filter field or mode, or an expression that
does not parse, 4 an index could not be read, 5 the embedding model of a collection is missing or
is not the one it was built with.

## Measuring search

`rag-eval` searches every question of a question set in every mode and reports how high the
records each question expects rank. It searches as the `search` tool does, without the response
limits, and runs without a chat model:

```bash
java -jar lucene-rag-server.jar rag-eval docs@billing,data@billing,queue@billing C:/projects/billing/knowledge/eval/questions.jsonl --ref data=table --ref symptom=number
```

```
Questions: 20 read, 17 measured, 3 left out (0 without references, the others with no reference that names a record)
References that name no record: 9 (spec: 9)

mode          @1      @5     @10    MRR recall@10  ms/query
lexical     9/17   15/17   16/17   0.69      0.75        24
vector      6/17   14/17   17/17   0.55      0.76       120
hybrid      9/17   15/17   17/17   0.69      0.76        23
```

**The question set** is a JSONL file, one question per line: `question` is the text searched,
`expected` a list of references to the records that answer it, `id` names the question in the
details. Other keys are ignored.

**A reference names a record** when it is
- the record's document key or id (`article.billing.refunds`);
- the record's source path, also written from a directory above the source
  (`catalogs/menu/menu.tsv` names the records of `menu.tsv`);
- `prefix:value`, and a field of the record has the value, compared as a filter compares. The
  field is the one `--ref prefix=field` maps the prefix to, else the field named as the prefix:
  with `--ref data=table`, `data:PAYMENT` names the records whose field `table` is `PAYMENT`.

**What is measured:**
- A reference that names no record of the collections cannot be found by any search. It is counted
  by prefix and left out; a question with no reference that names a record is left out too. Add the
  collection, the mapping or the field that makes it reachable, or accept it as a gap.
- Ranks count records as a client sees them, after the [one section per document](#results)
  limit; `--per-document N` measures with another limit.
- `@1`, `@5` and `@N`: the questions whose first expected record is at rank 1, within 5, and within
  the depth N. `MRR`: the mean of 1 / rank, 0 when not found within the depth. `recall@N`: the mean
  share of a question's references found within the depth. `ms/query`: the mean time of one
  search; the first vector search includes loading the model.

**Options:** `--limit N` sets the depth (default 10, at most 100). `--modes lexical,hybrid`
chooses the modes; without it every mode the collections have is measured (`vector` only when
every collection has vectors). `--ref prefix=field` may be repeated. `--per-document N` measures
search with that limit instead of the default 1. `--details` adds a line per
question with its rank in each mode (`-` when not found) and the references that name no record.

Exit codes: 0 measured, 1 unexpected error, 2 wrong usage, an unknown collection or mode, or a
question file that cannot be read, 4 an index could not be read, 5 the embedding model of a
collection is missing or is not the one it was built with.

## Known limits

- Lexical search does not find a passage that uses other words than the query; retry with
  synonyms, or use `vector` or `hybrid` on a collection with vectors.
- A vector covers at most `maxTokens` of a record (512 tokens for E5); the rest of a longer
  record is found by its words only.
- How well each mode finds the right passage has been measured on one question set only; tuning is
  stage 4 of [plan.md](plan.md) section 4.
- The Snowball stemmer does not join irregular Russian forms (`письмо` and `писем`).
- Snippets are cut at sentence boundaries found in Markdown source, so a snippet may start with a
  heading marker or contain a whole table row.
