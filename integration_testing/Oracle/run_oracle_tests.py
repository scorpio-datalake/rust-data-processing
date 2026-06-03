#!/usr/bin/env python3
"""SCRIPT: Oracle integration test orchestrator — invokes Java/Python/Rust tests; not a test itself."""

from __future__ import annotations

import argparse
import os
import shutil
import signal
import subprocess
import sys
from pathlib import Path

ORACLE_DIR = Path(__file__).resolve().parent
INTEG_ROOT = ORACLE_DIR.parent
REPO_ROOT = INTEG_ROOT.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))
if str(ORACLE_DIR) not in sys.path:
    sys.path.insert(0, str(ORACLE_DIR))

from common import (  # noqa: E402
    FAILURE_FLAG,
    INTEGRATION_RUST_TEST_FILTER,
    LIBS_DIR,
    apply_env_sh,
    die,
    docker_command,
    integration_rust_test_cmd,
    log,
    mark_test_failed,
    require_integration_libs,
    setup_integration_build_env,
    stage_integration_python_ext,
)
from oracle_common import (  # noqa: E402
    ensure_oracle_oci_libs,
    expected_csv_rows,
    load_oracle_env,
    pick_uber_csv,
    wait_for_oracle,
)

_active_child: subprocess.Popen[bytes] | None = None


def _terminate_active_child() -> None:
    global _active_child
    if _active_child is None or _active_child.poll() is not None:
        return
    log("Stopping active test subprocess...")
    try:
        os.killpg(_active_child.pid, signal.SIGTERM)
    except ProcessLookupError:
        _active_child.terminate()
    try:
        _active_child.wait(timeout=5)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(_active_child.pid, signal.SIGKILL)
        except ProcessLookupError:
            _active_child.kill()
        _active_child.wait(timeout=5)


def _handle_stop(signum: int, _frame: object) -> None:
    _terminate_active_child()
    raise SystemExit(128 + signum)


def _run_checked(cmd: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    global _active_child
    merged = os.environ.copy()
    if env:
        merged.update(env)
    _active_child = subprocess.Popen(cmd, cwd=cwd, env=merged, start_new_session=True)
    try:
        rc = _active_child.wait()
    finally:
        _active_child = None
    if rc != 0:
        raise subprocess.CalledProcessError(rc, cmd)


def require_built_libs() -> None:
    require_integration_libs()


def run_java_test() -> None:
    log("=== Java import test (RDP pipeline → kind: oracle sink) ===")
    if not shutil.which("mvn"):
        die("mvn required for Java integration test")
    _run_checked(["mvn", "-B", "-q", "test"], cwd=ORACLE_DIR / "java")
    log("Java import test passed.")


def run_python_test() -> None:
    log("=== Python import test (rdp_pipeline → kind: oracle sink → ingest_from_db verify) ===")
    if not shutil.which("uv"):
        die("uv required for Python integration test")
    stage_integration_python_ext()
    py_wrapper = REPO_ROOT / "python-wrapper"
    venv_python = py_wrapper / ".venv" / "bin" / "python"
    if not venv_python.is_file():
        subprocess.run(
            ["uv", "sync", "--group", "dev", "--quiet"],
            cwd=py_wrapper,
            check=True,
        )
    env = os.environ.copy()
    tests_dir = str(ORACLE_DIR / "tests")
    scripts_dir = str(_SCRIPTS)
    env["PYTHONPATH"] = f"{tests_dir}:{scripts_dir}:{env.get('PYTHONPATH', '')}"
    _run_checked(
        [
            str(venv_python),
            "-m",
            "pytest",
            str(ORACLE_DIR / "tests" / "test_oracle_import.py"),
            "-q",
        ],
        cwd=py_wrapper,
        env=env,
    )
    log("Python import test passed.")


def run_rust_test() -> None:
    """Execute the smoke test only — compile in build_rust_lib.py, not here."""
    log("=== Rust import test (rdp_run_pipeline_json → kind: oracle sink → ingest_from_db verify) ===")
    setup_integration_build_env()
    env = os.environ.copy()
    log(f"Using CARGO_TARGET_DIR={env.get('CARGO_TARGET_DIR', '(unset)')}")
    manifest = ORACLE_DIR / "rust" / "Cargo.toml"
    test_filter = INTEGRATION_RUST_TEST_FILTER["Oracle"]
    _run_checked(integration_rust_test_cmd(manifest, test_filter), env=env)
    log("Rust import test passed.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Oracle tri-language integration test runner (does not build libs or download data)."
    )
    parser.add_argument("--no-rancher", action="store_true", help="Oracle already up; skip Rancher start.")
    parser.add_argument("--keep-oracle", action="store_true", help="Skip docker compose down.")
    args = parser.parse_args(argv)

    try:
        signal.signal(signal.SIGTERM, _handle_stop)
        signal.signal(signal.SIGINT, _handle_stop)
        load_oracle_env()
        require_built_libs()

        os.environ["RUN_ORACLE_INTEGRATION"] = "1"
        os.environ["RDP_INTEGRATION_ROOT"] = str(INTEG_ROOT)
        os.environ["RDP_ORACLE_ROOT"] = str(ORACLE_DIR)
        os.environ["RDP_INTEGRATION_DATASET_SCHEMA"] = str(
            INTEG_ROOT / "schema" / "uber_pickups.schema.json"
        )
        os.environ["RDP_INTEGRATION_TABLE_SPEC"] = str(
            INTEG_ROOT / "schema" / "uber_pickups.table.json"
        )

        apply_env_sh(LIBS_DIR / "java" / "env.sh")
        apply_env_sh(LIBS_DIR / "python" / "env.sh")
        apply_env_sh(LIBS_DIR / "rust" / "env.sh")

        csv = pick_uber_csv()
        log(f"Using CSV: {csv} ({expected_csv_rows(csv)} data rows)")

        if not args.no_rancher:
            subprocess.run(
                [sys.executable, str(_SCRIPTS / "rancher" / "start_rancher_desktop.py")],
                check=True,
            )

        log("Starting Oracle (docker compose)...")
        subprocess.run(docker_command(["compose", "up", "-d"]), cwd=ORACLE_DIR, check=True)
        wait_for_oracle()

        # Verify uses RDP ingest_from_db (ConnectorX; needs Python/Rust integration_full / db).
        ensure_oracle_oci_libs()

        failed = False
        for runner in (run_java_test, run_python_test, run_rust_test):
            try:
                runner()
            except (subprocess.CalledProcessError, SystemExit):
                failed = True

        if not args.keep_oracle:
            subprocess.run(
                docker_command(["compose", "down"]),
                cwd=ORACLE_DIR,
                check=False,
            )

        if not args.no_rancher:
            subprocess.run(
                [sys.executable, str(_SCRIPTS / "rancher" / "stop_rancher_desktop.py")],
                check=False,
            )

        if failed:
            mark_test_failed()
            die("One or more Oracle integration tests failed")

        if FAILURE_FLAG.is_file():
            FAILURE_FLAG.unlink()
        log("All Oracle integration tests passed.")
        return 0
    except SystemExit:
        mark_test_failed()
        raise
    except Exception:
        mark_test_failed()
        raise


if __name__ == "__main__":
    raise SystemExit(main())
