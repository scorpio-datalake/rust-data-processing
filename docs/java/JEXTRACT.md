# `jextract` workflow (**P3-E1-S1b**)

Regenerate Panama stubs from **`bindings/jvm-sys/include/rdp_jvm_sys.h`**:

```bash
# JDK 22+ provides `jextract` on PATH (tool exact path varies by distro).
jextract --output bindings/java/rust-data-processing-jvm/src/main/java/io/github/rust_data_processing/internal/foreign \
  --target-package io.github.rust_data_processing.internal.foreign \
  bindings/jvm-sys/include/rdp_jvm_sys.h
```

**CI:** **`jvm_bindings_ci.yml`** runs **`scripts/check_jvm_ffi_manifest.sh`** (Linux). **`jextract`** drift check is **manual / optional** until headers stabilize (`/** @skip jextract */` policy in ADR 005).

After regeneration: **`mvn verify`** **and** **`./gradlew check`**.
