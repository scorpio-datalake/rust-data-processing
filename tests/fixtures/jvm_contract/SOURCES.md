# JVM contract fixture bundle

Shared by JUnit (`JvmNativeContractScenarios`), `docs/java/examples`, and Rust `pipeline_run` tests.

| Kind | Path |
| --- | --- |
| Data | `data/three_rows.json` (also `../jvm_contract_three_rows.json` at repo root for older paths), `../jvm_contract_pipeline_part_*.json`, `../jvm_contract_ordered_part_*.csv` |
| Schemas | `schemas/*.schema.json` |
| Pipelines | `pipelines/*.pipeline.json` (`schema_ref`, `{{PLACEHOLDER}}`) |
| Ordered ingest | `payloads/ordered_paths_*.payload.json` |

**SQL doc example:** `pipelines/sql_query_dataset.pipeline.json` + `data/three_rows.json` — `tests/sql.rs`, `python-wrapper/tests/test_sql_queries_fixtures.py`, `docs/java/examples/SQLQueries.java`, JVM `DocsExampleNativeIntegrationTest`.

**DataFrame-centric doc example:** `pipelines/dataframe_centric_sql.pipeline.json` + `../jvm_contract_three_rows.json` — `tests/dataframe_centric_pipeline_fixtures.rs`, `python-wrapper/tests/test_dataframe_centric_pipeline_fixtures.py`, `docs/java/examples/DataFrameCentricPipeline.java`, JVM `runPipelineJsonDataFrameCentricSqlContract`.
