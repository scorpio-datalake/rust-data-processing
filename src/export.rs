//! Deterministic **JSON Lines** export and simple **train / test** row splits (Phase 2).
//!
//! This module does **not** implement tokenizers or model-specific chat templates. Callers align
//! exported text with their trainer’s expected fields.

use serde_json::{Map, Value as JsonValue};

use crate::error::{IngestionError, IngestionResult};
use crate::types::{DataSet, Value};

fn cell_to_json(v: &Value) -> JsonValue {
    match v {
        Value::Null => JsonValue::Null,
        Value::Int64(i) => JsonValue::from(*i),
        Value::Float64(x) => JsonValue::from(*x),
        Value::Bool(b) => JsonValue::from(*b),
        Value::Utf8(s) => JsonValue::from(s.clone()),
    }
}

/// Serialize each row as one JSON object per line (UTF-8), columns in `column_order`.
///
/// Column names must exist on `ds`. Row order is preserved.
pub fn dataset_to_jsonl(ds: &DataSet, column_order: &[String]) -> IngestionResult<String> {
    let idx: Vec<usize> = column_order
        .iter()
        .map(|name| {
            ds.schema
                .index_of(name)
                .ok_or_else(|| IngestionError::SchemaMismatch {
                    message: format!("dataset_to_jsonl: unknown column '{name}'"),
                })
        })
        .collect::<Result<_, _>>()?;

    let mut out = String::new();
    for row in &ds.rows {
        let mut m = Map::new();
        for (name, &i) in column_order.iter().zip(&idx) {
            m.insert(name.clone(), cell_to_json(&row[i]));
        }
        let line = serde_json::to_string(&JsonValue::Object(m)).map_err(|e| {
            IngestionError::SchemaMismatch {
                message: format!("dataset_to_jsonl: json encode failed: {e}"),
            }
        })?;
        out.push_str(&line);
        out.push('\n');
    }
    Ok(out)
}

/// Deterministic split: first `train_count` rows are train, remaining rows are test, where
/// `train_count = row_count - round(row_count * test_fraction.clamp(0..=1))`.
pub fn train_test_row_indices(row_count: usize, test_fraction: f64) -> (Vec<usize>, Vec<usize>) {
    let tf = test_fraction.clamp(0.0, 1.0);
    let test_n = ((row_count as f64) * tf).round() as usize;
    let test_n = test_n.min(row_count);
    let train_n = row_count.saturating_sub(test_n);
    let train: Vec<usize> = (0..train_n).collect();
    let test: Vec<usize> = (train_n..row_count).collect();
    (train, test)
}

/// Keep only rows whose UTF-8 value in `column` has at most `max_chars` Unicode scalars; other rows dropped.
pub fn filter_rows_max_utf8_chars(
    ds: &DataSet,
    column: &str,
    max_chars: usize,
) -> IngestionResult<DataSet> {
    let idx = ds
        .schema
        .index_of(column)
        .ok_or_else(|| IngestionError::SchemaMismatch {
            message: format!("filter_rows_max_utf8_chars: unknown column '{column}'"),
        })?;
    if ds.schema.fields[idx].data_type != crate::types::DataType::Utf8 {
        return Err(IngestionError::SchemaMismatch {
            message: format!("column '{column}' must be Utf8"),
        });
    }
    let mut rows = Vec::new();
    for row in &ds.rows {
        match row.get(idx) {
            Some(Value::Utf8(s)) if s.chars().count() <= max_chars => rows.push(row.clone()),
            Some(Value::Null) | None => rows.push(row.clone()),
            _ => {}
        }
    }
    Ok(DataSet::new(ds.schema.clone(), rows))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{DataType, Field, Schema};

    #[test]
    fn jsonl_roundtrip_ordering_and_split() {
        let schema = Schema::new(vec![
            Field::new("a", DataType::Int64),
            Field::new("b", DataType::Utf8),
        ]);
        let ds = DataSet::new(
            schema.clone(),
            vec![
                vec![Value::Int64(1), Value::Utf8("x".into())],
                vec![Value::Int64(2), Value::Utf8("yy".into())],
                vec![Value::Int64(3), Value::Utf8("zzz".into())],
            ],
        );
        let jl = dataset_to_jsonl(&ds, &["a".into(), "b".into()]).unwrap();
        assert_eq!(
            jl,
            "{\"a\":1,\"b\":\"x\"}\n{\"a\":2,\"b\":\"yy\"}\n{\"a\":3,\"b\":\"zzz\"}\n"
        );
        let (tr, te) = train_test_row_indices(3, 1.0 / 3.0);
        assert_eq!(tr, vec![0, 1]);
        assert_eq!(te, vec![2]);
    }

    #[test]
    fn filter_max_chars_drops_long_rows() {
        let schema = Schema::new(vec![Field::new("s", DataType::Utf8)]);
        let ds = DataSet::new(
            schema,
            vec![
                vec![Value::Utf8("ab".into())],
                vec![Value::Utf8("abc".into())],
            ],
        );
        let out = filter_rows_max_utf8_chars(&ds, "s", 2).unwrap();
        assert_eq!(out.row_count(), 1);
    }

    #[test]
    fn jsonl_empty_dataset() {
        let schema = Schema::new(vec![Field::new("id", DataType::Int64)]);
        let ds = DataSet::new(schema, vec![]);
        assert_eq!(dataset_to_jsonl(&ds, &["id".into()]).unwrap(), "");
    }
}
