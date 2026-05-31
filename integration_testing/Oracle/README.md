# Oracle integration testing (local Docker)

Tri-language RDP connector tests against a real Oracle instance via ConnectorX `oracle://` URLs.

## Step 1 — Container runtime (Rancher Desktop or Docker Engine)

**Workstation (macOS / Linux desktop):** use [Rancher Desktop](https://docs.rancherdesktop.io/) for on-demand Docker.

**Headless Linux server (GCP, CI):** Rancher Desktop requires a GUI and `/dev/kvm`. Use **Docker Engine** instead — the integration scripts detect native Docker automatically when `rdctl` is absent.

```bash
# Verify install; apply recommended settings (no login autostart, no background start)
python3 integration_testing/scripts/rancher/check_rancher_desktop.py --configure

# Start Rancher only when running integration tests
python3 integration_testing/scripts/rancher/start_rancher_desktop.py

# Stop when finished (avoid background VM)
python3 integration_testing/scripts/rancher/stop_rancher_desktop.py
```

**Settings applied by `--configure`:**

| Setting | Value | Why |
| --- | --- | --- |
| `application.autoStart` | `false` | Do not start Rancher at OS login |
| `application.startInBackground` | `false` | Do not hide in tray at startup |
| `application.window.quitOnClose` | `true` | Quit when window closes |

## Step 2 — Oracle database (Docker Compose)

### Local Docker is allowed for dev/test

**Oracle Database Express Edition (XE)** and **Oracle Database Free** may be used in Docker for **development and testing**. This compose file uses [`gvenzl/oracle-xe`](https://hub.docker.com/r/gvenzl/oracle-xe) (21c XE, slim image).

That is sufficient for RDP **`db_connectorx`** / **`ingest_from_db`** integration tests (read/write smoke tests, Uber CSV load, etc.).

**Not covered here:**

- **Oracle Enterprise Edition** production licensing
- Full performance / RAC / Data Guard scenarios

If your organization **cannot** run Oracle in local Docker (policy or edition requirements), use a **one-time GCP** (or Oracle Cloud) test instance instead and set:

```bash
export ORACLE_CONNECT_URL='oracle://user:pass@your-host:1521/SERVICE'
```

Then skip `docker compose` and point `run_tests.py` at that URL (future step).

### Start Oracle XE

```bash
python3 integration_testing/scripts/rancher/start_rancher_desktop.py

cd integration_testing/Oracle
cp .env.example .env   # optional
docker compose up -d
docker compose logs -f oracle   # wait for "DATABASE IS READY TO USE!"
```

**ConnectorX URL** (default `.env.example`):

```text
oracle://etl_user:rdp_test_etl@localhost:1521/XEPDB1
```

| Variable | Default | Role |
| --- | --- | --- |
| `ORACLE_APP_USER` | `etl_user` | Application schema (created by image) |
| `ORACLE_APP_PASSWORD` | `rdp_test_etl` | App password |
| `ORACLE_PASSWORD` | `rdp_test_sys` | `SYS` / admin password |
| `ORACLE_PORT` | `1521` | Host port |

### Tear down

```bash
cd integration_testing/Oracle
docker compose down          # keep data volume
docker compose down -v       # remove data volume (fresh DB)
python3 integration_testing/scripts/rancher/stop_rancher_desktop.py
```

## Next steps (see `notes.txt`)

3. Build Rust / Java / Python libs (`integration_testing/scripts/build_libs/`)  
4. Uber NYC CSV in `integration_testing/data/`  
5. `run_tests.py` — start Rancher → compose → tri-language import tests
