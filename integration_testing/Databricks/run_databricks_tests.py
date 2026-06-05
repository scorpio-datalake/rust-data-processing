#!/usr/bin/env python3
"""SCRIPT: Databricks S3 warehouse integration orchestrator (MinIO Docker)."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

DATABRICKS_DIR = Path(__file__).resolve().parent
INTEG_ROOT = DATABRICKS_DIR.parent
REPO_ROOT = INTEG_ROOT.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))
if str(DATABRICKS_DIR) not in sys.path:
    sys.path.insert(0, str(DATABRICKS_DIR))

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
from platform_deps import ensure_platform_sql_deps  # noqa: E402
from databricks_common import (  # noqa: E402
    expected_csv_rows,
    load_databricks_env,
    pick_uber_csv,
    prepare_staging,
)

SPARK_DIR = INTEG_ROOT / "Spark"
if str(SPARK_DIR) not in sys.path:
    sys.path.insert(0, str(SPARK_DIR))
from spark_common import start_spark_stack  # noqa: E402

_active_child: subprocess.Popen[bytes] | None = None


def _run_checked(cmd: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    global _active_child
    merged = os.environ.copy()
    if env:
        merged.update(env)
    _active_child = subprocess.Popen(cmd, cwd=cwd, env=merged, start_new_session=True)
    rc = _active_child.wait()
    _active_child = None
    if rc != 0:
        raise subprocess.CalledProcessError(rc, cmd)


def run_java_test() -> None:
    log("=== Java Databricks S3 warehouse test ===")
    if not shutil.which("mvn"):
        die("mvn required")
    _run_checked(["mvn", "-B", "-q", "test"], cwd=DATABRICKS_DIR / "java")


def run_python_test() -> None:
    log("=== Python Databricks S3 warehouse test ===")
    if not shutil.which("uv"):
        die("uv required")
    stage_integration_python_ext()
    py_wrapper = REPO_ROOT / "python-wrapper"
    venv_python = py_wrapper / ".venv" / "bin" / "python"
    if not venv_python.is_file():
        subprocess.run(["uv", "sync", "--group", "dev", "--quiet"], cwd=py_wrapper, check=True)
    env = os.environ.copy()
    env["PYTHONPATH"] = f"{DATABRICKS_DIR / 'tests'}:{DATABRICKS_DIR}:{_SCRIPTS}:{env.get('PYTHONPATH', '')}"
    _run_checked(
        [str(venv_python), "-m", "pytest", str(DATABRICKS_DIR / "tests" / "test_databricks_import.py"), "-q"],
        cwd=py_wrapper,
        env=env,
    )


def run_rust_test() -> None:
    log("=== Rust Databricks S3 warehouse test ===")
    setup_integration_build_env()
    manifest = DATABRICKS_DIR / "rust" / "Cargo.toml"
    _run_checked(
        integration_rust_test_cmd(manifest, INTEGRATION_RUST_TEST_FILTER["Databricks"]),
        env=os.environ.copy(),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Databricks S3 warehouse integration tests (MinIO Docker).")
    parser.add_argument("--no-rancher", action="store_true", help="Docker already up; skip Rancher start.")
    parser.add_argument("--keep-minio", action="store_true", help="Skip docker compose down.")
    parser.add_argument("--no-isolate", action="store_true", help="Do not stop other containers before start.")
    args = parser.parse_args(argv)

    try:
        load_databricks_env()
        ensure_platform_sql_deps()
        require_integration_libs()
        os.environ["RUN_DATABRICKS_INTEGRATION"] = "1"
        os.environ["RDP_INTEGRATION_ROOT"] = str(INTEG_ROOT)
        apply_env_sh(LIBS_DIR / "java" / "env.sh")
        apply_env_sh(LIBS_DIR / "python" / "env.sh")
        apply_env_sh(LIBS_DIR / "rust" / "env.sh")

        if not args.no_rancher:
            subprocess.run(
                [sys.executable, str(_SCRIPTS / "rancher" / "start_rancher_desktop.py")],
                check=True,
            )

        start_spark_stack(isolate=not args.no_isolate)
        prepare_staging()
        csv = pick_uber_csv()
        log(f"Using CSV: {csv} ({expected_csv_rows(csv)} data rows)")

        failed = False
        for runner in (run_java_test, run_python_test, run_rust_test):
            try:
                runner()
            except (subprocess.CalledProcessError, SystemExit):
                failed = True

        if not args.keep_minio:
            subprocess.run(docker_command(["compose", "down"]), cwd=SPARK_DIR, check=False)

        if not args.no_rancher:
            subprocess.run(
                [sys.executable, str(_SCRIPTS / "rancher" / "stop_rancher_desktop.py")],
                check=False,
            )

        if failed:
            mark_test_failed()
            die("Databricks integration tests failed")
        if FAILURE_FLAG.is_file():
            FAILURE_FLAG.unlink()
        log("All Databricks integration tests passed.")
        return 0
    except Exception:
        mark_test_failed()
        raise


if __name__ == "__main__":
    raise SystemExit(main())
