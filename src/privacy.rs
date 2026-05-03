//! Summaries for **UTF-8 cell changes** after caller-defined transforms (Phase 2).
//!
//! This crate does **not** provide legal classification of data; callers supply policy and
//! interpret reports.

use serde::Serialize;

use crate::types::{DataSet, Value};

/// Per-column summary comparing string cells before and after a transform.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Utf8ColumnChangeSummary {
    pub column: String,
    pub non_null_cells_compared: usize,
    pub cells_changed: usize,
}

/// Count how many non-null UTF-8 cells differ between `before` and `after` for each column name.
pub fn summarize_utf8_column_changes(
    before: &DataSet,
    after: &DataSet,
    columns: &[String],
) -> Vec<Utf8ColumnChangeSummary> {
    let mut out = Vec::with_capacity(columns.len());
    for name in columns {
        let Some(bi) = before.schema.index_of(name) else {
            continue;
        };
        let Some(ai) = after.schema.index_of(name) else {
            continue;
        };
        let mut non_null = 0usize;
        let mut changed = 0usize;
        for (br, ar) in before.rows.iter().zip(after.rows.iter()) {
            match (br.get(bi), ar.get(ai)) {
                (Some(Value::Utf8(sb)), Some(Value::Utf8(sa))) => {
                    non_null += 1;
                    if sb != sa {
                        changed += 1;
                    }
                }
                _ => {}
            }
        }
        out.push(Utf8ColumnChangeSummary {
            column: name.clone(),
            non_null_cells_compared: non_null,
            cells_changed: changed,
        });
    }
    out
}

/// Pretty JSON for [`summarize_utf8_column_changes`].
pub fn render_privacy_report_json(rows: &[Utf8ColumnChangeSummary]) -> crate::error::IngestionResult<String> {
    serde_json::to_string_pretty(rows).map_err(|e| crate::error::IngestionError::SchemaMismatch {
        message: format!("privacy report json: {e}"),
    })
}

/// Short Markdown list for human review.
pub fn render_privacy_report_markdown(rows: &[Utf8ColumnChangeSummary]) -> String {
    let mut s = String::from("## Privacy / masking summary (UTF-8 diffs)\n\n");
    for r in rows {
        s.push_str(&format!(
            "- **{}**: changed **{}** / **{}** non-null cells\n",
            r.column, r.cells_changed, r.non_null_cells_compared
        ));
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{DataType, Field, Schema};

    #[test]
    fn summary_counts_changes() {
        let sc = Schema::new(vec![Field::new("email", DataType::Utf8)]);
        let before = DataSet::new(
            sc.clone(),
            vec![
                vec![Value::Utf8("a@b.c".into())],
                vec![Value::Null],
            ],
        );
        let after = DataSet::new(
            sc,
            vec![
                vec![Value::Utf8("a@***".into())],
                vec![Value::Null],
            ],
        );
        let r = summarize_utf8_column_changes(&before, &after, &["email".into()]);
        assert_eq!(r[0].cells_changed, 1);
        assert_eq!(r[0].non_null_cells_compared, 1);
    }
}
