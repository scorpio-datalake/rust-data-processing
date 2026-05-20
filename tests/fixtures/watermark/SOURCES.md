# Watermark ingest fixtures

| File | Role |
| --- | --- |
| `../watermark_events.csv` / `.json` | Incremental ingest samples |
| `schemas/events.schema.json` | Shared schema (`id`, `ts`) |
| `payloads/csv_watermark_ingest.body.json` | `schema_ref` + watermark options + response (paths added in Java after scan) |
| `payloads/directory_scan_two_csv.payload.json` | Same with `{{PATH_A}}` / `{{PATH_B}}` for fixed two-file demos |
| `payloads/csv_watermark_dataset.options.json` | Options fragment (legacy; prefer `csv_watermark_ingest.body.json`) |
| `payloads/csv_watermark_dataset.response.json` | Response fragment (legacy) |

**Tests:** `tests/path_from_directory_scan_fixtures.rs`, `python-wrapper/tests/test_path_from_directory_scan_fixtures.py`, `tests/ordered_batch_ingestion.rs`, JVM `DocsExampleNativeIntegrationTest#pathFromDirectoryScanWatermarkMatchesDocsExample`, parity `rdp_parity_watermark_mirror`.
