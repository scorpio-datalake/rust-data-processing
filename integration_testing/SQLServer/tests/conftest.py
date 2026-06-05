"""Pytest hooks for SQL Server integration tests."""

from __future__ import annotations


def pytest_configure(config) -> None:
    config.addinivalue_line(
        "markers",
        "integration: SQL Server / external-service integration tests (opt-in via env)",
    )
