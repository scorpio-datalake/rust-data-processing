"""Shared CloudConnectors integration helpers."""

from __future__ import annotations

import os
import socket
import subprocess
import sys
import time
from pathlib import Path

CLOUD_DIR = Path(__file__).resolve().parent
INTEG_ROOT = CLOUD_DIR.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import DATA_DIR, count_lines, die, docker_command, log  # noqa: E402
from minio_common import load_minio_env, wait_for_minio  # noqa: E402


def load_cloud_env() -> None:
    for env_file in (CLOUD_DIR / ".env", CLOUD_DIR / ".env.example"):
        if env_file.is_file():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))
    load_minio_env()
    # Azurite emulator: drop stale/wrong account keys from a copied .env (object_store uses well-known key).
    if os.environ.get("AZURE_STORAGE_USE_EMULATOR", "").lower() in ("1", "true", "yes"):
        os.environ.pop("AZURE_STORAGE_CONNECTION_STRING", None)
        os.environ.pop("AZURE_STORAGE_ACCOUNT_KEY", None)
    repo = INTEG_ROOT.parent
    gcs_sa = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
    if gcs_sa and not Path(gcs_sa).is_file():
        rel = repo / gcs_sa
        if rel.is_file():
            os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = str(rel.resolve())


def _wait_port(host: str, port: int, label: str, attempts: int = 60) -> None:
    for _ in range(attempts):
        try:
            with socket.create_connection((host, port), timeout=2):
                log(f"{label} reachable on {host}:{port}.")
                return
        except OSError:
            time.sleep(1)
    die(f"{label} did not become ready on {host}:{port}")


_AZURITE_ACCOUNT = "devstoreaccount1"
# Well-known Azurite key (see Microsoft docs / object_store EMULATOR_ACCOUNT_KEY).
_AZURITE_KEY_B64 = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="


def _azure_create_container(*, host: str, port: int, container: str) -> None:
    """Create Azurite blob container via azure-storage-blob SDK."""
    conn = (
        "DefaultEndpointsProtocol=http;"
        f"AccountName={_AZURITE_ACCOUNT};"
        f"AccountKey={_AZURITE_KEY_B64};"
        f"BlobEndpoint=http://{host}:{port}/{_AZURITE_ACCOUNT};"
    )
    script = f"""
from azure.core.exceptions import ResourceExistsError
from azure.storage.blob import BlobServiceClient
conn = {conn!r}
client = BlobServiceClient.from_connection_string(conn)
try:
    client.create_container({container!r})
    print('ready')
except ResourceExistsError:
    print('exists')
"""
    candidates = [sys.executable]
    venv_py = INTEG_ROOT.parent / "python-wrapper" / ".venv" / "bin" / "python"
    if venv_py.is_file():
        candidates.insert(0, str(venv_py))
    for py in candidates:
        proc = subprocess.run([py, "-c", script], capture_output=True, text=True, check=False)
        if proc.returncode == 0 and proc.stdout.strip() in ("ready", "exists"):
            log(f"Azurite container `{container}` {proc.stdout.strip()}.")
            return
        if "No module named 'azure'" in (proc.stderr or ""):
            continue
        if proc.returncode == 0:
            return
    log("azure-storage-blob not available — skip Azurite container seed")


def seed_gcs_and_azure() -> None:
    """Create GCS bucket and Azure container on host (emulators publish host ports)."""
    gcs_port = os.environ.get("GCS_PORT", "4443")
    az_port = int(os.environ.get("AZURITE_BLOB_PORT", "10000"))
    subprocess.run(
        [
            "curl",
            "-sf",
            "-X",
            "POST",
            "-H",
            "Content-Type: application/json",
            "-d",
            '{"name":"rdp-cloud-gcs"}',
            f"http://127.0.0.1:{gcs_port}/storage/v1/b?project=rdp-integration",
        ],
        check=False,
    )
    _azure_create_container(host="127.0.0.1", port=az_port, container="rdp-cloud-azure")
    log("GCS bucket + Azure container seeded (if emulators were ready).")


def wait_for_cloud_stack() -> None:
    wait_for_minio()
    _wait_port("127.0.0.1", int(os.environ.get("GCS_PORT", "4443")), "fake-gcs-server")
    _wait_port("127.0.0.1", int(os.environ.get("AZURITE_BLOB_PORT", "10000")), "Azurite blob")
    _wait_port("127.0.0.1", int(os.environ.get("SFTP_PORT", "2222")), "SFTP")
    _wait_port("127.0.0.1", int(os.environ.get("FTP_PORT", "21")), "FTP")
    time.sleep(3)
    seed_gcs_and_azure()
    log("Cloud stack healthy (MinIO, GCS, Azure, SFTP, FTP).")


def start_cloud_stack(*, isolate: bool = True) -> None:
    from minio_common import isolate_docker_for_platform

    if isolate:
        isolate_docker_for_platform("CloudConnectors")
    log("Starting cloud storage stack (docker compose)...")
    subprocess.run(docker_command(["compose", "up", "-d"]), cwd=CLOUD_DIR, check=True)
    wait_for_cloud_stack()


def pick_uber_csv() -> Path:
    sample = DATA_DIR / "uber_nyc_pickups_sample.csv"
    full = DATA_DIR / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    die("Uber CSV missing — run download_uber_data.py --sample")


def expected_csv_rows(csv: Path) -> int:
    return count_lines(csv) - 1


__all__ = [
    "expected_csv_rows",
    "load_cloud_env",
    "pick_uber_csv",
    "start_cloud_stack",
    "wait_for_cloud_stack",
]
