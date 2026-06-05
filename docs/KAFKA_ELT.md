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

## Integration validation (Docker)

Tri-language test: **`python3 integration_testing/Kafka/run_kafka_tests.py --no-rancher`** (Redpanda on `127.0.0.1:19092`, topic `rdp-uber-pickups`).

| Step | What happens | Code reference |
| --- | --- | --- |
| 1 | Start Redpanda + create topic | `integration_testing/Kafka/docker-compose.yml` |
| 2 | For each Uber CSV row, build a one-row `DataSet` JSON (`{"Utf8":…}`, `{"Float64":…}`) | `integration_testing/scripts/kafka_stream.py` → `_dataset_envelope` |
| 3 | **Produce:** `rdp_kafka_export_dataset_json(producer_config, dataset_json)` | Java: `KafkaStreamIntegrationTest` · Rust: `rdp_kafka.rs` |
| 4 | **Consume:** `rdp_kafka_poll_window_loaded_json(consumer_config, landing_schema)` | Assert landed row count == produced |
| 5 | Repeat for Java, Python, Rust legs | Look for `PASSED:` in log |

First run rebuilds `librdp_jvm_sys` with **`--features full,kafka`**. Details: [`integration_testing/integration_testing_details.md`](../integration_testing/integration_testing_details.md) § Kafka.

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

JVM does **not** embed `kafka-clients` for ingestion. **Project Panama** downcalls into `rdp_jvm_sys`, which runs the same Rust `rdkafka` code as the native crate and Python wrapper.

Build the native library first:

```bash
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full,kafka
export RDP_JVM_SYS=bindings/jvm-sys/target/release/librdp_jvm_sys.so   # .dylib / .dll on macOS / Windows
```

| FFI symbol | Rust analogue |
| --- | --- |
| `rdp_kafka_elt_load_records_json` | `elt_load_kafka_records_json` |
| `rdp_kafka_poll_window_json` | `poll_kafka_window` |
| `rdp_kafka_poll_window_loaded_json` | `poll_kafka_window_loaded` |
| `rdp_kafka_export_dataset_json` | `export_dataset_to_kafka` |

All calls return the usual JSON envelope: `{ "ok": true, "interchange": { … } }` (or `{ "ok": false, "error": "…" }`).

### Load (fixture — no broker)

`docs/java/examples/KafkaEltLoadExample.java` — map `tests/fixtures/kafka/stream_records.json` into a landing `dataset` via `rdp_kafka_elt_load_records_json`.

### Connect to a broker (Extract → Load)

Consumer config is a JSON object (not Java `Properties`):

```json
{
  "brokers": "localhost:9092",
  "group_id": "rdp-elt-java",
  "topic": "events",
  "max_records": 500,
  "auto_offset_reset": "earliest",
  "session_timeout_ms": 10000
}
```

Landing schema uses Rust `Schema` serde shape (`data_type`, not Python’s list form):

```json
{
  "fields": [
    { "name": "user_id", "data_type": "Int64" },
    { "name": "event", "data_type": "Utf8" },
    { "name": "_kafka_offset", "data_type": "Int64" }
  ]
}
```

**Extract + Load** in one FFI call:

```java
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import org.json.JSONObject;

Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty(); // RDP_JVM_SYS
Linker linker = Linker.nativeLinker();
try (Arena arena = Arena.ofConfined()) {
  SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);

  String consumerConfig =
      """
      {"brokers":"localhost:9092","group_id":"rdp-elt-java","topic":"events","max_records":500}
      """;
  String landingSchema =
      """
      {"fields":[
        {"name":"user_id","data_type":"Int64"},
        {"name":"event","data_type":"Utf8"},
        {"name":"_kafka_offset","data_type":"Int64"}
      ]}
      """;

  JSONObject root =
      RdpNativeJson.invokeKafkaPollWindowLoadedJson(
          linker, lookup, arena, consumerConfig, landingSchema);
  if (!root.getBoolean("ok")) {
    throw new IllegalStateException(root.getString("error"));
  }
  JSONObject dataset = root.getJSONObject("interchange").getJSONObject("dataset");
  int rows = dataset.getJSONArray("rows").length();
  System.out.println("Landed " + rows + " rows (offsets in _kafka_* columns)");
}
```

Runnable loop example: `docs/java/examples/KafkaEltStreamExample.java`

```bash
# optional: KAFKA_BROKERS, KAFKA_GROUP_ID, KAFKA_TOPIC env vars
java KafkaEltStreamExample localhost:9092 rdp-elt-java events 500
```

**Extract only** (raw records, no landing schema):

```java
JSONObject root =
    RdpNativeJson.invokeKafkaPollWindowJson(linker, lookup, arena, consumerConfig);
var records = root.getJSONObject("interchange").getJSONArray("records");
```

### Sink (produce to a topic)

Producer config + dataset JSON (Rust `DataSet` serde):

```java
String producerConfig =
    """
    {"brokers":"localhost:9092","topic":"curated-out","key_column":"user_id"}
    """;
String datasetJson = landedDatasetEnvelope.toString(); // { "schema": {…}, "rows": […] }

JSONObject root =
    RdpNativeJson.invokeKafkaExportDatasetJson(
        linker, lookup, arena, producerConfig, datasetJson);
int sent = root.getJSONObject("interchange").getInt("row_count");
```

### Transform (separate stage)

After landing (Parquet temp export, pipeline JSON, etc.), run **`rdp_run_pipeline_json`** or SQL parity exports on the landed data — same as other connectors.

Requires `rdp_jvm_sys` built with **`--features kafka`**. Linux CI builds `full,kafka`; other platforms need an explicit kafka-enabled native artifact on `RDP_JVM_SYS`.

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

---

## Related

- [`docs/CONNECTORS.md`](CONNECTORS.md) — connector index (Kafka row + build features)
- [`integration_testing/Kafka/`](../integration_testing/Kafka/) — Redpanda Docker + tri-language tests
- [`integration_testing/integration_testing_details.md`](../integration_testing/integration_testing_details.md) — step-by-step flows
- [`docs/java/examples/KafkaEltStreamExample.java`](java/examples/KafkaEltStreamExample.java) — runnable Java tour
