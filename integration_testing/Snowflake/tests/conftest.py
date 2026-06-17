"""Pytest hooks for Snowflake integration tests."""


def pytest_configure(config) -> None:
    config.addinivalue_line(
        "markers",
        "integration: Snowflake stage integration tests (opt-in via env)",
    )
