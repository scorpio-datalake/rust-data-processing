//! **Load** step: map Kafka stream records into a landing [`DataSet`] (ELT, not batch ETL).
//!
//! Parses payload JSON into columns and preserves `_kafka_*` metadata columns. **Transform**
//! (Polars SQL, validation, joins) is a separate step — see [`docs/KAFKA_ELT.md`](../../docs/KAFKA_ELT.md).

use crate::error::{IngestionError, IngestionResult};
use crate::kafka::record::KafkaStreamRecord;
use crate::types::{DataSet, DataType, Field, Schema, Value};

/// **Load:** host-provided stream records → landing table (`DataSet`).
///
/// **Load:** map stream records into a landing [`DataSet`] (used by Rust, Python, and JVM wrappers).
pub fn elt_load_kafka_records(
    records: &[KafkaStreamRecord],
    landing_schema: &Schema,
) -> IngestionResult<DataSet> {
    let mut rows = Vec::with_capacity(records.len());
    for record in records {
        rows.push(record_to_row(record, landing_schema)?);
    }
    Ok(DataSet::new(landing_schema.clone(), rows))
}

/// JSON: `{ "records": [ …KafkaStreamRecord… ] }` or a bare record array.
pub fn elt_load_kafka_records_json(json: &str, landing_schema: &Schema) -> IngestionResult<DataSet> {
    let records: Vec<KafkaStreamRecord> = if let Ok(wrapper) =
        serde_json::from_str::<ExternalRecordEnvelope>(json)
    {
        wrapper.records
    } else {
        serde_json::from_str(json).map_err(|e| IngestionError::SchemaMismatch {
            message: format!("invalid kafka stream record JSON: {e}"),
        })?
    };
    elt_load_kafka_records(&records, landing_schema)
}

/// Back-compat — prefer [`elt_load_kafka_records`].
pub fn ingest_from_external_kafka_batches(
    records: &[KafkaStreamRecord],
    schema: &Schema,
) -> IngestionResult<DataSet> {
    elt_load_kafka_records(records, schema)
}

/// Back-compat — prefer [`elt_load_kafka_records_json`].
pub fn ingest_from_external_kafka_batches_json(json: &str, schema: &Schema) -> IngestionResult<DataSet> {
    elt_load_kafka_records_json(json, schema)
}

#[derive(serde::Deserialize)]
struct ExternalRecordEnvelope {
    records: Vec<KafkaStreamRecord>,
}

fn record_to_row(record: &KafkaStreamRecord, schema: &Schema) -> IngestionResult<Vec<Value>> {
    let value_json: Option<serde_json::Value> = serde_json::from_str(&record.value).ok();
    let non_meta = schema_non_metadata_fields(schema);
    let mut row = Vec::with_capacity(schema.fields.len());
    for field in &schema.fields {
        let v = if field.name.starts_with("_kafka_") {
            kafka_metadata_value(record, &field.name)?
        } else if let Some(obj) = value_json.as_ref().and_then(|j| j.as_object()) {
            json_field_to_value(obj.get(&field.name), &field.data_type)?
        } else if non_meta.len() == 1 && non_meta[0].name == field.name {
            string_to_value(&record.value, &field.data_type)?
        } else {
            Value::Null
        };
        row.push(v);
    }
    Ok(row)
}

fn schema_non_metadata_fields(schema: &Schema) -> Vec<&Field> {
    schema
        .fields
        .iter()
        .filter(|f| !f.name.starts_with("_kafka_"))
        .collect()
}

fn kafka_metadata_value(record: &KafkaStreamRecord, name: &str) -> IngestionResult<Value> {
    match name {
        "_kafka_topic" => Ok(Value::Utf8(record.topic.clone())),
        "_kafka_partition" => Ok(Value::Int64(i64::from(record.partition))),
        "_kafka_offset" => Ok(Value::Int64(record.offset)),
        "_kafka_timestamp_ms" => Ok(record
            .timestamp_ms
            .map(Value::Int64)
            .unwrap_or(Value::Null)),
        "_kafka_key" => Ok(record
            .key
            .clone()
            .map(Value::Utf8)
            .unwrap_or(Value::Null)),
        other => Err(IngestionError::SchemaMismatch {
            message: format!("unknown kafka metadata column '{other}'"),
        }),
    }
}

fn json_field_to_value(v: Option<&serde_json::Value>, dt: &DataType) -> IngestionResult<Value> {
    match v {
        None | Some(serde_json::Value::Null) => Ok(Value::Null),
        Some(serde_json::Value::Number(n)) if matches!(dt, DataType::Int64) => Ok(Value::Int64(
            n.as_i64()
                .ok_or_else(|| IngestionError::SchemaMismatch {
                    message: "expected i64 in kafka value JSON".to_string(),
                })?,
        )),
        Some(serde_json::Value::Number(n)) if matches!(dt, DataType::Float64) => {
            Ok(Value::Float64(n.as_f64().unwrap_or(f64::NAN)))
        }
        Some(serde_json::Value::Bool(b)) if matches!(dt, DataType::Bool) => Ok(Value::Bool(*b)),
        Some(serde_json::Value::String(s)) => string_to_value(s, dt),
        Some(other) => string_to_value(&other.to_string(), dt),
    }
}

fn string_to_value(s: &str, dt: &DataType) -> IngestionResult<Value> {
    match dt {
        DataType::Utf8 => Ok(Value::Utf8(s.to_string())),
        DataType::Int64 => s
            .parse::<i64>()
            .map(Value::Int64)
            .map_err(|e| IngestionError::SchemaMismatch {
                message: format!("kafka value int64 cast failed: {e}"),
            }),
        DataType::Float64 => s
            .parse::<f64>()
            .map(Value::Float64)
            .map_err(|e| IngestionError::SchemaMismatch {
                message: format!("kafka value float64 cast failed: {e}"),
            }),
        DataType::Bool => s
            .parse::<bool>()
            .map(Value::Bool)
            .map_err(|e| IngestionError::SchemaMismatch {
                message: format!("kafka value bool cast failed: {e}"),
            }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn elt_load_records_json_landing_table() {
        let landing = Schema::new(vec![
            Field::new("id", DataType::Int64),
            Field::new("name", DataType::Utf8),
            Field::new("_kafka_topic", DataType::Utf8),
            Field::new("_kafka_offset", DataType::Int64),
        ]);
        let json = r#"{"records":[
          {"topic":"events","partition":0,"offset":42,"timestamp_ms":1000,
           "value":"{\"id\":1,\"name\":\"Ada\"}"}
        ]}"#;
        let ds = elt_load_kafka_records_json(json, &landing).unwrap();
        assert_eq!(ds.row_count(), 1);
        assert_eq!(ds.rows[0][0], Value::Int64(1));
        assert_eq!(ds.rows[0][1], Value::Utf8("Ada".to_string()));
        assert_eq!(ds.rows[0][2], Value::Utf8("events".to_string()));
        assert_eq!(ds.rows[0][3], Value::Int64(42));
    }
}
