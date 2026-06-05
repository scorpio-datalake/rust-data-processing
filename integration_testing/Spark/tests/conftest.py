"""Pytest hooks for Spark integration tests."""


def pytest_configure(config) -> None:
    config.addinivalue_line(
        "markers",
        "integration: Spark stage integration tests (opt-in via env)",
    )
