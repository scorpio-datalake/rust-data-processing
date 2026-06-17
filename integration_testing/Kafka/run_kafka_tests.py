#!/usr/bin/env python3
"""SCRIPT: Kafka streaming integration orchestrator (Redpanda Docker)."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

KAFKA_DIR = Path(__file__).resolve().parent
INTEG_ROOT = KAFKA_DIR.parent
REPO_ROOT = INTEG_ROOT.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))
if str(KAFKA_DIR) not in sys.path:
    sys.path.insert(0, str(KAFKA_DIR))

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
from kafka_common import expected_csv_rows, load_kafka_env, pick_uber_csv, start_kafka_stack  # noqa: E402

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


def ensure_kafka_jvm_lib() -> None:
    """Integration JVM must include kafka FFI symbols."""
    lib = LIBS_DIR / "java" / "librdp_jvm_sys.so"
    if not lib.is_file():
        die("missing libs/java/librdp_jvm_sys.so — run build_all_libs.py")
    repo_scripts = REPO_ROOT / "scripts"
    if str(repo_scripts) not in sys.path:
        sys.path.insert(0, str(repo_scripts))
    from connector_features import JVM_FEATURES_KAFKA  # noqa: E402

    stamp = LIBS_DIR / "java" / ".kafka_built_at"
    if stamp.is_file():
        return
    log(f"Rebuilding rdp_jvm_sys with --features {JVM_FEATURES_KAFKA} for Kafka tests...")
    setup_integration_build_env()
    subprocess.run(
        [
            "cargo",
            "build",
            "--release",
            "--locked",
            "--manifest-path",
            str(REPO_ROOT / "bindings" / "jvm-sys" / "Cargo.toml"),
            "--features",
            JVM_FEATURES_KAFKA,
        ],
        check=True,
        cwd=REPO_ROOT,
        env=os.environ.copy(),
    )
    from common import native_jvm_src  # noqa: E402

    src = native_jvm_src()
    if not src.is_file():
        die(f"kafka JVM build missing {src}")
    shutil.copy2(src, lib)
    stamp.write_text("", encoding="utf-8")


def run_java_test() -> None:
    log("=== Java Kafka stream test (one row per message) ===")
    if not shutil.which("mvn"):
        die("mvn required")
    _run_checked(["mvn", "-B", "-q", "test"], cwd=KAFKA_DIR / "java")


def run_python_test() -> None:
    log("=== Python Kafka stream test ===")
    if not shutil.which("uv"):
        die("uv required")
    stage_integration_python_ext()
    py_wrapper = REPO_ROOT / "python-wrapper"
    venv_python = py_wrapper / ".venv" / "bin" / "python"
    if not venv_python.is_file():
        subprocess.run(["uv", "sync", "--group", "dev", "--quiet"], cwd=py_wrapper, check=True)
    env = os.environ.copy()
    env["PYTHONPATH"] = f"{KAFKA_DIR / 'tests'}:{KAFKA_DIR}:{_SCRIPTS}:{env.get('PYTHONPATH', '')}"
    _run_checked(
        [str(venv_python), "-m", "pytest", str(KAFKA_DIR / "tests" / "test_kafka_stream.py"), "-q"],
        cwd=py_wrapper,
        env=env,
    )


def run_rust_test() -> None:
    log("=== Rust Kafka stream test ===")
    setup_integration_build_env()
    manifest = KAFKA_DIR / "rust" / "Cargo.toml"
    _run_checked(
        integration_rust_test_cmd(manifest, INTEGRATION_RUST_TEST_FILTER["Kafka"]),
        env=os.environ.copy(),
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Kafka streaming integration tests (Redpanda Docker).")
    parser.add_argument("--no-rancher", action="store_true", help="Docker already up; skip Rancher start.")
    parser.add_argument("--keep-kafka", action="store_true", help="Skip docker compose down.")
    parser.add_argument("--no-isolate", action="store_true", help="Do not stop other containers before start.")
    args = parser.parse_args(argv)

    try:
        load_kafka_env()
        require_integration_libs()
        ensure_kafka_jvm_lib()
        os.environ["RUN_KAFKA_INTEGRATION"] = "1"
        os.environ["RDP_INTEGRATION_ROOT"] = str(INTEG_ROOT)
        apply_env_sh(LIBS_DIR / "java" / "env.sh")
        apply_env_sh(LIBS_DIR / "python" / "env.sh")
        apply_env_sh(LIBS_DIR / "rust" / "env.sh")

        if not args.no_rancher:
            subprocess.run(
                [sys.executable, str(_SCRIPTS / "rancher" / "start_rancher_desktop.py")],
                check=True,
            )

        start_kafka_stack(isolate=not args.no_isolate)
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

        if not args.keep_kafka:
            subprocess.run(docker_command(["compose", "down"]), cwd=KAFKA_DIR, check=False)

        if not args.no_rancher:
            subprocess.run(
                [sys.executable, str(_SCRIPTS / "rancher" / "stop_rancher_desktop.py")],
                check=False,
            )

        if failed:
            mark_test_failed()
            die("Kafka integration tests failed")
        if FAILURE_FLAG.is_file():
            FAILURE_FLAG.unlink()
        log("All Kafka integration tests passed.")
        return 0
    except Exception:
        mark_test_failed()
        raise


if __name__ == "__main__":
    raise SystemExit(main())
