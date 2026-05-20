"""Ingest the committed Alpaca-shaped SFT sample (repo examples/sft)."""

from __future__ import annotations

from pathlib import Path

import rust_data_processing as rdp

# python-wrapper/tests -> repo root is two levels up
REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLE = REPO_ROOT / "examples" / "sft" / "sample_alpaca.ndjson"


def test_sft_sample_alpaca_ndjson_ingest_and_export() -> None:
    assert SAMPLE.is_file(), f"missing fixture: {SAMPLE}"
    schema = [
        {"name": "instruction", "data_type": "utf8"},
        {"name": "input", "data_type": "utf8"},
        {"name": "output", "data_type": "utf8"},
    ]
    ds = rdp.ingest_from_path(str(SAMPLE), schema)
    assert ds.row_count() == 4
    lines = rdp.export_dataset_jsonl(ds, ["instruction", "input", "output"]).strip().splitlines()
    assert len(lines) == 4
    assert "instruction" in lines[0] and "output" in lines[0]
