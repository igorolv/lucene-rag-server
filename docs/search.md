# Search

How the `search`, `lookup` and `getDocument` tools and the `rag-search` command find records, and
how the server bounds what one call returns.

- [Modes](#modes)
- [Queries](#queries)
- [Filters](#filters)
- [Exact lookup](#exact-lookup)
- [Results](#results)
- [Limits](#limits)
- [The rag-search command](#the-rag-search-command)
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
  with, from `<data-dir>/models/<model>/`. When those files are missing or are not the files the
  collection was built with (their SHA-256 is in the manifest), `vector` and `hybrid` are an error
  that says so, and `lexical` keeps working. The first such search of a process loads the model,
  which takes a few seconds; a model whose files are replaced is seen after a restart.
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
bold words. `mode` says how the page was ranked. `total` counts all matches (fused candidates in
`vector` and `hybrid`); pass `nextOffset` as `offset` with the same arguments for the next page.

`getDocument` takes a hit's `collection` and `id` and returns the full passage with the document's
fields, its key and the record's position in the document.

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

## The rag-search command

Searches from the command line with the same search and the same limits, without a chat model.
A `vector` or `hybrid` search loads the embedding model in the command's process:

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
[filter expression](#filter-expressions). `--mode lexical|vector|hybrid` chooses the
[mode](#modes); without it the default applies, and the last line names the mode used.
`--lookup value` makes an [exact lookup](#exact-lookup) instead and takes no query, no filters, no
expression and no mode. Exit codes: 0 searched (also with no matches), 1 unexpected error, 2 wrong
usage, an unknown collection, filter field or mode, or an expression that does not parse, 4 an index could not be read, 5 the embedding model of a
collection is missing or is not the one it was built with.

## Known limits

- Lexical search does not find a passage that uses other words than the query; retry with
  synonyms, or use `vector` or `hybrid` on a collection with vectors.
- A vector covers at most `maxTokens` of a record (512 tokens for E5); the rest of a longer
  record is found by its words only.
- How well each mode finds the right passage has not been measured yet ([plan.md](plan.md)
  section 4, stage 4).
- The Snowball stemmer does not join irregular Russian forms (`письмо` and `писем`).
- Snippets are cut at sentence boundaries found in Markdown source, so a snippet may start with a
  heading marker or contain a whole table row.
