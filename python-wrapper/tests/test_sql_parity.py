"""Mirror `tests/sql.rs` — all queries go through `rust_data_processing` (no `cargo test`)."""

from __future__ import annotations

import pytest
import rust_data_processing as rdp

from tests.helpers import people_dataset


def test_sql_basic_select_where_order_limit_works() -> None:
    ds = people_dataset()
    out = rdp.sql_query_dataset(
        ds,
        """
        SELECT id, name, score
        FROM df
        WHERE active = TRUE
        ORDER BY id DESC
        LIMIT 2
        """,
    )
    assert out.column_names() == ["id", "name", "score"]
    assert out.row_count() == 2
    rows = out.to_rows()
    assert rows[0] == [4, "Ken", None]
    assert rows[1] == [3, "Linus", 3.0]


def test_sql_group_by_aggregates_and_having_work() -> None:
    ds = people_dataset()
    out = rdp.sql_query_dataset(
        ds,
        """
        SELECT
          grp,
          SUM(score) AS sum_score,
          COUNT(*) AS cnt
        FROM df
        GROUP BY grp
        HAVING SUM(score) > 10
        ORDER BY grp ASC
        """,
    )
    assert out.column_names() == ["grp", "sum_score", "cnt"]
    assert out.row_count() == 1
    assert out.to_rows()[0] == ["A", 30.0, 2]


def test_sql_context_supports_joins_across_registered_tables() -> None:
    """Delegates to fixture-backed test; kept for name parity with ``tests/sql.rs``."""
    from tests.test_sql_queries_fixtures import test_sql_parity_join_matches_committed_fixtures

    test_sql_parity_join_matches_committed_fixtures()


def test_sql_missing_table_returns_engine_error() -> None:
    ds = people_dataset()
    with pytest.raises(Exception):
        rdp.sql_query_dataset(ds, "SELECT * FROM does_not_exist")


def test_sql_missing_column_returns_actionable_error() -> None:
    ds = people_dataset()
    with pytest.raises(Exception) as ei:
        rdp.sql_query_dataset(ds, "SELECT missing_col FROM df")
    assert "missing" in str(ei.value).lower()
