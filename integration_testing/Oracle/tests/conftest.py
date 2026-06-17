"""Pytest hooks for Oracle integration tests."""

from __future__ import annotations


def pytest_configure(config) -> None:
    config.addinivalue_line(
        "markers",
        "integration: Oracle / external-service integration tests (opt-in via env)",
    )
