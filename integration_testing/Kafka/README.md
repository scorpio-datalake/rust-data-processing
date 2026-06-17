# Kafka streaming integration tests

Tri-language tests streaming the Uber CSV **one row per Kafka message** via Rust `rdkafka`, then polling the topic to verify row count.

## What is tested

- **Produce:** `rdp_kafka_export_dataset_json` — one message per CSV row
- **Consume:** `rdp_kafka_poll_window_loaded_json` — bounded poll + landing schema
- **Broker:** Redpanda (Kafka-compatible) in Docker

Requires `librdp_jvm_sys` built with `--features full,kafka` (run script rebuilds once if needed).

**Step-by-step:** [`integration_testing_details.md`](../integration_testing_details.md) § Kafka · [`docs/KAFKA_ELT.md`](../../docs/KAFKA_ELT.md) · [`docs/java/examples/KafkaEltStreamExample.java`](../../docs/java/examples/KafkaEltStreamExample.java)

## Run

```bash
python3 integration_testing/scripts/build_libs/build_all_libs.py
python3 integration_testing/scripts/data_download/download_uber_data.py --sample
python3 integration_testing/Kafka/run_kafka_tests.py
```
