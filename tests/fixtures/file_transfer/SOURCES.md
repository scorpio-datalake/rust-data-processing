# `file_transfer` fixtures

SFTP / FTP / FTPS pipeline examples. CI runs FTP against a loopback server in `tests/file_transfer_ftp_integration.rs` (requires `--features cloud_connectors`).

| File | Purpose |
| --- | --- |
| `data/two_rows.json` | Remote file payload (JSON array) |
| `schemas/id_name.schema.json` | Ingest schema |
| `pipelines/ftp_sources_only.pipeline.json` | `sources.file_transfer_uris` + local parquet sink |

Placeholders: `{{FTP_URI}}`, `{{SINK_PATH}}`.
