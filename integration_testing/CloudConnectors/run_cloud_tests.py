#!/usr/bin/env python3
"""SCRIPT: Cloud storage integration orchestrator (S3, GCS, Azure, SFTP, FTP)."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

CLOUD_DIR = Path(__file__).resolve().parent
INTEG_ROOT = CLOUD_DIR.parent
REPO_ROOT = INTEG_ROOT.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))
if str(CLOUD_DIR) not in sys.path:
    sys.path.insert(0, str(CLOUD_DIR))

from common import (  # noqa: E402
    FAILURE_FLAG,
    INTEGRATION_RUST_TEST_FILTER,
    LIBS_DIR,
    apply_env_sh,
    die,
    docker_command,
    integration_rust_test_cmd,
    log,
    log_test_failed,
    log_test_passed,
    log_test_summary,
    mark_test_failed,
    require_integration_libs,
    setup_integration_build_env,
    stage_integration_python_ext,
)
from cloud_common import expected_csv_rows, load_cloud_env, pick_uber_csv, start_cloud_stack  # noqa: E402

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
    log("=== Java cloud storage test (S3, GCS, Azure, SFTP, FTP) ===")
    if not shutil.which("mvn"):
        die("mvn required")
    _run_checked(["mvn", "-B", "-q", "test"], cwd=CLOUD_DIR / "java")


def run_python_test() -> None:
    log("=== Python cloud storage test ===")
    if not shutil.which("uv"):
        die("uv required")
    stage_integration_python_ext()
    py_wrapper = REPO_ROOT / "python-wrapper"
    venv_python = py_wrapper / ".venv" / "bin" / "python"
    if not venv_python.is_file():
        subprocess.run(["uv", "sync", "--group", "dev", "--quiet"], cwd=py_wrapper, check=True)
    env = os.environ.copy()
    env["PYTHONPATH"] = f"{CLOUD_DIR / 'tests'}:{CLOUD_DIR}:{_SCRIPTS}:{env.get('PYTHONPATH', '')}"
    _run_checked(
        [str(venv_python), "-m", "pytest", str(CLOUD_DIR / "tests" / "test_cloud_import.py"), "-q"],
        cwd=py_wrapper,
        env=env,
    )


def run_rust_test() -> None:
    log("=== Rust cloud storage test ===")
    setup_integration_build_env()
    manifest = CLOUD_DIR / "rust" / "Cargo.toml"
    _run_checked(
        integration_rust_test_cmd(manifest, INTEGRATION_RUST_TEST_FILTER["CloudConnectors"]),
        env=os.environ.copy(),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Cloud storage integration tests (Docker emulators).")
    parser.add_argument("--no-rancher", action="store_true", help="Docker already up; skip Rancher start.")
    parser.add_argument("--keep-cloud", action="store_true", help="Skip docker compose down.")
    parser.add_argument("--no-isolate", action="store_true", help="Do not stop other containers before start.")
    args = parser.parse_args(argv)

    try:
        load_cloud_env()
        require_integration_libs()
        os.environ["RUN_CLOUD_INTEGRATION"] = "1"
        os.environ["RDP_INTEGRATION_ROOT"] = str(INTEG_ROOT)
        apply_env_sh(LIBS_DIR / "java" / "env.sh")
        apply_env_sh(LIBS_DIR / "python" / "env.sh")
        apply_env_sh(LIBS_DIR / "rust" / "env.sh")

        if not args.no_rancher:
            subprocess.run(
                [sys.executable, str(_SCRIPTS / "rancher" / "start_rancher_desktop.py")],
                check=True,
            )

        start_cloud_stack(isolate=not args.no_isolate)
        csv = pick_uber_csv()
        log(f"Using CSV: {csv} ({expected_csv_rows(csv)} data rows)")

        failed = False
        results: list[tuple[str, bool]] = []
        for name, runner in (
            ("Java", run_java_test),
            ("Python", run_python_test),
            ("Rust", run_rust_test),
        ):
            try:
                runner()
                log_test_passed(name)
                results.append((name, True))
            except (subprocess.CalledProcessError, SystemExit):
                log_test_failed(name)
                results.append((name, False))
                failed = True
        log_test_summary(results)

        if not args.keep_cloud:
            subprocess.run(docker_command(["compose", "down"]), cwd=CLOUD_DIR, check=False)

        if not args.no_rancher:
            subprocess.run(
                [sys.executable, str(_SCRIPTS / "rancher" / "stop_rancher_desktop.py")],
                check=False,
            )

        if failed:
            mark_test_failed()
            die("Cloud storage integration tests failed")
        if FAILURE_FLAG.is_file():
            FAILURE_FLAG.unlink()
        log("All cloud storage integration tests passed.")
        return 0
    except Exception:
        mark_test_failed()
        raise


if __name__ == "__main__":
    raise SystemExit(main())
