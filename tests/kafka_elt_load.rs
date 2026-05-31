//! Integration tests for Kafka **ELT Load** (BYO records; no broker).

use rust_data_processing::kafka::elt_load_kafka_records_json;
use rust_data_processing::types::{DataType, Field, Schema, Value};

#[test]
fn elt_load_stream_records_fixture() {
    let landing = Schema::new(vec![
        Field::new("event_id", DataType::Int64),
        Field::new("payload", DataType::Utf8),
        Field::new("_kafka_partition", DataType::Int64),
    ]);
    let json = include_str!("fixtures/kafka/stream_records.json");
    let ds = elt_load_kafka_records_json(json, &landing).unwrap();
    assert_eq!(ds.row_count(), 2);
    assert_eq!(ds.rows[0][0], Value::Int64(100));
    assert_eq!(ds.rows[0][1], Value::Utf8("alpha".to_string()));
}

#[test]
fn kafka_consumer_builder_validates_brokers() {
    use rust_data_processing::kafka::KafkaConsumerBuilder;
    let b = KafkaConsumerBuilder::new("", "group", "topic");
    assert!(b.validate().is_err());
}
