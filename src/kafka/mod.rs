//! Kafka **streaming ELT** (Extract → Load → Transform).
//!
//! Kafka is **not** a file connector. RDP treats it like stream frameworks (Flink, Kafka Streams):
//!
//! 1. **Extract** — poll a bounded window from a topic (`poll_kafka_window`) or accept records from
//!    your host consumer (`elt_load_kafka_records`).
//! 2. **Load** — land raw/semi-structured rows to storage (Parquet, Postgres `COPY`, object store)
//!    with offsets preserved — **no heavy transform in the hot path**.
//! 3. **Transform** — run Polars SQL / pipeline JSON on landed data in a **separate** job or stage.
//!
//! **Poll window** (`Vec<KafkaStreamRecord>`) is backpressure / checkpoint sizing — not batch ETL.
//!
//! Enable native I/O with **`--features kafka`**. See [`docs/KAFKA_ELT.md`](../../docs/KAFKA_ELT.md).

#[cfg(feature = "kafka")]
mod builder;
#[cfg(feature = "kafka")]
mod consumer;
#[cfg(feature = "kafka")]
mod load;
#[cfg(feature = "kafka")]
mod producer;
#[cfg(feature = "kafka")]
mod record;

#[cfg(feature = "kafka")]
pub use builder::{KafkaConsumerBuilder, KafkaProducerBuilder};
#[cfg(feature = "kafka")]
pub use consumer::{poll_kafka_window, poll_kafka_window_loaded};
#[cfg(feature = "kafka")]
pub use load::{
    elt_load_kafka_records, elt_load_kafka_records_json, ingest_from_external_kafka_batches,
    ingest_from_external_kafka_batches_json,
};
#[cfg(feature = "kafka")]
pub use producer::export_dataset_to_kafka;
#[cfg(feature = "kafka")]
pub use record::{BytesTopicBatch, KafkaHeader, KafkaStreamRecord};

/// Back-compat aliases.
#[cfg(feature = "kafka")]
pub use consumer::{consume_micro_batch, consume_micro_batch_as_dataset};

/// **Extract** side: poll the next bounded window from a native or test consumer.
#[cfg(feature = "kafka")]
pub trait KafkaStreamSource {
    fn poll_window(
        &mut self,
        max_records: usize,
    ) -> crate::error::IngestionResult<Vec<KafkaStreamRecord>>;
}

/// Back-compat trait name.
#[cfg(feature = "kafka")]
pub trait KafkaBatchSource: KafkaStreamSource {
    fn next_batch(
        &mut self,
        max_records: usize,
    ) -> crate::error::IngestionResult<Vec<KafkaStreamRecord>> {
        self.poll_window(max_records)
    }
}

#[cfg(feature = "kafka")]
impl<T: KafkaStreamSource + ?Sized> KafkaBatchSource for T {}

#[cfg(not(feature = "kafka"))]
mod disabled {
    use crate::error::{IngestionError, IngestionResult};
    use crate::types::{DataSet, Schema};

    fn disabled_err() -> IngestionError {
        IngestionError::SchemaMismatch {
            message: "kafka support is disabled; enable Cargo feature 'kafka'".to_string(),
        }
    }

    pub fn elt_load_kafka_records_json(_json: &str, _schema: &Schema) -> IngestionResult<DataSet> {
        Err(disabled_err())
    }
}

#[cfg(not(feature = "kafka"))]
pub use disabled::elt_load_kafka_records_json;
