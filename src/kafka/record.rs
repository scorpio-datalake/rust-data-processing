//! One Kafka stream record (partition, offset, payload bytes).
//!
//! This is a **single event**, not a file batch. Bounded **`poll_window`** calls return
//! `Vec<KafkaStreamRecord>` — a **poll window** for backpressure, not batch ETL.

use serde::{Deserialize, Serialize};

/// One consumed/produced Kafka record plus broker metadata.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KafkaStreamRecord {
    pub topic: String,
    pub partition: i32,
    pub offset: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub timestamp_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub key: Option<String>,
    /// Payload as UTF-8 JSON text or opaque string for the **Load** step (minimal parse only).
    pub value: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub headers: Vec<KafkaHeader>,
}

/// Optional record header (UTF-8 name and value for JSON interchange).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct KafkaHeader {
    pub name: String,
    pub value: String,
}

impl KafkaStreamRecord {
    pub fn value_bytes(&self) -> &[u8] {
        self.value.as_bytes()
    }

    pub fn key_bytes(&self) -> Option<&[u8]> {
        self.key.as_deref().map(str::as_bytes)
    }
}

/// Back-compat alias — prefer [`KafkaStreamRecord`].
pub type BytesTopicBatch = KafkaStreamRecord;
