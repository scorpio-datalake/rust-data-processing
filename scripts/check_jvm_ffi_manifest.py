#!/usr/bin/env python3
"""Verify `ffi_manifest.json` matches `include/rdp_jvm_sys.h` and `src/lib.rs` ABI constant."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
MANIFEST = REPO / "bindings/jvm-sys/ffi_manifest.json"
JVM_TEST_MANIFEST = REPO / (
    "bindings/java/rust-data-processing-jvm/src/test/resources/"
    "io/github/vihangdesai2018_png/rdp/ffi_manifest.json"
)
HEADER = REPO / "bindings/jvm-sys/include/rdp_jvm_sys.h"
LIB_RS = REPO / "bindings/jvm-sys/src/lib.rs"


def main() -> int:
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    header = HEADER.read_text(encoding="utf-8")
    lib = LIB_RS.read_text(encoding="utf-8")

    for sym in data["exported_symbols"]:
        if sym not in header:
            print(f"error: symbol `{sym}` missing from {HEADER.relative_to(REPO)}", file=sys.stderr)
            return 1

    m_abi = re.search(r"rdp_ffi_abi_version\s*\(\)\s*->\s*u32\s*\{\s*(\d+)", lib, re.S)
    if not m_abi:
        print("error: could not parse rdp_ffi_abi_version() body in lib.rs", file=sys.stderr)
        return 1
    rust_abi = int(m_abi.group(1))
    if rust_abi != data["abi_version_constant"]:
        print(
            f"error: ffi_manifest abi_version_constant {data['abi_version_constant']} != Rust {rust_abi}",
            file=sys.stderr,
        )
        return 1

    if not JVM_TEST_MANIFEST.is_file():
        print(f"error: missing JVM test resource {JVM_TEST_MANIFEST.relative_to(REPO)}", file=sys.stderr)
        return 1
    jvm_test_bytes = JVM_TEST_MANIFEST.read_bytes()
    manifest_bytes = MANIFEST.read_bytes()
    if jvm_test_bytes != manifest_bytes:
        print(
            "error: JVM test resource ffi_manifest.json must match bindings/jvm-sys/ffi_manifest.json "
            f"(copy or regenerate). Compare:\n  {MANIFEST.relative_to(REPO)}\n  {JVM_TEST_MANIFEST.relative_to(REPO)}",
            file=sys.stderr,
        )
        return 1

    print("check_jvm_ffi_manifest: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
