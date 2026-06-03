"""CONNECTORS.md batch connector build features — shared by CI and integration builds.

Rust I/O lives in ``rust-data-processing`` / ``rdp_jvm_sys``. Python and Java are thin
wrappers; integration builds must enable the same connector surface as JVM CI ``full``.
"""
from __future__ import annotations

# rdp_jvm_sys — every batch connector (validated by check_jvm_full_features.py).
JVM_FEATURES = "full"
JVM_FEATURES_KAFKA = "full,kafka"

# Main crate — DB reads + cloud object store / SFTP / FTP / Snowflake / Delta / Spark handoff.
RUST_INTEGRATION_FEATURES = "integration_full"

# PyO3 wrapper — ``db`` → db_connectorx, ``cloud`` → cloud_connectors.
PYTHON_INTEGRATION_FEATURES = "integration_full"
