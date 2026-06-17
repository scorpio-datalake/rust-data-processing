"""Pytest hooks for Databricks integration tests."""


def pytest_configure(config) -> None:
    config.addinivalue_line(
        "markers",
        "integration: Databricks stage integration tests (opt-in via env)",
    )
