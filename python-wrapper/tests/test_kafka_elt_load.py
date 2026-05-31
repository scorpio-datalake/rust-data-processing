"""Kafka streaming ELT Python wrappers (feature kafka)."""

from __future__ import annotations

import json

import pytest

import rust_data_processing as rdp

KAFKA = hasattr(rdp, "elt_load_kafka_records_json")
skip_kafka = pytest.mark.skipif(not KAFKA, reason="extension not built with --features kafka")


@skip_kafka
def test_elt_load_kafka_records_json_landing():
    landing = {
        "fields": [
            {"name": "event_id", "type": "Int64"},
            {"name": "payload", "type": "Utf8"},
            {"name": "_kafka_offset", "type": "Int64"},
        ]
    }
    records = {
        "records": [
            {
                "topic": "metrics",
                "partition": 0,
                "offset": 10,
                "value": json.dumps({"event_id": 100, "payload": "alpha"}),
            }
        ]
    }
    ds = rdp.elt_load_kafka_records_json(json.dumps(records), landing)
    assert ds.row_count() == 1


@skip_kafka
def test_poll_kafka_window_rejects_empty_brokers():
    with pytest.raises(Exception, match="brokers"):
        rdp.poll_kafka_window("", "g", "t", max_records=1)


@skip_kafka
def test_export_dataset_to_kafka_rejects_empty_brokers():
    landing = {"fields": [{"name": "x", "type": "Utf8"}]}
    ds = rdp.DataSet(landing, [["hello"]])
    with pytest.raises(Exception, match="brokers"):
        rdp.export_dataset_to_kafka("", "out", ds)
