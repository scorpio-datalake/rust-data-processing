//! Streaming ELT sketch: Extract (poll window) → Load (Parquet landing) → Transform (SQL).
//!
//! Requires a reachable broker for the Extract loop. BYO-only tests use `kafka_elt_byo_load`.
//!
//! ```bash
//! export KAFKA_BROKERS=localhost:9092
//! export KAFKA_TOPIC=events
//! cargo run --features kafka --example kafka_elt_stream
//! ```

use rust_data_processing::ingestion::export_dataset_to_parquet;
use rust_data_processing::kafka::{elt_load_kafka_records, poll_kafka_window, KafkaConsumerBuilder};
use rust_data_processing::pipeline::DataFrame;
use rust_data_processing::sql;
use rust_data_processing::types::{DataType, Field, Schema};
use std::env;
use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let brokers = env::var("KAFKA_BROKERS").unwrap_or_else(|_| "localhost:9092".into());
    let topic = env::var("KAFKA_TOPIC").unwrap_or_else(|_| "events".into());
    let group = env::var("KAFKA_GROUP").unwrap_or_else(|_| "rdp-elt-demo".into());

    let landing = Schema::new(vec![
        Field::new("user_id", DataType::Int64),
        Field::new("event", DataType::Utf8),
        Field::new("_kafka_offset", DataType::Int64),
    ]);

    let consumer = KafkaConsumerBuilder::new(&brokers, &group, &topic);

    // --- Extract + Load (one poll window; production loops with commit/checkpoint) ---
    let records = poll_kafka_window(&consumer, 500)?;
    if records.is_empty() {
        eprintln!("No records in poll window (topic empty or broker unreachable).");
        eprintln!("Use example `kafka_elt_byo_load` for broker-free Load demos.");
        return Ok(());
    }
    let landed = elt_load_kafka_records(&records, &landing)?;

    let landing_path = PathBuf::from(env::var("RDP_ELT_LANDING").unwrap_or_else(|_| {
        std::env::temp_dir()
            .join("rdp_kafka_landing.parquet")
            .to_string_lossy()
            .into_owned()
    }));
    export_dataset_to_parquet(&landing_path, &landed)?;
    println!(
        "Load: wrote {} rows to {}",
        landed.row_count(),
        landing_path.display()
    );

    // --- Transform (separate stage on landed storage) ---
    let df = DataFrame::from_dataset(&landed)?;
    let curated = sql::query(&df, "SELECT user_id, event FROM df WHERE event IS NOT NULL")?
        .collect()?;
    println!("Transform: curated {} rows via Polars SQL", curated.row_count());
    Ok(())
}
