"""Kafka streaming integration helpers — ctypes FFI to librdp_jvm_sys (full,kafka)."""

from __future__ import annotations

import csv
import ctypes
import json
import os
from pathlib import Path
from typing import Any

INTEG_ROOT = Path(__file__).resolve().parent.parent


class RdpJsonSlice(ctypes.Structure):
    _fields_ = [
        ("ptr", ctypes.POINTER(ctypes.c_uint8)),
        ("len", ctypes.c_size_t),
        ("cap", ctypes.c_size_t),
    ]


def _lib_path() -> Path:
    env = os.environ.get("RDP_JVM_SYS")
    if env:
        return Path(env)
    default = INTEG_ROOT / "libs" / "java" / "librdp_jvm_sys.so"
    if default.is_file():
        return default
    raise RuntimeError(
        "RDP_JVM_SYS not set and libs/java/librdp_jvm_sys.so missing — "
        "rebuild with JVM_FEATURES full,kafka"
    )


def _load_lib() -> ctypes.CDLL:
    lib = ctypes.CDLL(str(_lib_path()))
    lib.rdp_json_slice_free.argtypes = [RdpJsonSlice]
    lib.rdp_json_slice_free.restype = None
    for name in (
        "rdp_kafka_export_dataset_json",
        "rdp_kafka_poll_window_loaded_json",
    ):
        fn = getattr(lib, name)
        fn.argtypes = [ctypes.POINTER(RdpJsonSlice), ctypes.c_char_p, ctypes.c_char_p]
        fn.restype = None
    return lib


def _invoke_two_arg(lib: ctypes.CDLL, symbol: str, arg1: str, arg2: str) -> dict[str, Any]:
    out = RdpJsonSlice()
    fn = getattr(lib, symbol)
    fn(ctypes.byref(out), arg1.encode("utf-8"), arg2.encode("utf-8"))
    try:
        raw = ctypes.string_at(out.ptr, out.len)
        root = json.loads(raw.decode("utf-8"))
    finally:
        lib.rdp_json_slice_free(out)
    if not root.get("ok", False):
        raise RuntimeError(f"{symbol} failed: {root.get('error', root)}")
    return root


def kafka_brokers() -> str:
    return os.environ.get("KAFKA_BROKERS", "127.0.0.1:9092")


def kafka_topic() -> str:
    return os.environ.get("KAFKA_TOPIC", "rdp-uber-pickups")


def kafka_group_id() -> str:
    return os.environ.get("KAFKA_GROUP_ID", "rdp-integration-test")


def landing_schema() -> list[dict[str, str]]:
    return [
        {"name": "pickup_time", "data_type": "Utf8"},
        {"name": "lat", "data_type": "Float64"},
        {"name": "lon", "data_type": "Float64"},
        {"name": "base_code", "data_type": "Utf8"},
        {"name": "_kafka_offset", "data_type": "Int64"},
        {"name": "_kafka_partition", "data_type": "Int64"},
    ]


def _producer_config(topic: str | None = None) -> str:
    return json.dumps(
        {
            "brokers": kafka_brokers(),
            "topic": topic or kafka_topic(),
            "message_timeout_ms": 10_000,
        }
    )


def _consumer_config(*, max_records: int, group_id: str | None = None) -> str:
    return json.dumps(
        {
            "brokers": kafka_brokers(),
            "group_id": group_id or kafka_group_id(),
            "topic": kafka_topic(),
            "max_records": max_records,
            "auto_offset_reset": "earliest",
        }
    )


def _dataset_envelope(rows: list[list[Any]]) -> str:
    typed_rows = []
    for row in rows:
        pickup, lat, lon, base = row
        typed_rows.append(
            [
                {"Utf8": str(pickup)},
                {"Float64": float(lat)},
                {"Float64": float(lon)},
                {"Utf8": str(base)},
            ]
        )
    return json.dumps(
        {
            "schema": {"fields": landing_schema()[:4]},
            "rows": typed_rows,
        }
    )


def stream_csv_rows_to_kafka(
    csv_path: str | Path,
    *,
    max_rows: int | None = None,
    one_row_per_message: bool = True,
) -> int:
    """Stream Uber CSV rows to Kafka one message at a time via Rust producer."""
    max_rows = max_rows or int(os.environ.get("INTEG_MAX_IMPORT_ROWS", "500"))
    lib = _load_lib()
    sent = 0
    with Path(csv_path).open(newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        for row in reader:
            if sent >= max_rows:
                break
            payload = _dataset_envelope(
                [
                    [
                        row["Date/Time"],
                        float(row["Lat"]),
                        float(row["Lon"]),
                        row["Base"],
                    ]
                ]
            )
            _invoke_two_arg(lib, "rdp_kafka_export_dataset_json", _producer_config(), payload)
            sent += 1
            if not one_row_per_message:
                break
    if sent == 0:
        raise RuntimeError(f"no rows streamed from {csv_path}")
    return sent


def poll_kafka_row_count(*, expected: int, group_id: str | None = None) -> int:
    """Poll a bounded window from Kafka and return landed row count."""
    lib = _load_lib()
    root = _invoke_two_arg(
        lib,
        "rdp_kafka_poll_window_loaded_json",
        _consumer_config(max_records=expected, group_id=group_id),
        json.dumps({"fields": landing_schema()}),
    )
    rows = root["interchange"]["dataset"]["rows"]
    count = len(rows)
    if count != expected:
        raise RuntimeError(f"kafka poll: expected {expected} rows, got {count}")
    return count


def verify_uber_kafka_stream(csv_path: str | Path, *, max_rows: int | None = None) -> int:
    """Produce one Kafka message per CSV row, then poll and verify count."""
    max_rows = max_rows or int(os.environ.get("INTEG_MAX_IMPORT_ROWS", "500"))
    produced = stream_csv_rows_to_kafka(csv_path, max_rows=max_rows)
    # Fresh consumer group per verify so offset resets to earliest for this topic run.
    group = f"{kafka_group_id()}-{os.getpid()}"
    return poll_kafka_row_count(expected=produced, group_id=group)
