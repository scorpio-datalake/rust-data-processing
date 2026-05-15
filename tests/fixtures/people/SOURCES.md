# People fixture schemas

| File | Role |
| --- | --- |
| `../people.csv`, `../people.json`, `../people.xlsx` | Ingest samples |
| `schemas/*.schema.json` | Shared serde schemas for JVM / Python tests |
| `payloads/json_path_dataset.payload.json` | `rdp_ingest_ordered_paths_json` for `people.json` |
| `payloads/csv_path_dataset.payload.json` | Same for `people.csv` |
| `payloads/excel_sheet_dataset.payload.json` | Excel + `people_flat` schema |
| `payloads/json_path_ingest.options.json`, `csv_path_ingest.options.json` | Format options for path FFI (non-empty only) |
| `pipelines/csv_to_parquet.pipeline.json` | CSV → `parquet_file` (Parquet round-trip demo) |
