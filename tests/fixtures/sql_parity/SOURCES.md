# SQL parity / JOIN fixture bundle

| File | Role |
| --- | --- |
| `schemas/join_left.schema.json`, `join_right.schema.json` | Serde schemas for JOIN sides |
| `data/join_left.json`, `join_right.json` | Committed row data (mirrors Rust `sql_suite` parity) |
| `queries/join_people_scores.sql.json` | Documented JOIN SQL text |

JVM multi-table {@code SqlContext} JOIN execution still uses {@code rdp_parity_sql_suite_mirror} until {@code rdp_run_pipeline_json} supports multiple registered sources; {@code SQLQueries.java} loads SQL and schemas from this bundle.

**Tests:** `tests/sql.rs` (`sql_context_join_matches_sql_parity_fixtures`), `python-wrapper/tests/test_sql_queries_fixtures.py`, JVM `rdp_parity_sql_suite_mirror` (Rust mirror loads this bundle in `parity_mirrors.rs`).
