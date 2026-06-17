//! **Load** (sink): publish a [`DataSet`] to a Kafka topic; flush before return (at-least-once).

use std::time::Duration;

use rdkafka::config::ClientConfig;
use rdkafka::message::OwnedHeaders;
use rdkafka::producer::{BaseProducer, BaseRecord, Producer};

use crate::error::{IngestionError, IngestionResult};
use crate::types::{DataSet, Value};

use super::KafkaProducerBuilder;

/// Send each row of `dataset` as a JSON object to Kafka; flush before returning.
///
/// Uses `value_column` when set; otherwise serializes the full row as JSON.
pub fn export_dataset_to_kafka(
    builder: &KafkaProducerBuilder,
    dataset: &DataSet,
) -> IngestionResult<usize> {
    builder.validate()?;
    let producer: BaseProducer = ClientConfig::new()
        .set("bootstrap.servers", &builder.brokers)
        .set("message.timeout.ms", builder.message_timeout_ms.to_string())
        .create()
        .map_err(|e| IngestionError::Engine {
            message: "failed to create kafka producer".to_string(),
            source: Box::new(e),
        })?;

    let mut sent = 0usize;
    for (idx, row) in dataset.rows.iter().enumerate() {
        let payload = row_payload(dataset, row, builder.value_column.as_deref())?;
        let key = builder
            .key_column
            .as_deref()
            .and_then(|col| dataset.schema.index_of(col))
            .and_then(|i| value_to_string(&row[i]))
            .unwrap_or_else(|| idx.to_string());

        let mut record = BaseRecord::to(&builder.topic).key(&key).payload(&payload);

        if !builder.headers.is_empty() {
            let mut hdrs = OwnedHeaders::new();
            for (name, val) in &builder.headers {
                hdrs = hdrs.insert(rdkafka::message::Header {
                    key: name,
                    value: Some(val.as_str()),
                });
            }
            record = record.headers(hdrs);
        }

        producer
            .send(record)
            .map_err(|(e, _)| IngestionError::Engine {
                message: format!("kafka produce to topic '{}' failed", builder.topic),
                source: Box::new(e),
            })?;
        sent += 1;
    }

    producer
        .flush(Duration::from_secs(30))
        .map_err(|e| IngestionError::Engine {
            message: "kafka producer flush failed".to_string(),
            source: Box::new(e),
        })?;

    Ok(sent)
}

fn row_payload(
    dataset: &DataSet,
    row: &[Value],
    value_column: Option<&str>,
) -> IngestionResult<String> {
    if let Some(col) = value_column {
        let idx = dataset
            .schema
            .index_of(col)
            .ok_or_else(|| IngestionError::SchemaMismatch {
                message: format!("kafka value_column '{col}' not in schema"),
            })?;
        return value_to_string(&row[idx]).ok_or_else(|| IngestionError::SchemaMismatch {
            message: format!("kafka value_column '{col}' is null"),
        });
    }

    let mut obj = serde_json::Map::new();
    for (field, value) in dataset.schema.fields.iter().zip(row.iter()) {
        obj.insert(field.name.clone(), value_to_json(value));
    }
    serde_json::to_string(&obj).map_err(|e| IngestionError::Engine {
        message: "serialize kafka row JSON failed".to_string(),
        source: Box::new(e),
    })
}

fn value_to_json(v: &Value) -> serde_json::Value {
    match v {
        Value::Null => serde_json::Value::Null,
        Value::Bool(b) => serde_json::Value::Bool(*b),
        Value::Int64(i) => serde_json::Value::from(*i),
        Value::Float64(f) => serde_json::Number::from_f64(*f)
            .map(serde_json::Value::Number)
            .unwrap_or(serde_json::Value::Null),
        Value::Utf8(s) => serde_json::Value::String(s.clone()),
    }
}

fn value_to_string(v: &Value) -> Option<String> {
    match v {
        Value::Utf8(s) => Some(s.clone()),
        Value::Int64(i) => Some(i.to_string()),
        Value::Float64(f) => Some(f.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        Value::Null => None,
    }
}
