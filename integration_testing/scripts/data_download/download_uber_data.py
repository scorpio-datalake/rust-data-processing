#!/usr/bin/env python3
"""SCRIPT: integration tooling — not a pytest/cargo/junit test target."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import DATA_DIR, count_lines, log, require_tool  # noqa: E402

UBER_URL = (
    "https://raw.githubusercontent.com/fivethirtyeight/uber-tlc-foil-response/"
    "master/uber-trip-data/uber-raw-data-apr14.csv"
)
OUT_FILE = DATA_DIR / "uber_nyc_pickups_apr2014.csv"
SAMPLE_FILE = DATA_DIR / "uber_nyc_pickups_sample.csv"
SAMPLE_ROWS = 50_000


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Download Uber NYC pickups CSV for Oracle integration import tests."
    )
    parser.add_argument("--sample", action="store_true", help="Also write a 50k-row sample file.")
    parser.add_argument("--force", action="store_true", help="Re-download even if file exists.")
    args = parser.parse_args(argv)

    if OUT_FILE.is_file() and not args.force:
        log(f"Data already present: {OUT_FILE} ({count_lines(OUT_FILE)} lines)")
    else:
        require_tool("curl")
        log("Downloading Uber NYC pickups (April 2014)...")
        log(f"URL: {UBER_URL}")
        tmp = OUT_FILE.with_suffix(".csv.partial")
        subprocess.run(
            ["curl", "-fsSL", "--retry", "3", "--retry-delay", "2", "-o", str(tmp), UBER_URL],
            check=True,
        )
        tmp.replace(OUT_FILE)
        log(f"Saved {count_lines(OUT_FILE)} lines → {OUT_FILE}")

    if args.sample:
        if SAMPLE_FILE.is_file() and not args.force:
            log(f"Sample already present: {SAMPLE_FILE}")
        else:
            log(f"Writing sample ({SAMPLE_ROWS} rows) → {SAMPLE_FILE}")
            with OUT_FILE.open(encoding="utf-8", errors="replace") as src:
                header = src.readline()
            with SAMPLE_FILE.open("w", encoding="utf-8") as dst:
                dst.write(header)
                with OUT_FILE.open(encoding="utf-8", errors="replace") as src:
                    next(src)
                    for i, line in enumerate(src):
                        if i >= SAMPLE_ROWS:
                            break
                        dst.write(line)
            log(f"Sample rows: {count_lines(SAMPLE_FILE) - 1}")

    log("Uber data ready.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
