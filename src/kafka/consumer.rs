//! Native Kafka consumer — **Extract** step (bounded poll windows).

use std::time::Duration;

use rdkafka::consumer::{BaseConsumer, Consumer};
use rdkafka::message::{Headers, Message};

use crate::error::{IngestionError, IngestionResult};
use crate::kafka::KafkaStreamSource;
use crate::kafka::record::KafkaStreamRecord;
use crate::types::{DataSet, Schema};

use super::KafkaConsumerBuilder;
use super::load::elt_load_kafka_records;

/// **Extract:** poll up to `max_records` from the topic (one checkpoint window).
pub fn poll_kafka_window(
    builder: &KafkaConsumerBuilder,
    max_records: usize,
) -> IngestionResult<Vec<KafkaStreamRecord>> {
    let mut source = builder.build()?;
    source.poll_window(max_records)
}

/// **Extract + Load:** poll a window and map to a landing [`DataSet`].
pub fn poll_kafka_window_loaded(
    builder: &KafkaConsumerBuilder,
    landing_schema: &Schema,
    max_records: usize,
) -> IngestionResult<DataSet> {
    let records = poll_kafka_window(builder, max_records)?;
    elt_load_kafka_records(&records, landing_schema)
}

/// Back-compat — prefer [`poll_kafka_window`].
pub fn consume_micro_batch(
    builder: &KafkaConsumerBuilder,
    max_records: usize,
) -> IngestionResult<Vec<KafkaStreamRecord>> {
    poll_kafka_window(builder, max_records)
}

/// Back-compat — prefer [`poll_kafka_window_loaded`].
pub fn consume_micro_batch_as_dataset(
    builder: &KafkaConsumerBuilder,
    schema: &Schema,
    max_records: usize,
) -> IngestionResult<DataSet> {
    poll_kafka_window_loaded(builder, schema, max_records)
}

impl KafkaStreamSource for BaseConsumer {
    fn poll_window(&mut self, max_records: usize) -> IngestionResult<Vec<KafkaStreamRecord>> {
        if max_records == 0 {
            return Ok(Vec::new());
        }
        let timeout = Duration::from_millis(500);
        let mut out = Vec::with_capacity(max_records);
        while out.len() < max_records {
            match self.poll(timeout) {
                Some(Ok(message)) => {
                    let headers = message
                        .headers()
                        .map(|h| {
                            h.iter()
                                .map(|header| super::record::KafkaHeader {
                                    name: header.key.to_string(),
                                    value: header
                                        .value
                                        .map(|v| String::from_utf8_lossy(v).into_owned())
                                        .unwrap_or_default(),
                                })
                                .collect()
                        })
                        .unwrap_or_default();
                    out.push(KafkaStreamRecord {
                        topic: message.topic().to_string(),
                        partition: message.partition(),
                        offset: message.offset(),
                        timestamp_ms: message.timestamp().to_millis(),
                        key: message
                            .key()
                            .map(|k| String::from_utf8_lossy(k).into_owned()),
                        value: String::from_utf8_lossy(message.payload().unwrap_or_default())
                            .into_owned(),
                        headers,
                    });
                }
                Some(Err(e)) => {
                    return Err(IngestionError::Engine {
                        message: "kafka consumer poll failed".to_string(),
                        source: Box::new(e),
                    });
                }
                None => break,
            }
        }
        Ok(out)
    }
}

impl KafkaConsumerBuilder {
    pub(crate) fn build(&self) -> IngestionResult<BaseConsumer> {
        use rdkafka::config::ClientConfig;

        self.validate()?;

        let mut config = ClientConfig::new();
        config
            .set("bootstrap.servers", &self.brokers)
            .set("group.id", &self.group_id)
            .set("enable.auto.commit", "false")
            .set("auto.offset.reset", &self.auto_offset_reset);

        if let Some(ms) = self.session_timeout_ms {
            config.set("session.timeout.ms", ms.to_string());
        }

        let consumer: BaseConsumer = config.create().map_err(|e| IngestionError::Engine {
            message: "failed to create kafka consumer".to_string(),
            source: Box::new(e),
        })?;

        consumer
            .subscribe(&[&self.topic])
            .map_err(|e| IngestionError::Engine {
                message: format!("kafka subscribe to topic '{}' failed", self.topic),
                source: Box::new(e),
            })?;

        Ok(consumer)
    }
}
