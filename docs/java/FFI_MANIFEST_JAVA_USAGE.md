# Using `ffi_manifest.json` from Java (JAR + native `rdp_jvm_sys`)

The file **`bindings/jvm-sys/ffi_manifest.json`** is the **source of truth** for which `extern "C"` symbols exist in the **`rdp_jvm_sys`** shared library. The same bytes are bundled in the **`rust-data-processing-jvm`** JAR so applications can **discover symbols and ABI version at runtime** without parsing C headers.

| Location | Role |
| --- | --- |
| **`bindings/jvm-sys/ffi_manifest.json`** | Canonical manifest (Rust build / reviews) |
| **`rust-data-processing-jvm` JAR** | Classpath resource **`RdpNativeJson.FFI_MANIFEST_RESOURCE`** (`/io/github/rust_data_processing/ffi_manifest.json`) |
| **`RdpNativeJson`** | High-level calls: `invokeAbiVersion`, `invokeParityExport` (JSON `RdpJsonSlice` protocol) |

**CI** enforces that the bundled copy matches `bindings/jvm-sys/ffi_manifest.json` (`python scripts/check_jvm_ffi_manifest.py`).

---

## 1. Maven dependency

Use the same **`groupId`** / **`artifactId`** / **`version`** as the published module (or `0.1.0-SNAPSHOT` when building locally after `mvn install`):

```xml
<dependency>
  <groupId>io.github.rust_data_processing</groupId>
  <artifactId>rust-data-processing-jvm</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

You still need the **native** library (`librdp_jvm_sys.so`, `rdp_jvm_sys.dll`, or `librdp_jvm_sys.dylib`) built from **`bindings/jvm-sys`** (see **`bindings/java/rust-data-processing-jvm/README.md`**). The JAR does **not** embed that binary.

---

## 2. JVM flags and native library path

Panama **FFM** downcalls require native access:

```text
--enable-native-access=ALL-UNNAMED
```

Set the library path (absolute) via environment variable:

```bash
export RDP_JVM_SYS=/absolute/path/to/librdp_jvm_sys.so
```

or Java system property:

```text
-Drdp.jvm.sys.library=C:\absolute\path\to\rdp_jvm_sys.dll
```

The examples module uses the same resolution as tests (`ExamplesNativeLibrary`).

---

## 3. Read the manifest from the JAR

Use a class from **`rust-data-processing-jvm`** so the resource loads from that JAR:

```java
import io.github.rust_data_processing.ffi.RdpNativeJson;
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

**Runnable repo example:** `LoadFfiManifestExample` in **`bindings/java/rust-data-processing-jvm-examples/`** (prints all symbols and probes ABI when `RDP_JVM_SYS` is set).

---

## 4. Call an exported symbol (parity JSON exports)

Every name in **`exported_symbols`** except **`rdp_json_slice_free`** (free helper) is intended to be resolved with **`SymbolLookup.libraryLookup`**. Parity exports (`rdp_parity_*`) follow the same calling convention as **`RdpNativeJson.invokeParityExport`**: `void (*)(RdpJsonSlice* out)`; JSON is written into the slice; callers must invoke **`rdp_json_slice_free`** on the slice (already done inside **`invokeParityExport`**).

Minimal usage:

```java
import io.github.rust_data_processing.ffi.RdpNativeJson;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

Linker linker = Linker.nativeLinker();
Path lib = Path.of(System.getenv("RDP_JVM_SYS"));
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

From **`rust-data-processing-jvm-examples`** after `mvn -DskipTests package` (with the main module already `mvn install`’d):

```bash
export RDP_JVM_SYS=/path/to/librdp_jvm_sys.so
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar" \
  io.github.rust_data_processing.examples.LoadFfiManifestExample
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar" \
  io.github.rust_data_processing.examples.RunPytestMirrorExample rdp_parity_bindings_mirror
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
