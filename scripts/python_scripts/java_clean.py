#!/usr/bin/env python3
"""Run ./gradlew clean for bindings/java/rust-data-processing-jvm."""

from __future__ import annotations

import argparse
import sys

from java_build import gradle_clean


def main(argv: list[str] | None = None) -> int:
    argparse.ArgumentParser(description=__doc__).parse_args(argv)
    gradle_clean()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
