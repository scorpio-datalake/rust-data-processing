#!/usr/bin/env python3
"""SCRIPT: Oracle integration test orchestrator — invokes Java/Python/Rust tests; not a test itself."""

from __future__ import annotations

import argparse
import os
import shutil
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

from common import FAILURE_FLAG, LIBS_DIR, apply_env_sh, die, docker_command, load_cargo_env, log, mark_test_failed  # noqa: E402
from oracle_common import expected_csv_rows, load_oracle_env, pick_uber_csv, wait_for_oracle  # noqa: E402


def require_built_libs() -> None:
    missing = False
    if not (LIBS_DIR / "rust" / "env.sh").is_file():
        log("missing libs/rust — run build_libs/build_rust_lib.py")
        missing = True
    if not (LIBS_DIR / "java" / "env.sh").is_file():
        log("missing libs/java — run build_libs/build_java_lib.py")
        missing = True
    if not (LIBS_DIR / "python" / "env.sh").is_file():
        log("missing libs/python — run build_libs/build_python_lib.py")
        missing = True
    sample = INTEG_ROOT / "data" / "uber_nyc_pickups_sample.csv"
    full = INTEG_ROOT / "data" / "uber_nyc_pickups_apr2014.csv"
    if not sample.is_file() and not full.is_file():
        log("missing Uber CSV — run data_download/download_uber_data.py")
        missing = True
    if missing:
        die(
            "Build libraries and data first:\n"
            "  python3 integration_testing/scripts/build_libs/build_all_libs.py\n"
            "  python3 integration_testing/scripts/data_download/download_uber_data.py --sample"
        )


def run_java_test() -> None:
    log("=== Java import test ===")
    if not shutil.which("mvn"):
        die("mvn required for Java integration test")
    subprocess.run(["mvn", "-B", "-q", "test"], cwd=ORACLE_DIR / "java", check=True)


def run_python_test() -> None:
    log("=== Python import test ===")
    if not shutil.which("uv"):
        die("uv required for Python integration test")
    env = os.environ.copy()
    tests_dir = str(ORACLE_DIR / "tests")
    env["PYTHONPATH"] = f"{tests_dir}:{env.get('PYTHONPATH', '')}"
    subprocess.run(
        [
            "uv",
            "run",
            "pytest",
            str(ORACLE_DIR / "tests" / "test_oracle_import.py"),
            "-q",
        ],
        cwd=REPO_ROOT / "python-wrapper",
        env=env,
        check=True,
    )


def run_rust_test() -> None:
    log("=== Rust import test ===")
    load_cargo_env()
    subprocess.run(
        ["cargo", "test", "--release", "oracle_import_uber_csv", "--", "--nocapture"],
        cwd=ORACLE_DIR / "rust",
        check=True,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Oracle tri-language integration test runner (does not build libs or download data)."
    )
    parser.add_argument("--no-rancher", action="store_true", help="Oracle already up; skip Rancher start.")
    parser.add_argument("--keep-oracle", action="store_true", help="Skip docker compose down.")
    args = parser.parse_args(argv)

    try:
        load_oracle_env()
        require_built_libs()

        os.environ["RUN_ORACLE_INTEGRATION"] = "1"
        os.environ["RDP_INTEGRATION_ROOT"] = str(INTEG_ROOT)
        os.environ["RDP_ORACLE_ROOT"] = str(ORACLE_DIR)

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
