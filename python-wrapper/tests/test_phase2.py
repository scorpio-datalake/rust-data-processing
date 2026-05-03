"""Unit tests for Phase 2 Python surface: export, privacy, reports, UTF-8 transforms, validation, median."""

from __future__ import annotations

import json

import pytest

import rust_data_processing as rdp


def test_export_dataset_jsonl_and_records_helper() -> None:
    schema = [{"name": "id", "data_type": "int64"}, {"name": "t", "data_type": "utf8"}]
    rows = [[1, "a"], [2, "bb"]]
    ds = rdp.DataSet(schema, rows)
    s = rdp.export_dataset_jsonl(ds, ["id", "t"])
    assert s.strip().splitlines()[0] == '{"id":1,"t":"a"}'
    recs = rdp.export_jsonl_records(ds, ["t", "id"])
    assert recs[0] == {"t": "a", "id": 1}


def test_export_train_test_row_indices() -> None:
    train, test = rdp.export_train_test_row_indices(10, 0.2)
    assert train == [0, 1, 2, 3, 4, 5, 6, 7]
    assert test == [8, 9]


def test_export_filter_rows_max_utf8_chars() -> None:
    schema = [{"name": "s", "data_type": "utf8"}]
    ds = rdp.DataSet(schema, [["ab"], ["abc"], [None]])
    out = rdp.export_filter_rows_max_utf8_chars(ds, "s", 2)
    assert out.row_count() == 2


def test_privacy_summarize_helpers() -> None:
    schema = [{"name": "email", "data_type": "utf8"}]
    before = rdp.DataSet(schema, [["a@b.c"]])
    after = rdp.DataSet(schema, [["a@x"]])
    js = rdp.privacy_summarize_utf8_changes(before, after, ["email"])
    assert isinstance(js, list) and js[0]["cells_changed"] == 1
    md = rdp.privacy_summarize_utf8_changes(before, after, ["email"], as_markdown=True)
    assert "email" in md and "changed" in md
    raw = json.loads(rdp.privacy_summarize_utf8_changes_json(before, after, ["email"]))
    assert raw[0]["column"] == "email"


def test_reports_truncate_utf8_bytes() -> None:
    s = "é" * 4  # 2 bytes each in UTF-8
    t = rdp.reports_truncate_utf8_bytes(s, 3)
    assert "truncated" in t.lower() or "…" in t


def test_transform_utf8_privacy_steps() -> None:
    schema_in = [{"name": "s", "data_type": "utf8"}]
    ds = rdp.DataSet(schema_in, [["abcdef"]])
    spec = {
        "output_schema": {"fields": [{"name": "s", "data_type": "Utf8"}]},
        "steps": [
            {"Utf8Truncate": {"column": "s", "max_chars": 4}},
            {
                "Utf8RedactMiddle": {
                    "column": "s",
                    "keep_left": 1,
                    "keep_right": 1,
                    "redaction": "***",
                }
            },
        ],
    }
    out = rdp.transform_apply(ds, spec)
    assert out.to_rows()[0][0] == "a***d"


def test_transform_utf8_sha256_hex() -> None:
    schema_in = [{"name": "s", "data_type": "utf8"}]
    ds = rdp.DataSet(schema_in, [["abc"]])
    spec = {
        "output_schema": {"fields": [{"name": "s", "data_type": "Utf8"}]},
        "steps": [{"Utf8Sha256Hex": {"column": "s"}}],
    }
    out = rdp.transform_apply(ds, spec)
    hx = out.to_rows()[0][0]
    assert isinstance(hx, str) and len(hx) == 64


def test_validate_utf8_len_chars_between_all_pass() -> None:
    schema = [{"name": "code", "data_type": "utf8"}]
    ds = rdp.DataSet(schema, [["abc"], ["abcd"]])
    rep = rdp.validate_dataset(
        ds,
        {
            "checks": [
                {
                    "kind": "utf8_len_chars_between",
                    "column": "code",
                    "min_chars": 3,
                    "max_chars": 5,
                    "severity": "error",
                }
            ]
        },
    )
    assert rep["summary"]["failed_checks"] == 0


def test_validate_utf8_len_chars_between() -> None:
    schema = [{"name": "code", "data_type": "utf8"}]
    ds = rdp.DataSet(schema, [["ab"], ["abcd"], ["abcdef"]])
    rep = rdp.validate_dataset(
        ds,
        {
            "checks": [
                {
                    "kind": "utf8_len_chars_between",
                    "column": "code",
                    "min_chars": 3,
                    "max_chars": 5,
                    "severity": "error",
                }
            ]
        },
    )
    assert rep["summary"]["failed_checks"] >= 1


def test_processing_reduce_median() -> None:
    schema = [{"name": "x", "data_type": "int64"}]
    ds = rdp.DataSet(schema, [[3], [1], [2]])
    m = rdp.processing_reduce(ds, "x", "median")
    assert m == pytest.approx(2.0)


def test_processing_reduce_median_even_count() -> None:
    schema = [{"name": "x", "data_type": "int64"}]
    ds = rdp.DataSet(schema, [[10], [20], [30], [40]])
    m = rdp.processing_reduce(ds, "x", "median")
    assert float(m) == pytest.approx(25.0)


def test_export_dataset_jsonl_empty() -> None:
    schema = [{"name": "id", "data_type": "int64"}]
    ds = rdp.DataSet(schema, [])
    assert rdp.export_dataset_jsonl(ds, ["id"]) == ""
    assert rdp.export_jsonl_records(ds, ["id"]) == []


def test_privacy_summarize_markdown_has_header() -> None:
    schema = [{"name": "x", "data_type": "utf8"}]
    b = rdp.DataSet(schema, [["a"]])
    a = rdp.DataSet(schema, [["b"]])
    md = rdp.privacy_summarize_utf8_changes_markdown(b, a, ["x"])
    assert "## Privacy" in md or "Privacy" in md


def test_dataframe_reduce_and_group_by_median() -> None:
    schema = [{"name": "g", "data_type": "utf8"}, {"name": "v", "data_type": "int64"}]
    ds = rdp.DataSet(schema, [["a", 1], ["a", 2], ["a", 6]])
    lf = rdp.DataFrame.from_dataset(ds)
    med = lf.reduce("v", "median")
    assert med is not None
    assert float(med) == pytest.approx(2.0)  # median of 1, 2, 6
    lf2 = rdp.DataFrame.from_dataset(ds)
    out = lf2.group_by(["g"], [{"type": "median", "column": "v", "alias": "m"}]).collect()
    assert out.row_count() == 1
    rows = out.to_rows()
    assert float(rows[0][1]) == pytest.approx(2.0)
