//! ELT **Load** step from host-provided stream records (no live broker).
//!
//! ```bash
//! cargo run --features kafka --example kafka_elt_byo_load
//! ```
//!
//! See `docs/KAFKA_ELT.md` for the full Extract → Load → Transform loop.

use rust_data_processing::kafka::elt_load_kafka_records_json;
use rust_data_processing::types::{DataType, Field, Schema};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let landing = Schema::new(vec![
        Field::new("id", DataType::Int64),
        Field::new("name", DataType::Utf8),
        Field::new("_kafka_topic", DataType::Utf8),
        Field::new("_kafka_offset", DataType::Int64),
    ]);

    // Fixture JSON simulates what a Python/JVM wrapper passes to elt_load_kafka_records_json.
    let json = r#"{"records":[
      {"topic":"events","partition":0,"offset":1,"value":"{\"id\":1,\"name\":\"Ada\"}"},
      {"topic":"events","partition":0,"offset":2,"value":"{\"id\":2,\"name\":\"Bob\"}"}
    ]}"#;

    let landed = elt_load_kafka_records_json(json, &landing)?;
    println!("Load: landed {} rows (offsets preserved)", landed.row_count());

    // Transform is a separate step — e.g. sql_query_dataset, pipeline JSON, validation.
    println!("Transform: run Polars SQL / pipeline on landed data (not in this example).");
    Ok(())
}
