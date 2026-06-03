"""Shared Oracle helpers for integration tests (library module — not a CLI entrypoint)."""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

ORACLE_DIR = Path(__file__).resolve().parent
INTEG_ROOT = ORACLE_DIR.parent
ORACLE_OCI_HOME = INTEG_ROOT / ".oracle" / "oci-home"
ORACLE_XE_IMAGE = "gvenzl/oracle-xe:21-slim"
_ORACLE_XE_HOME = "/opt/oracle/product/21c/dbhomeXE"
_ORACLE_OCI_EXTRACTS = (
    "lib",
    "nls",
    "network",
    "oracore/zoneinfo",
)
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import DATA_DIR, count_lines, die, docker_command, log  # noqa: E402


def _oci_home_from_env() -> Path | None:
    for var in ("ORACLE_HOME", "ORACLE_CLIENT_LIB_DIR"):
        val = os.environ.get(var)
        if not val:
            continue
        home = Path(val)
        lib = home / "lib" if home.name != "lib" else home
        if lib.is_dir() and any(lib.glob("libclntsh.so*")):
            return home if (home / "lib").is_dir() else home.parent
    for part in os.environ.get("LD_LIBRARY_PATH", "").split(":"):
        if not part:
            continue
        lib = Path(part)
        if lib.is_dir() and any(lib.glob("libclntsh.so*")):
            return lib.parent if (lib.parent / "nls").is_dir() else lib
    return None


def _apply_oracle_client_home(home: Path) -> None:
    os.environ["ORACLE_HOME"] = str(home)
    lib_dir = home / "lib"
    prefix = str(lib_dir)
    parts = [p for p in os.environ.get("LD_LIBRARY_PATH", "").split(":") if p]
    if prefix not in parts:
        os.environ["LD_LIBRARY_PATH"] = (
            f"{prefix}:{os.environ['LD_LIBRARY_PATH']}" if parts else prefix
        )


def _oci_home_ready(home: Path) -> bool:
    return (home / "lib" / "libclntsh.so.21.1").is_file() and (home / "nls").is_dir()


def ensure_oracle_oci_libs() -> Path:
    """Make Oracle OCI shared libraries available for Rust (oracle crate / ConnectorX).

    Rust reset/load uses the ``oracle`` crate; ``verify_row_count`` uses ConnectorX's
    Oracle source — both need ``libclntsh.so`` plus NLS/timezone data. Java JDBC and
    python-oracledb thin mode do not. When Instant Client is not installed, cache a
    client bundle under ``integration_testing/.oracle/oci-home/`` from the same XE
    image as docker-compose.
    """
    found = _oci_home_from_env()
    if found is not None and _oci_home_ready(found):
        log(f"Using Oracle OCI client at {found}")
        _apply_oracle_client_home(found)
        return found

    home = ORACLE_OCI_HOME
    if _oci_home_ready(home):
        log(f"Using cached Oracle OCI client at {home}")
        _apply_oracle_client_home(home)
        return home

    log(f"Extracting Oracle OCI client from {ORACLE_XE_IMAGE} into {home}...")
    home.mkdir(parents=True, exist_ok=True)
    create = subprocess.run(
        docker_command(["create", "-e", "ORACLE_RANDOM_PASSWORD=yes", ORACLE_XE_IMAGE]),
        capture_output=True,
        text=True,
        check=True,
    )
    cid = create.stdout.strip()
    try:
        for rel in _ORACLE_OCI_EXTRACTS:
            remote = f"{_ORACLE_XE_HOME}/{rel}"
            if rel == "oracore/zoneinfo":
                dest_parent = home / "oracore"
                dest_parent.mkdir(parents=True, exist_ok=True)
                cp_dest = dest_parent
            else:
                cp_dest = home
            cp = subprocess.run(
                docker_command(["cp", f"{cid}:{remote}", str(cp_dest)]),
                capture_output=True,
                text=True,
            )
            if cp.returncode != 0:
                die(
                    "Failed to extract Oracle OCI client for Rust tests.\n"
                    f"  image: {ORACLE_XE_IMAGE}\n"
                    f"  path:  {rel}\n"
                    f"  error: {cp.stderr.strip() or cp.stdout.strip()}\n"
                    "Install Oracle Instant Client and set ORACLE_HOME, or fix Docker."
                )
    finally:
        subprocess.run(docker_command(["rm", cid]), capture_output=True)

    if not _oci_home_ready(home):
        die(f"Oracle OCI extract incomplete under {home}")

    log(f"Oracle OCI client ready at {home}")
    _apply_oracle_client_home(home)
    return home


def load_oracle_env() -> None:
    for env_file in (ORACLE_DIR / ".env", ORACLE_DIR / ".env.example"):
        if env_file.is_file():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))
            break
    if not os.environ.get("ORACLE_CONNECT_URL"):
        die("Set ORACLE_CONNECT_URL in Oracle/.env")


def wait_for_oracle(attempts: int = 60) -> None:
    log("Waiting for Oracle container health...")
    compose = ORACLE_DIR / "docker-compose.yml"
    for _ in range(attempts):
        ps = subprocess.run(
            docker_command(["compose", "-f", str(compose), "ps", "--status", "running"]),
            capture_output=True,
            text=True,
        )
        if ps.returncode == 0 and "oracle" in ps.stdout:
            health = subprocess.run(
                docker_command(
                    [
                        "compose",
                        "-f",
                        str(compose),
                        "exec",
                        "-T",
                        "oracle",
                        "healthcheck.sh",
                    ]
                ),
                capture_output=True,
            )
            if health.returncode == 0:
                log("Oracle ready.")
                return
        time.sleep(5)
    die(f"Oracle container not healthy after {attempts} attempts")


def pick_uber_csv() -> Path:
    sample = DATA_DIR / "uber_nyc_pickups_sample.csv"
    full = DATA_DIR / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    die("Uber CSV missing — run integration_testing/scripts/data_download/download_uber_data.py")


def expected_csv_rows(csv: Path) -> int:
    return count_lines(csv) - 1
