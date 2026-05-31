#!/usr/bin/env python3
"""SCRIPT: integration tooling — not a pytest/cargo/junit test target."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from build_libs import build_rust_lib  # noqa: E402
from common import (  # noqa: E402
    JAVA_STAMP,
    LIBS_DIR,
    REPO_ROOT,
    die,
    ensure_linux_native_deps,
    jvm_lib_dest,
    load_cargo_env,
    log,
    mark_built,
    native_jvm_src,
    needs_rebuild,
    require_tool,
    run,
    write_java_env,
)

JAVA_WATCH_PATHS = [
    "bindings/jvm-sys",
    "bindings/java/rust-data-processing-jvm",
    "src",
    "Cargo.toml",
    "Cargo.lock",
]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build rdp_jvm_sys + JVM JAR for Oracle integration (db_connectorx)."
    )
    parser.add_argument("--force", action="store_true", help="Force rebuild.")
    args = parser.parse_args(argv)

    if args.force:
        import os

        os.environ["INTEG_FORCE_REBUILD"] = "1"

    load_cargo_env()
    ensure_linux_native_deps()
    require_tool("cargo")

    build_rust_lib.main(["--force"] if args.force else [])

    if needs_rebuild(JAVA_STAMP, JAVA_WATCH_PATHS):
        log("Building rdp_jvm_sys (--release --features full,db_connectorx)...")
        run(
            [
                "cargo",
                "build",
                "--release",
                "--locked",
                "--manifest-path",
                str(REPO_ROOT / "bindings" / "jvm-sys" / "Cargo.toml"),
                "--features",
                "full,db_connectorx",
            ]
        )

        if shutil.which("python3"):
            run([sys.executable, str(REPO_ROOT / "scripts" / "check_jvm_ffi_manifest.py")])

        if shutil.which("mvn"):
            log("Maven package (skip tests) for rust-data-processing-jvm...")
            run(
                ["mvn", "-B", "-q", "-DskipTests", "package"],
                cwd=REPO_ROOT / "bindings" / "java" / "rust-data-processing-jvm",
            )
            jar_dir = REPO_ROOT / "bindings" / "java" / "rust-data-processing-jvm" / "target"
            jar_src = next(
                (
                    p
                    for p in jar_dir.glob("rust-data-processing-jvm-*.jar")
                    if p.is_file()
                    and not p.name.endswith("-sources.jar")
                    and not p.name.endswith("-javadoc.jar")
                ),
                None,
            )
            if jar_src is not None:
                shutil.copy2(jar_src, LIBS_DIR / "java" / "rust-data-processing-jvm.jar")
            else:
                log("WARN: JVM JAR not found after mvn package; native lib still copied.")
        else:
            log("WARN: mvn not found; skipping JAR copy (native lib only).")

        src = native_jvm_src()
        dest = jvm_lib_dest()
        if not src.is_file():
            die(f"native library missing after build: {src}")
        shutil.copy2(src, dest)
        mark_built(JAVA_STAMP)
        log(f"Java native lib → {dest}")
    else:
        log("Java lib up to date (skip rebuild). Use --force to rebuild.")
        dest = jvm_lib_dest()
        if not dest.is_file():
            die(f"stamp exists but {dest} missing — run with --force")

    write_java_env()
    log(f"Wrote {LIBS_DIR / 'java' / 'env.sh'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
