# testdata

Synthetic knowledge of an invented billing system, used by the tests and safe to publish. Nothing
here comes from a real project.

| Path | What it is |
|---|---|
| `knowledge/articles/` | Russian articles with YAML front matter, split into `##` sections |
| `knowledge/playbooks/` | A Russian troubleshooting playbook |
| `knowledge/api/` | An English article without front matter |

Tests assert on the wording of these files (word forms, `ё`, phrases, field values): change a file
together with the tests that read it.
