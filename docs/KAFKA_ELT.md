# Kafka streaming ELT (Rust, Python, Java)

Kafka is a **streaming** connector. **Rust owns all Kafka I/O** (`rdkafka`). Python and Java are **thin wrappers** over the same Rust APIs — same ELT model as other connectors:

| Phase | What happens | Where |
| --- | --- | --- |
| **Extract** | Poll a bounded window from a topic | Rust (`poll_kafka_window`) |
| **Load** | Land rows + offsets to storage (Parquet, Postgres, object store) | Rust (`elt_load_kafka_records`, sinks) |
| **Transform** | Polars SQL, pipeline JSON — **after** load | Rust (separate stage) |

## Why “poll window”, not “batch”?

- **Kafka is continuous.** Consumers poll; stream frameworks use **finite windows** for checkpointing and backpressure.
- A **`Vec<KafkaStreamRecord>`** is one **poll window** — not a nightly file batch.
- **`KafkaStreamRecord`** is a **single event** (topic, partition, offset, payload).
- **Do not** run heavy transforms inside the consume hot path. That is ETL. Land first, transform separately.

Build: `cargo build --features kafka` (Rust), `maturin develop --features kafka` (Python), `cargo build -p rdp-jvm-sys --features kafka` (JVM wrapper over same Rust).

See also: [docs/adr/007-kafka-streaming-elt.md](adr/007-kafka-streaming-elt.md).

---

## Rust (implementation)

### Load step (fixture / tests — no broker)

```rust
use rust_data_processing::kafka::elt_load_kafka_records_json;
use rust_data_processing::types::{DataType, Field, Schema};

let landing = Schema::new(vec![
    Field::new("user_id", DataType::Int64),
    Field::new("event", DataType::Utf8),
    Field::new("_kafka_offset", DataType::Int64),
]);

let json = r#"{"records":[
  {"topic":"events","partition":0,"offset":1,
   "value":"{\"user_id\":1,\"event\":\"click\"}"}
]}"#;

let landed = elt_load_kafka_records_json(json, &landing)?;
// Next: export_dataset_to_parquet / COPY / object store — then Transform separately.
```

Run: `cargo run --features kafka --example kafka_elt_byo_load`

### Stream loop (Extract → Load → Transform)

```rust
use rust_data_processing::ingestion::export_dataset_to_parquet;
use rust_data_processing::kafka::{elt_load_kafka_records, poll_kafka_window, KafkaConsumerBuilder};
use rust_data_processing::pipeline::DataFrame;
use rust_data_processing::sql;

let consumer = KafkaConsumerBuilder::new("localhost:9092", "rdp-elt", "events");

loop {
    let records = poll_kafka_window(&consumer, 500)?; // Extract
    if records.is_empty() { break; }
    let landed = elt_load_kafka_records(&records, &landing_schema)?; // Load
    export_dataset_to_parquet("landing/part.parquet", &landed)?;

    let df = DataFrame::from_dataset(&landed)?;
    let curated = sql::query(&df, "SELECT user_id FROM df WHERE event = 'purchase'")?.collect()?; // Transform
}
```

Run (needs broker): `cargo run --features kafka --example kafka_elt_stream`

---

## Python (wrapper)

Build: `uv run maturin develop --release --features kafka`

Python does **not** use `confluent_kafka` or `kafka-python` for ingestion. Call Rust:

```python
import rust_data_processing as rdp

landed = rdp.elt_load_kafka_records_json(records_json, landing_schema)

# Extract (needs broker)
records_json = rdp.poll_kafka_window("localhost:9092", "rdp-elt", "events", max_records=500)
landed = rdp.elt_load_kafka_records_json(records_json, landing_schema)

# Or Extract+Load in one call:
landed = rdp.poll_kafka_window_loaded(
    "localhost:9092", "rdp-elt", "events", landing_schema, max_records=500
)

# Sink (needs broker)
sent = rdp.export_dataset_to_kafka("localhost:9092", "out-topic", curated_dataset)
```

Native `poll_kafka_window*` blocks while holding the GIL — run from a dedicated thread or keep orchestration in Rust.

---

## Java (wrapper)

JVM does **not** embed `kafka-clients` for ingestion. Panama FFI calls into `rdp_jvm_sys` (same Rust implementation):

| FFI symbol | Rust analogue |
| --- | --- |
| `rdp_kafka_elt_load_records_json` | `elt_load_kafka_records_json` |
| `rdp_kafka_poll_window_json` | `poll_kafka_window` |
| `rdp_kafka_poll_window_loaded_json` | `poll_kafka_window_loaded` |
| `rdp_kafka_export_dataset_json` | `export_dataset_to_kafka` |

Example: `docs/java/examples/KafkaEltLoadExample.java` (Load via fixture JSON — no broker).

**Transform** — `rdp_run_pipeline_json` or SQL parity exports on landed data.

Requires `rdp_jvm_sys` built with `--features kafka` (Linux CI builds `full,kafka`). Maven and Gradle both load the same native artifact.

---

## File connectors vs Kafka

| | File / DB connectors | Kafka |
| --- | --- | --- |
| Model | One-shot read or query | Continuous stream |
| Unit of work | File, SQL result set | **Poll window** of records |
| Who does I/O | Rust | **Rust only** (wrappers call Rust) |
| RDP pattern | Ingest + transform in one pipeline is OK | **ELT:** load landing, transform separately |
| Offsets | N/A | Preserve `_kafka_offset` / `_kafka_partition` in landing tables |

---

## Metadata columns

Optional landing schema columns (filled from broker metadata):

- `_kafka_topic`, `_kafka_partition`, `_kafka_offset`, `_kafka_timestamp_ms`, `_kafka_key`

Payload JSON keys map to other columns during **Load** only.
