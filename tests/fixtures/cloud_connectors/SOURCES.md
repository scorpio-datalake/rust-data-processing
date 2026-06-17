# `cloud_connectors` fixtures

Committed URLs for **Snowflake**, **Databricks**, **Spark**, and **object store** (S3, GCS, Azure Blob / ADLS) JVM pipeline examples. Rust executes these via the `cloud_connectors` Cargo feature (`object_store` read/write).

| Path | Role |
| --- | --- |
| `schemas/id_name.schema.json` | Two-column ingest schema |
| `data/two_rows.json` | Local object for `file://` ingest demos |
| `cloud/*/incoming/two_rows.json` | Same data under cloud-style paths |
| `pipelines/platform_connectors.pipeline.json` | `object_store_uris` + platform sinks |
| `pipelines/object_store_sources_only.pipeline.json` | Cloud read URIs + local `parquet_file` sink |

Java: [`docs/java/examples/PlatformConnectorsPipelineExample.java`](../../docs/java/examples/PlatformConnectorsPipelineExample.java), [`ObjectStoreUrlsExample.java`](../../docs/java/examples/ObjectStoreUrlsExample.java).
