# GHCN Daily station sample (JVM XML pipeline fixtures)

| File | Source |
| --- | --- |
| `ghcn_stations_sample.json` | First five rows derived from [NCEI GHCN-Daily `ghcnd-stations.txt`](https://www.ncei.noaa.gov/pub/data/ghcn/daily/ghcnd-stations.txt) (fixed-width station inventory). Downloaded 2026-05-15; committed for offline CI. |
| `ghcn_stations_intermediate.xml` | Reference XML in the **intermediate schema** (`stationCode`, `lat`, …) used by `rdp_ingest_xml_path` contract checks; pipeline tests generate XML dynamically and need not match this file byte-for-byte. |
| `schemas/*.schema.json` | Shared serde [`Schema`](../../../src/types.rs) definitions (JSON / XML / Parquet stages). |
| `pipelines/*.pipeline.json` | `rdp_run_pipeline_json` templates with `schema_ref` + `{{SOURCE_PATH}}` / `{{SINK_PATH}}` placeholders. Resolved by [`pipeline_spec`](../../../src/pipeline_spec.rs) in Rust and `PipelineFixtureSupport` in JVM tests. |
JUnit and examples **must not** download at runtime. Regenerate manually if the inventory format changes.

**Java doc example:** `docs/java/examples/GhcnJsonXmlParquetPipeline.java` (same bundle; CI via `DocsExampleNativeIntegrationTest`, `XmlGhcnPipelineContractTest`, Rust `tests/ghcn_json_xml_parquet_pipeline_fixtures.rs`, `jvm-sys` `run_pipeline_ghcn_json_xml_parquet_committed_fixture`, Python `test_ghcn_json_xml_parquet_pipeline_fixtures.py`).
