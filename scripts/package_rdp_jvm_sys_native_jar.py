#!/usr/bin/env python3
"""Package a prebuilt rdp_jvm_sys binary as a Maven classifier JAR (META-INF/native/)."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VERSION_FILE = REPO / "bindings" / "java" / "VERSION"

# Classifier → filename inside META-INF/native/ (must match RdpNativeJson.nativeLibraryBasename() on that OS).
NATIVE_BASENAMES: dict[str, str] = {
    "linux-x86_64": "librdp_jvm_sys.so",
    "linux-aarch64": "librdp_jvm_sys.so",
    "osx-x86_64": "librdp_jvm_sys.dylib",
    "osx-aarch64": "librdp_jvm_sys.dylib",
    "windows-x86_64": "rdp_jvm_sys.dll",
}

GROUP_ID = "io.github.scorpio-datalake.rust-data-processing"
ARTIFACT_ID = "rdp-jvm-sys"


def package_jar(*, classifier: str, native_path: Path, out_dir: Path, version: str) -> Path:
    if classifier not in NATIVE_BASENAMES:
        raise SystemExit(f"Unknown classifier {classifier!r}; expected one of {sorted(NATIVE_BASENAMES)}")
    if not native_path.is_file():
        raise SystemExit(f"Native library not found: {native_path}")

    basename = NATIVE_BASENAMES[classifier]
    out_dir.mkdir(parents=True, exist_ok=True)
    jar_name = f"{ARTIFACT_ID}-{version}-{classifier}.jar"
    out_jar = out_dir / jar_name

    with tempfile.TemporaryDirectory(prefix="rdp-jvm-sys-native-") as tmp:
        meta_native = Path(tmp) / "META-INF" / "native"
        meta_native.mkdir(parents=True)
        shutil.copy2(native_path, meta_native / basename)
        subprocess.run(
            ["jar", "cf", str(out_jar), "-C", tmp, "META-INF"],
            check=True,
        )
    return out_jar


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("classifier", choices=sorted(NATIVE_BASENAMES))
    parser.add_argument("native_path", type=Path, help="Prebuilt cdylib path from cargo")
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=REPO / "bindings" / "java" / "rdp-jvm-sys" / "target" / "native-jars",
    )
    parser.add_argument("--version", default=None, help="Defaults to bindings/java/VERSION")
    args = parser.parse_args(argv)

    version = args.version or VERSION_FILE.read_text(encoding="utf-8").strip()
    out_jar = package_jar(
        classifier=args.classifier,
        native_path=args.native_path.resolve(),
        out_dir=args.out_dir.resolve(),
        version=version,
    )
    print(out_jar, flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
