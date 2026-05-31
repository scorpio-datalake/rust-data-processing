"""Python bindings for the `rust-data-processing` Rust crate.

The native extension is built with PyO3 and maturin. Prefer the APIs exported here rather than
importing ``rust_data_processing._rust_data_processing`` directly.
"""

from __future__ import annotations

import json
from importlib.metadata import PackageNotFoundError, version
from typing import Any, Mapping

from . import cdc
from ._rust_data_processing import (
    DataFrame,
    DataSet,
    ExecutionEngine,
    SqlContext,
    detect_outliers_json,
    detect_outliers_markdown,
    discover_hive_partitioned_files,
    export_dataset_jsonl,
    export_filter_rows_max_utf8_chars,
    export_train_test_row_indices,
    extension_version,
    infer_schema_from_path,
    ingest_from_db,
    ingest_from_db_infer,
    ingest_from_ordered_paths,
    ingest_from_path,
    ingest_from_path_infer,
    parse_partition_segment,
    paths_from_directory_scan,
    paths_from_explicit_list,
    paths_from_glob,
    privacy_summarize_utf8_changes_json,
    privacy_summarize_utf8_changes_markdown,
    processing_arg_max_row,
    processing_arg_min_row,
    processing_feature_wise_mean_std,
    processing_filter,
    processing_map,
    processing_reduce,
    processing_top_k_by_frequency,
    profile_dataset_json,
    profile_dataset_markdown,
    reports_truncate_utf8_bytes,
    sql_query_dataset,
    transform_apply_json,
    validate_dataset_json,
    validate_dataset_markdown,
)

try:
    __version__ = version("rust-data-processing")
except PackageNotFoundError:
    __version__ = extension_version()


def ingest_with_inferred_schema(path: str, options: dict[str, Any] | None = None):
    """Infer schema once, then ingest (two passes over the file; same as the Rust helper)."""
    schema = infer_schema_from_path(path, options)
    return ingest_from_path(path, schema, options), schema


def transform_apply(dataset: DataSet, spec: Mapping[str, Any] | str) -> DataSet:
    """Apply a :class:`TransformSpec` given as JSON string or dict (serde shape)."""
    if isinstance(spec, str):
        payload = spec
    else:
        payload = json.dumps(spec)
    return transform_apply_json(dataset, payload)


def profile_dataset(dataset: DataSet, options: dict[str, Any] | None = None) -> dict[str, Any]:
    """Return profiling report as a dict (parsed JSON)."""
    return json.loads(profile_dataset_json(dataset, options))


def validate_dataset(dataset: DataSet, spec: Mapping[str, Any]) -> dict[str, Any]:
    """Run validation checks; return report dict (parsed JSON)."""
    return json.loads(validate_dataset_json(dataset, spec))


def detect_outliers(
    dataset: DataSet,
    column: str,
    method: Mapping[str, Any],
    options: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Outlier report as dict (parsed JSON)."""
    return json.loads(detect_outliers_json(dataset, column, method, options))


def export_jsonl_records(dataset: DataSet, columns: list[str]) -> list[dict[str, Any]]:
    """Parse :func:`export_dataset_jsonl` output into a list of row dicts."""
    text = export_dataset_jsonl(dataset, columns)
    return [json.loads(line) for line in text.splitlines() if line.strip()]


def privacy_summarize_utf8_changes(
    before: DataSet,
    after: DataSet,
    columns: list[str],
    *,
    as_markdown: bool = False,
) -> list[dict[str, Any]] | str:
    """UTF-8 diff summary per column: parsed JSON list (default) or Markdown string."""
    if as_markdown:
        return privacy_summarize_utf8_changes_markdown(before, after, columns)
    return json.loads(privacy_summarize_utf8_changes_json(before, after, columns))


__all__ = [
    "DataFrame",
    "DataSet",
    "ExecutionEngine",
    "SqlContext",
    "__version__",
    "cdc",
    "detect_outliers",
    "detect_outliers_json",
    "detect_outliers_markdown",
    "discover_hive_partitioned_files",
    "export_dataset_jsonl",
    "export_filter_rows_max_utf8_chars",
    "export_jsonl_records",
    "export_train_test_row_indices",
    "extension_version",
    "ingest_from_db",
    "ingest_from_db_infer",
    "ingest_from_ordered_paths",
    "ingest_from_path",
    "ingest_from_path_infer",
    "ingest_with_inferred_schema",
    "infer_schema_from_path",
    "parse_partition_segment",
    "paths_from_directory_scan",
    "paths_from_explicit_list",
    "paths_from_glob",
    "privacy_summarize_utf8_changes",
    "privacy_summarize_utf8_changes_json",
    "privacy_summarize_utf8_changes_markdown",
    "processing_arg_max_row",
    "processing_arg_min_row",
    "processing_feature_wise_mean_std",
    "processing_filter",
    "processing_map",
    "processing_reduce",
    "processing_top_k_by_frequency",
    "profile_dataset",
    "profile_dataset_json",
    "profile_dataset_markdown",
    "reports_truncate_utf8_bytes",
    "sql_query_dataset",
    "transform_apply",
    "transform_apply_json",
    "validate_dataset",
    "validate_dataset_json",
    "validate_dataset_markdown",
]

try:
    from ._rust_data_processing import (  # noqa: F401
        elt_load_kafka_records_json,
        export_dataset_to_kafka,
        poll_kafka_window,
        poll_kafka_window_loaded,
    )
except ImportError:
    pass
else:
    __all__ += [
        "elt_load_kafka_records_json",
        "export_dataset_to_kafka",
        "poll_kafka_window",
        "poll_kafka_window_loaded",
    ]

try:
    from ._rust_data_processing import (  # noqa: F401
        export_dataset_to_object_store_uri,
        ingest_from_file_transfer_uri,
        ingest_from_object_store_uri,
    )
except ImportError:
    pass
else:
    __all__ += [
        "export_dataset_to_object_store_uri",
        "ingest_from_file_transfer_uri",
        "ingest_from_object_store_uri",
    ]
