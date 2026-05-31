//! Builder-style configuration for native Kafka consumer/producer paths.

use crate::error::{IngestionError, IngestionResult};

/// Consumer configuration (brokers, group, topic, poll limits).
#[derive(Debug, Clone)]
pub struct KafkaConsumerBuilder {
    pub brokers: String,
    pub group_id: String,
    pub topic: String,
    pub auto_offset_reset: String,
    pub session_timeout_ms: Option<u32>,
}

impl KafkaConsumerBuilder {
    pub fn new(
        brokers: impl Into<String>,
        group_id: impl Into<String>,
        topic: impl Into<String>,
    ) -> Self {
        Self {
            brokers: brokers.into(),
            group_id: group_id.into(),
            topic: topic.into(),
            auto_offset_reset: "earliest".to_string(),
            session_timeout_ms: None,
        }
    }

    pub fn auto_offset_reset(mut self, value: impl Into<String>) -> Self {
        self.auto_offset_reset = value.into();
        self
    }

    pub fn session_timeout_ms(mut self, ms: u32) -> Self {
        self.session_timeout_ms = Some(ms);
        self
    }

    pub fn validate(&self) -> IngestionResult<()> {
        if self.brokers.trim().is_empty() {
            return Err(IngestionError::SchemaMismatch {
                message: "kafka brokers must not be empty".to_string(),
            });
        }
        if self.group_id.trim().is_empty() {
            return Err(IngestionError::SchemaMismatch {
                message: "kafka group_id must not be empty".to_string(),
            });
        }
        if self.topic.trim().is_empty() {
            return Err(IngestionError::SchemaMismatch {
                message: "kafka topic must not be empty".to_string(),
            });
        }
        Ok(())
    }
}

/// Producer configuration (brokers, topic, optional key/value columns).
#[derive(Debug, Clone, Default)]
pub struct KafkaProducerBuilder {
    pub brokers: String,
    pub topic: String,
    pub message_timeout_ms: u64,
    pub key_column: Option<String>,
    pub value_column: Option<String>,
    pub headers: Vec<(String, String)>,
}

impl KafkaProducerBuilder {
    pub fn new(brokers: impl Into<String>, topic: impl Into<String>) -> Self {
        Self {
            brokers: brokers.into(),
            topic: topic.into(),
            message_timeout_ms: 5_000,
            ..Self::default()
        }
    }

    pub fn message_timeout_ms(mut self, ms: u64) -> Self {
        self.message_timeout_ms = ms;
        self
    }

    pub fn key_column(mut self, column: impl Into<String>) -> Self {
        self.key_column = Some(column.into());
        self
    }

    pub fn value_column(mut self, column: impl Into<String>) -> Self {
        self.value_column = Some(column.into());
        self
    }

    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }

    pub fn validate(&self) -> IngestionResult<()> {
        if self.brokers.trim().is_empty() {
            return Err(IngestionError::SchemaMismatch {
                message: "kafka brokers must not be empty".to_string(),
            });
        }
        if self.topic.trim().is_empty() {
            return Err(IngestionError::SchemaMismatch {
                message: "kafka topic must not be empty".to_string(),
            });
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn consumer_builder_rejects_empty_brokers() {
        let b = KafkaConsumerBuilder::new("", "g", "t");
        assert!(b.validate().is_err());
    }

    #[test]
    fn producer_builder_accepts_minimal_config() {
        let b = KafkaProducerBuilder::new("localhost:9092", "out");
        assert!(b.validate().is_ok());
    }
}
