# Using `ffi_manifest.json` from Java (JAR + native `rdp_jvm_sys`)

The file **`bindings/jvm-sys/ffi_manifest.json`** is the **source of truth** for which `extern "C"` symbols exist in the **`rdp_jvm_sys`** shared library. The same bytes are bundled in the **`rust-data-processing-jvm`** JAR so applications can **discover symbols and ABI version at runtime** without parsing C headers.

| Location | Role |
| --- | --- |
| **`bindings/jvm-sys/ffi_manifest.json`** | Canonical manifest (Rust build / reviews) |
| **`rust-data-processing-jvm` JAR** | Classpath resource **`RdpNativeJson.FFI_MANIFEST_RESOURCE`** (`/io/github/scorpio_datalake/rust_data_processing/ffi_manifest.json`) |
| **`RdpNativeJson`** | High-level calls: `invokeAbiVersion`, `invokeParityExport` (JSON `RdpJsonSlice` protocol) |

**CI** enforces that the bundled copy matches `bindings/jvm-sys/ffi_manifest.json` (`python scripts/check_jvm_ffi_manifest.py`).

---

## 1. Maven dependency

Use the same **`groupId`** / **`version`** as the published module (see **`bindings/java/VERSION`**). Add **two** artifacts: the Java API JAR and **one** native classifier for your OS/CPU.

```xml
<properties>
  <rdp.jvm.version>0.3.4</rdp.jvm.version>
  <rdp.jvm.native.classifier>linux-x86_64</rdp.jvm.native.classifier>
</properties>

<dependencies>
  <dependency>
    <groupId>io.github.scorpio-datalake.rust-data-processing</groupId>
    <artifactId>rust-data-processing-jvm</artifactId>
    <version>${rdp.jvm.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.scorpio-datalake.rust-data-processing</groupId>
    <artifactId>rdp-jvm-sys</artifactId>
    <version>${rdp.jvm.version}</version>
    <classifier>${rdp.jvm.native.classifier}</classifier>
  </dependency>
</dependencies>
```

The main JAR does **not** embed the native binary. Classifier table: **[`NATIVE_ARTIFACT_PACKAGING.md`](NATIVE_ARTIFACT_PACKAGING.md)**. `RdpNativeJson` loads from **`META-INF/native/`** on the classpath — no **`RDP_JVM_SYS`** required.

**From source:** build **`bindings/jvm-sys`** (see **`bindings/java/rust-data-processing-jvm/README.md`**) and set **`RDP_JVM_SYS`** or **`-Drdp.jvm.sys.library`** instead of the classifier dependency.

---

## 2. JVM flags and native library path

Panama **FFM** downcalls require native access:

```text
--enable-native-access=ALL-UNNAMED
```

**Default (Maven Central):** add the matching **`rdp-jvm-sys`** classifier dependency (see **[`NATIVE_ARTIFACT_PACKAGING.md`](NATIVE_ARTIFACT_PACKAGING.md)**). `RdpNativeJson.resolveNativeLibraryFromEnvOrProperty()` loads from **`META-INF/native/`** on the classpath — no environment variable required.

**Override (from source / debugging):** set an absolute path via environment variable:

```bash
export RDP_JVM_SYS=/absolute/path/to/librdp_jvm_sys.so
```

or Java system property:

```text
-Drdp.jvm.sys.library=C:\absolute\path\to\rdp_jvm_sys.dll
```

The examples module uses the same resolution as tests (`ExamplesNativeLibrary` → `RdpNativeJson`).

---

## 3. Read the manifest from the JAR

Use a class from **`rust-data-processing-jvm`** so the resource loads from that JAR:

```java
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

try (InputStream in = RdpNativeJson.class.getResourceAsStream(RdpNativeJson.FFI_MANIFEST_RESOURCE)) {
  if (in == null) {
    throw new IllegalStateException("ffi_manifest.json not on classpath");
  }
  JSONObject manifest = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
  int abi = manifest.getInt("abi_version_constant");
  var symbols = manifest.getJSONArray("exported_symbols");
  // iterate symbols.getString(i) …
}
```

**Runnable repo example:** `LoadFfiManifestExample` in **`bindings/java/rust-data-processing-jvm-examples/`** (prints all symbols and probes ABI when a native library is available on the classpath or via **`RDP_JVM_SYS`**).

---

## 4. Call an exported symbol (parity JSON exports)

Every name in **`exported_symbols`** except **`rdp_json_slice_free`** (free helper) is intended to be resolved with **`SymbolLookup.libraryLookup`**. Parity exports (`rdp_parity_*`) follow the same calling convention as **`RdpNativeJson.invokeParityExport`**: `void (*)(RdpJsonSlice* out)`; JSON is written into the slice; callers must invoke **`rdp_json_slice_free`** on the slice (already done inside **`invokeParityExport`**).

Minimal usage:

```java
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

Linker linker = Linker.nativeLinker();
Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
try (Arena arena = Arena.ofConfined()) {
  SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);
  JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_bindings_mirror");
  // root: keys ok, interchange, notes — same envelope as python-wrapper parity tests
}
```

**Runnable repo examples:** `RunPytestMirrorExample` — pass any **`rdp_parity_*`** name from the manifest as the sole CLI argument. **`ParityScenariosWalkthrough`** (under `rust-data-processing-jvm-examples`) runs several exports in one run and prints short **`interchange`** summaries (see that module’s `README.md`).

