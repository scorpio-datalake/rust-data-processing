# Student ETL legacy fixture bundle

| Kind | Path |
| --- | --- |
| Schemas | `schemas/student_source.schema.json`, `lake_grade_stats.schema.json`, `postgres_courses.schema.json` |
| Pipelines | `legacy_student_etl.pipeline.json` (single `{{SOURCE_PATH}}`), `legacy_student_etl_three_paths.pipeline.json` (`{{PATH_A..C}}`) |
| Payloads | `ordered_ingest_dataset.payload.json`, `ordered_ingest_dataset_2paths.payload.json` |
| Data | `data/part-0000*.json`, `data/example_s3_json_source_paths.json` |

Used by `docs/java/examples/RDPOnlyETLExample.java` and `bindings/jvm-sys` `run_pipeline_legacy_student_etl_envelope`.

**Tests:** `tests/student_etl_fixtures.rs`, `python-wrapper/tests/test_student_etl_fixtures.py`, JVM `DocsExampleNativeIntegrationTest` (`studentEtlLegacyThreePaths…`, `studentEtlOrderedIngestTwoParts…`), `bindings/jvm-sys` `run_pipeline_legacy_student_etl_three_committed_parts`.
