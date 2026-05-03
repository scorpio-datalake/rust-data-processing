# LLM fine-tuning prep — small open corpora (reference)

## Pick one corpus (examples)

| Corpus | Notes |
|--------|--------|
| **Databricks Dolly** | Instruction-style JSONL; check current license on Hugging Face / Databricks pages before use. |
| **Alpaca** | Classic `instruction` / `input` / `output` rows. |
| **OpenAssistant** | Larger multi-turn; heavier download. |

## Workflow with this library

1. Download the corpus per upstream instructions (document **license**, **size**, and **checksum** in your own project).
2. Ingest a slice to `DataSet` (CSV/JSONL) or convert rows to `DataSet(schema, rows)` in Python.
3. Run **`validate_dataset`** + **`profile_dataset`** on text columns.
4. Shape columns with **`transform_apply`** (JSON `TransformSpec`) — rename, truncate, hash per [`Planning/P2_E6_PRIVACY_POLICY.md`](../../Planning/P2_E6_PRIVACY_POLICY.md).
5. Export **`export_dataset_jsonl`** with explicit column order.

## **Chat templates**

**Export is raw text.** Tokenizer and chat template live in **HF TRL / PEFT / Llama-Factory** — align there.

See also: **[`docs/SFT_DATA_FORMATS.md`](../../docs/SFT_DATA_FORMATS.md)**, **[`notebooks/ml_qa.ipynb`](../../notebooks/ml_qa.ipynb)** (tabular), and **`export_dataset_jsonl`** in Python for JSONL output.

Upstream links (may change): [Hugging Face Docs](https://huggingface.co/docs), [PEFT](https://huggingface.co/docs/peft), [TRL](https://huggingface.co/docs/trl).