To validate JSON shape the same way as unit tests, use **`PytestMirrorAssertions.validateMirrorExport(exportName, root)`** for `*_mirror` exports.

---

## 5. ABI version only

```java
int abi = RdpNativeJson.invokeAbiVersion(linker, lookup);
```

Compare with **`abi_version_constant`** from the manifest; they must match for a compatible **`rdp_jvm_sys`** build.

---

## 6. Classpath-only `java` / `java` from a fat layout

From **`rust-data-processing-jvm-examples`** after `mvn -DskipTests package` (with the main module already `mvn install`’d).

**With a classifier JAR on the classpath (no `RDP_JVM_SYS`):**

```bash
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar:/path/to/rdp-jvm-sys-0.3.4-linux-x86_64.jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.LoadFfiManifestExample
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar:/path/to/rdp-jvm-sys-0.3.4-linux-x86_64.jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.RunPytestMirrorExample rdp_parity_bindings_mirror
```

**From a checkout build (`RDP_JVM_SYS`):**

```bash
export RDP_JVM_SYS=/path/to/librdp_jvm_sys.so
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.LoadFfiManifestExample
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.RunPytestMirrorExample rdp_parity_bindings_mirror
```

On Windows, use `;` instead of `:` in `-cp` and absolute paths.

---

## 7. Large results: prefer Rust-side ETL and files

Many `rdp_parity_*` exports return **`interchange.dataset`** as **JSON** (`schema` + `rows`). That is the default interchange for **tests, contracts, and small tables**.

For **production** and **large** `DataSet` / Polars outputs, **do not** rely on shipping the full table through the JVM as JSON. Instead:

1. Run ingest, transforms, SQL, and validation **in Rust** (or Python calling Rust).
2. **Write** results to **Parquet**, **CSV**, or a **database** (or object storage).
3. Use the JVM only for **orchestration**, **small JSON responses**, or reading **paths** to files written by Rust — then let **Spark (`local[*]`)** or other readers consume those files.

The same idea applies to **every** parity export that materializes a full **`dataset`** in JSON. See **[`EXAMPLES.md` § Rust-first ETL vs JVM consumption](EXAMPLES.md#rust-first-etl-vs-jvm-consumption)** for the full list and rationale. Arrow-based interchange remains a future milestone (**[`ARROW_FFI_JVM.md`](ARROW_FFI_JVM.md)**).

---

## 8. What the manifest does *not* tell you

- **JSON schema** per export — infer from **`python-wrapper/tests`** and **`PytestMirrorAssertions`**, or inspect **`bindings/jvm-sys`** / Rust parity sources.
- **Future non-parity APIs** — when new `extern "C"` entry points ship, they must be added to **`ffi_manifest.json`** and regenerated in the JAR; **`FfiExportedSymbolsContractTest`** catches drift for symbols listed in the manifest.

For the high-level Phase 3 policy (semver, Panama), see **ADR [005](../adr/005-jvm-panama-production-policy.md)** and **[FFI_API_SLICE.md](FFI_API_SLICE.md)**.

---

## 9. Production path ingest and pipeline JSON (non-parity)

These symbols are listed in **`exported_symbols`** and covered by **`FfiExportedSymbolsContractTest`** + **`DocsExampleNativeIntegrationTest`** / **`JvmNativeContractScenarios`**. They return the same `{ ok, interchange, notes }` envelope as parity exports.

| Symbol | Role |
| --- | --- |
| `rdp_ingest_csv_path` | Path + schema JSON + options JSON → `ingest_path_csv` |
| `rdp_ingest_json_path` | JSON / NDJSON path ingest |
| `rdp_ingest_parquet_path` | Parquet path ingest |
| `rdp_ingest_xml_path` | XML path ingest (`format: xml` in options or extension) |
| `rdp_excel_ingest_path_sheet` | Excel sheet ingest (schema inferred in Rust; no schema on the wire) |
| `rdp_ingest_ordered_paths_json` | Multi-path payload: `paths`, `schema` / `schema_ref`, `options`, `response.mode` (`dataset` \| `parquet_temp` \| `arrow_ipc_temp`) |
| `rdp_run_pipeline_json` | Declarative pipeline: sources → optional `transform.sql` on `df` → sinks (`parquet_file`, `xml_file`, …) |
| `rdp_export_parquet_temp` | Small Rust-built sample Parquet in OS temp dir (handoff) |
| `rdp_export_arrow_ipc_temp` | Temp Arrow IPC file handoff |
| `rdp_export_polars_parquet_temp` | Temp Parquet via Polars writer |

**Fixture JSON** lives under `tests/fixtures/<bundle>/` (`schemas/`, `pipelines/`, `payloads/`). Java: **`io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures`**; resolve templates before calling native code. Tour: **[EXAMPLES.md](EXAMPLES.md)**; runnable sources: **`docs/java/examples/*.java`**.

**Build / test locally:**

```powershell
pwsh -File scripts/build_all.ps1
# or: python scripts/python_scripts/build_all.py
```

Requires **`rdp_jvm_sys`** with **`--features full`** (Excel + linked core) — from a Maven **`rdp-jvm-sys`** classifier on the classpath, or a checkout build via **`RDP_JVM_SYS`** — and **`--enable-native-access=ALL-UNNAMED`**.
