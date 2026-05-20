# Cross-language test fixtures

Rust (`pipeline_spec::PipelineBundle`), JVM (`PipelineJsonFixtures`), and Python (`tests.pipeline_fixture_support`) load the same JSON from here.

## Layout

| Bundle | Schemas | Pipelines | Notes |
| --- | --- | --- | --- |
| `ghcn/` | `schemas/` | `pipelines/` | JSON → XML → Parquet |
| `jvm_contract/` | `schemas/` | `pipelines/`, `payloads/` | JVM contract + doc examples |
| `people/` | `schemas/` | `pipelines/`, `payloads/` | `people.csv`, `people.json`, `people.xlsx` at parent |
| `student_etl/` | `schemas/` | `pipelines/`, `payloads/`, `data/` | Legacy student ETL |
| `watermark/schemas/` | events | `payloads/` (ingest options/response) | `../watermark_events.*` |
| `deep/schemas/` | seattle, job_runs | — | `../deep/*.csv`, `job-runs.json` |
| `sql_parity/` | `schemas/` | `queries/`, `data/` | JOIN SQL + side tables for `SQLQueries.java` |

Serde schemas use Pascal-case `data_type` (`Int64`, `Utf8`, …). Python tests lowercase via `load_schema_fields()`.

Path ingest with no extra options (format/watermark/sheet): use `PipelineJsonFixtures.defaultPathIngestOptionsJson()` (`"{}"`) in Java — do not add empty `*.options.json` files per bundle.
