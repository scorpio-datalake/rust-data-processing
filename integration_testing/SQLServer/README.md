# SQL Server integration tests

Tri-language Uber CSV → **`kind: mssql`** sink → **`ingest_from_db`** row-count verify. Requires `rdp_jvm_sys --features full` (includes **`sink_mssql`**).

## Prerequisites

```bash
python3 integration_testing/scripts/build_libs/build_all_libs.py
python3 integration_testing/scripts/data_download/download_uber_data.py --sample
```

## Run

```bash
python3 integration_testing/SQLServer/run_mssql_tests.py
```

Docker: `mcr.microsoft.com/mssql/server:2022-latest` on port **1433**. Default SA URL in `.env.example` uses `encrypt=false&trust_server_certificate=true` for local dev.

```bash
docker compose -f integration_testing/SQLServer/docker-compose.yml down
python3 integration_testing/SQLServer/run_mssql_tests.py --no-rancher --keep-mssql
```

Shared schemas: `integration_testing/schema/uber_pickups.{schema,table}.json`.
