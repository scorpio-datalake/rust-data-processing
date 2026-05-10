# FFI API slice — Rust ↔ JVM boundary

**Phase 3 rule:** Every **user-visible** Rust capability shipped on JVM must appear in **`Planning/PHASE3_EPICS.md`** (parity rows) as **done** or link an **ADR exception**.

## Rust-only (not projected across `extern "C"` today)

| Category | Examples | Notes |
| --- | --- | --- |
| **Macros** | Any `macro_rules!` expanding to pipeline DSL | Recreate as Java builders / static factories. |
| **Generic trait combos** | Highly generic combinators without stable ABI | Narrow to concrete FFI typedefs per ADR. |
| **Internal modules** | `#[doc(hidden)]` helpers | Not part of public parity promise. |

*(Extend this table as APIs stabilize.)*

## Arrow ownership (summary)

| Direction | Owner frees | Borrow rules |
| --- | --- | --- |
| **Rust → JVM** | JVM **`Arena`** scope owns **`MemorySegment`** copies unless IPC zero-copy negotiated | Document per-call in generated **`jextract`** docstrings. |
| **JVM → Rust** | Rust **`drop_glue`** / explicit **`rdp_*_release`** after parity wiring | **TBD** in **`ARROW_FFI_JVM.md`**. |

Full detail: **`ARROW_FFI_JVM.md`**.
