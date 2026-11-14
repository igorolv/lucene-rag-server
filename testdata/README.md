# testdata

Synthetic knowledge of an invented billing system, used by the tests and safe to publish. No text
or name here comes from a real project; the formats and the layout of the files follow the first
real corpus the server is built for (`docs/decisions.md` D20).

| Path | What it is |
|---|---|
| `knowledge/articles/` | Russian articles with YAML front matter, split into `##` sections |
| `knowledge/playbooks/` | A Russian troubleshooting playbook |
| `knowledge/api/` | An English article without front matter |
| `knowledge/catalogs/<name>/` | TSV catalogs with a header line: messages of the code, a menu with empty cells and short rows; an SQL file with a free comment that generates a catalog |
| `knowledge/data/` | YAML descriptions: enumerations with a list of `values`, a table with a mapping of columns, `logs.yaml` with a list of `sources` |
| `knowledge/eval/` | A JSONL question set |
| `knowledge/queries/<area>/` | SQL queries with a header of `-- key: value` lines |

Tests assert on the wording of these files (word forms, `ё`, phrases, field values): change a file
together with the tests that read it.
