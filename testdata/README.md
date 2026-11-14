# testdata

Synthetic knowledge of an invented billing system, used by the tests and safe to publish. No text
or name here comes from a real project; the formats and the layout of the files follow the first
real corpus the server is built for (`docs/decisions.md` D20).

| Path | What it is |
|---|---|
| `knowledge/articles/` | Russian articles with YAML front matter, split into `##` sections |
| `knowledge/playbooks/` | A Russian troubleshooting playbook |
| `knowledge/api/` | An English article without front matter |

Tests assert on the wording of these files (word forms, `ё`, phrases, field values): change a file
together with the tests that read it.
