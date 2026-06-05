# CloudConnectors integration tests

Tri-language tests for **S3** (MinIO), **GCS** (fake-gcs-server), **Azure** (Azurite), **SFTP**, and **FTP**.

## What is tested

| Protocol | Docker service | Rust I/O | Test |
| -------- | -------------- | -------- | ---- |
| S3 | MinIO | `kind: object_store` export + `object_store_uris` read-back | Roundtrip row count |
| GCS | fake-gcs-server | same | Roundtrip row count |
| Azure | Azurite | same | Roundtrip row count |
| SFTP | atmoz/sftp | `file_transfer_uris` import | Seeded CSV row count |
| FTP | fauria/vsftpd | `file_transfer_uris` import | Seeded CSV row count |

Java and Python call `rdp_run_pipeline_json` via `librdp_jvm_sys` (same as other integration tests).

## Run

```bash
python3 integration_testing/scripts/build_libs/build_all_libs.py
python3 integration_testing/scripts/data_download/download_uber_data.py --sample
python3 integration_testing/CloudConnectors/run_cloud_tests.py
```

Logs: look for `PASSED: Java/Python/Rust integration test` and `All cloud storage integration tests passed.`

**Step-by-step (matches docs):** [`integration_testing_details.md`](../integration_testing_details.md) · [`docs/CONNECTORS.md`](../../docs/CONNECTORS.md) · [`docs/java/EXAMPLES.md`](../../docs/java/EXAMPLES.md) · [`docs/python/README.md`](../../docs/python/README.md)
