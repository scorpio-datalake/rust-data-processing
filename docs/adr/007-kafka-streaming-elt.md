# ADR 007: Kafka streaming ELT and poll windows

**Status:** Accepted (Phase 3 — P3-E2)

## Context

Kafka is a **streaming** system. File and warehouse connectors in RDP use ingest-then-process patterns suited to bounded inputs. Applying the same naming (`ingest_from_*`, `batch`) to Kafka caused confusion: users read it as **batch ETL**, not **stream ELT**.

Phase 3 requires Rust, Python, and JVM surfaces: **Rust owns Kafka I/O**; Python and JVM are **thin wrappers**.

## Decision

### 1. ELT, not ETL, for Kafka

- **Extract:** poll a bounded window from a topic (`poll_kafka_window`) or accept records from a host consumer.
- **Load:** map records to a landing `DataSet` with minimal parsing (`elt_load_kafka_records`) and write to durable storage (Parquet, Postgres COPY, object store). Preserve offsets in landing columns.
- **Transform:** Polars SQL, pipeline JSON, validation — **separate stage**, not inside the consumer hot path.

### 2. Terminology

| Term | Meaning |
| --- | --- |
| `KafkaStreamRecord` | One Kafka event (topic, partition, offset, payload) |
| Poll window | `Vec<KafkaStreamRecord>` from one `poll` cycle — backpressure/checkpoint sizing |
| ~~micro-batch~~ / ~~BytesTopicBatch~~ | Deprecated naming; use poll window / stream record |

### 3. Native build (`rdkafka`)

- Cargo feature **`kafka`** enables `rdkafka` with **`cmake-build`** (vendored librdkafka) so CI/dev do not depend on distro package versions.
- CI Linux job installs build deps: `cmake`, `libssl-dev`, `zlib1g-dev`, `libcurl4-openssl-dev`.
- **At-least-once** baseline: producer flush, consumer manual commit left to host loop (documented). Exactly-once only if explicitly scoped later.

### 4. Wrapper ABI (Python / JVM)

- Python **`elt_load_kafka_records_json`** and future **`poll_kafka_window`** / **`export_dataset_to_kafka`** are thin PyO3 forwards to Rust.
- JVM **`rdp_kafka_*`** Panama symbols mirror the same Rust functions (JSON envelopes like other ingest FFIs).
- **No** host-side `kafka-clients` / `confluent_kafka` ingestion paths.

### 5. SSL / SASL

- Native clients use librdkafka config via `KafkaConsumerBuilder` / `KafkaProducerBuilder` (brokers, group, timeouts).
- SSL/SASL broker settings pass through standard `rdkafka` ClientConfig keys in a future builder API extension; document env/KIP-style config in CONNECTORS.md when enabled.

## Consequences

- Examples and docs use **ELT** staging (`docs/KAFKA_ELT.md`, `examples/kafka_elt_*`).
- File connector docs unchanged; Kafka has its own doc trail.
- Tests cover **Load** without a broker (fixture JSON); broker integration remains opt-in/`#[ignore]`.

## References

- `Planning/PHASE3_EPICS.md` P3-E2
- `docs/KAFKA_ELT.md`
