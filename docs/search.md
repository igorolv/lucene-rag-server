# Search

How the `search` and `getDocument` tools and the `rag-search` command find records, and how the
server bounds what one call returns.

- [Queries](#queries)
- [Filters](#filters)
- [Results](#results)
- [Limits](#limits)
- [The rag-search command](#the-rag-search-command)
- [Known limits](#known-limits)

## Queries

Search is lexical (BM25) over the built collections named in the call.

- **Words are optional.** A record matching more of the words, or matching them in its breadcrumb
  (document title and headings), ranks higher. A whole question can be passed as it is.
- **Quotes make a phrase:** `"очередь разбора"` matches these words next to each other, in this
  order.
- **Word forms match** in Russian and English: `платежей` finds `платёж`, `retrying` finds
  `retries`. Every text is indexed with both stemmers, so the language is not declared.
- **Case** does not matter, and `ё` matches `е`.
- Common **stop words** (`и`, `в`, `the`, …) are ignored; a query of stop words alone finds
  nothing.
- **No wildcards, fuzzy matching or boolean operators.**
- A query has at most 1,000 characters.

## Filters

`filters` maps a field name to a value every result must have: `{"kind": "article"}`.

- Fields are the front matter keys of the documents ([collections.md](collections.md#markdown));
  `listCollections` returns the names available in each collection.
- A value matches exactly, without regard to case. A field with several values matches when one
  of them equals the filter.
- Several filters must all hold. An unknown field name is an error that lists the known ones.
- With filters the query may be omitted: the call then lists the matching records in index order.

## Results

`search` returns a page of hits, best first: `collection`, `id`, `breadcrumb`, `source` (the file,
relative to its source), `score` and a `snippet` — part of the passage around the matches, with
the matches in `**bold**`. `total` counts all matches; pass `nextOffset` as `offset` with the same
arguments for the next page.

`getDocument` takes a hit's `collection` and `id` and returns the full passage with the document's
fields, its key and the record's position in the document.

Several collections in one call are searched as one index, so their scores are comparable.

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

Searches from the command line with the same search and the same limits, without a model:

```bash
java -jar lucene-rag-server.jar rag-search docs@billing "отмена платежа" --limit 5
```

```bash
java -jar lucene-rag-server.jar rag-search docs@billing,api@billing --filter kind=playbook
```

The first argument is one or more collection names separated by commas; the remaining words are
the query. `--filter name=value` may be repeated. Exit codes: 0 searched (also with no matches),
1 unexpected error, 2 wrong usage or an unknown collection or filter field, 4 an index could not
be read.

## Known limits

- Lexical search does not find a passage that uses other words than the query; retry with
  synonyms. Vector search is planned ([plan.md](plan.md) section 4, stage 3).
- The Snowball stemmer does not join irregular Russian forms (`письмо` and `писем`).
- Snippets are cut at sentence boundaries found in Markdown source, so a snippet may start with a
  heading marker or contain a whole table row.
