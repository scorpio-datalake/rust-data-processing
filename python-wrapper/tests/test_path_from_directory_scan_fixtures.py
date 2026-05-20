"""Parity with ``docs/java/examples/OrderedPaths.java`` and ``tests/fixtures/watermark/``."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

import rust_data_processing as rdp

from tests.pipeline_fixture_support import load_schema_fields, resolve_payload_json


def _events_schema() -> list[dict[str, str]]:
    return load_schema_fields("schemas", "events.schema.json", bundle="watermark")


def _watermark_opts() -> dict:
    return {
        "format": "csv",
        "watermark_column": "ts",
        "watermark_exclusive_above": 100,
    }


def _java_demo_dir(tmp: Path) -> tuple[Path, Path]:
    nested = tmp / "nested"
    nested.mkdir()
    path_a = tmp / "a.csv"
    path_b = nested / "b.csv"
    path_a.write_text("id,ts\n1,50\n2,99\n", encoding="utf-8")
    path_b.write_text("id,ts\n3,150\n4,200\n", encoding="utf-8")
    return path_a, path_b


def test_watermark_csv_watermark_ingest_body_payload_resolves() -> None:
    body = json.loads(
        resolve_payload_json("watermark", "payloads/csv_watermark_ingest.body.json", {})
    )
    assert body["options"]["watermark_column"] == "ts"
    assert body["options"]["watermark_exclusive_above"] == 100
    assert body["response"]["mode"] == "dataset"
    assert "schema" in body


def test_directory_scan_then_batch_watermark_matches_java_demo() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        _java_demo_dir(root)
        paths = rdp.paths_from_directory_scan(str(root), "**/*.csv")
        assert len(paths) == 2
        assert paths[0].endswith("a.csv")
        assert "nested" in paths[1] and paths[1].endswith("b.csv")

        ds, meta = rdp.ingest_from_ordered_paths(paths, _events_schema(), _watermark_opts())
        assert ds.row_count() == 2
        assert [r[0] for r in ds.to_rows()] == [3, 4]
        assert meta["max_watermark_value"] == 200
        assert len(meta["paths"]) == 2


def test_directory_scan_two_csv_payload_template() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        path_a, path_b = _java_demo_dir(root)
        payload = json.loads(
            resolve_payload_json(
                "watermark",
                "payloads/directory_scan_two_csv.payload.json",
                {
                    "PATH_A": str(path_a.resolve()),
                    "PATH_B": str(path_b.resolve()),
                },
            )
        )
        assert len(payload["paths"]) == 2
        ds, _ = rdp.ingest_from_ordered_paths(
            payload["paths"],
            _events_schema(),
            _watermark_opts(),
        )
        assert ds.row_count() == 2
