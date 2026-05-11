import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * <p><b>Principle:</b> <strong>Rust does all heavy work.</strong> The JVM only supplies <em>pipeline
 * metadata</em> and <em>schemas</em> (control-plane JSON). Do <strong>not</strong> pull large tables
 * into Java only to push them back into Rust. The primary orchestration entry is {@code
 * rdp_run_pipeline_json}: Polars plans and sink drivers run in Rust; the response is summaries and
 * sink statuses — not bulk student rows.
 *
 * <p><b>Other FFI modes (not used here):</b> {@code rdp_ingest_ordered_paths_json} can return a temp
 * Parquet/Arrow <strong>file path</strong> so something <em>on the JVM</em> (e.g. Spark) reads bytes
 * from disk. That is unrelated to {@code rdp_run_pipeline_json}, where sinks stay in Rust — see
 * contract tests if you need that path.
 *
 * <p><b>Story (student / grades / lake / PostgreSQL):</b> many JSON files list students. Rust
 * ingests with a shared schema, keeps a Polars frame in process, reports Delta/Iceberg/PostgreSQL
 * sink phases from the same JSON spec the JVM built (see {@link #syntheticPipelineSpec()}).
 * PostgreSQL loads use a libpq-style URL (not JDBC). With {@code --features sink_postgres} on {@code
 * rdp_jvm_sys}, the PostgreSQL sink performs a native {@code COPY}; otherwise it returns {@code
 * skipped} with a rebuild hint. Delta and Iceberg return {@code connector_pending} until their
 * native writers are enabled in the build.
 *
 * <p><b>What this class does when you run {@link #demonstrateSchemas}:</b> prints declared schemas,
 * writes tiny on-disk JSON fixtures, calls {@link RdpNativeJson#invokeRunPipelineJson} with the
 * legacy control-plane shape, calls {@link RdpNativeJson#invokeIngestOrderedPathsJson} in {@code
 * dataset} mode (capped rows), and runs small parity exports for schema smoke tests.
 *
 * <p><b>Paths:</b> {@link #exampleJsonSourcePaths()} lists {@code s3://} URIs as a production sketch.
 * {@code rdp_run_pipeline_json} currently accepts <strong>local filesystem paths</strong> only for
 * ingestion; object-store reads require additional Rust wiring. The live demo replaces those URIs
 * with temp files.
 *
 * <p>Run with {@code RDP_JVM_SYS} (or {@code -Drdp.jvm.sys.library}) and {@code
 * --enable-native-access=ALL-UNNAMED}. Not built by the main Maven module — copy into {@code
 * rust-data-processing-jvm-examples} if you want {@code mvn} to compile it.
 */
public final class RDPOnlyETLExample {

  private RDPOnlyETLExample() {}

  /**
   * Serde {@code Schema} shape for <strong>NDJSON / JSON array-of-objects</strong> student files
   * (one logical row per student after Rust normalizes nested arrays). Field names use dot paths
   * where JSON is nested. {@code data_type} values must match Rust serde: {@code Int64}, {@code
   * Utf8}, {@code Float64}, {@code Bool} — not lowercase.
   */
  public static JSONObject schemaStudentJsonSource() {
    JSONArray fields =
        new JSONArray()
            .put(new JSONObject().put("name", "student_id").put("data_type", "Int64"))
            .put(new JSONObject().put("name", "legal_name").put("data_type", "Utf8"))
            .put(new JSONObject().put("name", "email").put("data_type", "Utf8"))
            .put(new JSONObject().put("name", "homeroom").put("data_type", "Utf8"))
            .put(new JSONObject().put("name", "gpa").put("data_type", "Float64"))
            .put(new JSONObject().put("name", "enrollment_year").put("data_type", "Int64"));
    return new JSONObject().put("fields", fields);
  }

  /**
   * Target <strong>lake / wide</strong> table: base student keys plus per-student grade dispersion
   * computed in Rust (example columns). Java only declares the shape Rust should write.
   */
  public static JSONObject schemaLakeStudentGradeStats() {
    JSONArray fields =
        new JSONArray()
            .put(new JSONObject().put("name", "student_id").put("data_type", "Int64"))
            .put(new JSONObject().put("name", "legal_name").put("data_type", "Utf8"))
            .put(new JSONObject().put("name", "course_count").put("data_type", "Int64"))
            .put(new JSONObject().put("name", "gpa").put("data_type", "Float64"))
            .put(new JSONObject().put("name", "score_mean").put("data_type", "Float64"))
            .put(new JSONObject().put("name", "score_std").put("data_type", "Float64"));
    return new JSONObject().put("fields", fields);
  }

  /**
   * <strong>PostgreSQL</strong> bound table: courses and teachers only — <strong>no student PII</strong>.
   * Rust drops student columns before INSERT/UPSERT.
   */
  public static JSONObject schemaPostgresCoursesTeachers() {
    JSONArray fields =
        new JSONArray()
            .put(new JSONObject().put("name", "course_id").put("data_type", "Int64"))
            .put(new JSONObject().put("name", "course_code").put("data_type", "Utf8"))
            .put(new JSONObject().put("name", "course_title").put("data_type", "Utf8"))
            .put(new JSONObject().put("name", "teacher_id").put("data_type", "Int64"))
            .put(new JSONObject().put("name", "teacher_name").put("data_type", "Utf8"))
            .put(new JSONObject().put("name", "teacher_department").put("data_type", "Utf8"));
    return new JSONObject().put("fields", fields);
  }

  /** Example source list (three URIs); production uses however many paths Rust should scan. */
  public static JSONArray exampleJsonSourcePaths() {
    return new JSONArray()
        .put("s3://school-rdp/students/year=2024/part-00000.json")
        .put("s3://school-rdp/students/year=2024/part-00001.json")
        .put("s3://school-rdp/students/year=2024/part-00002.json");
  }

  /**
   * Full <strong>control-plane</strong> document the JVM passes to {@code rdp_run_pipeline_json}
   * (Rust accepts this legacy shape and maps it to internal sinks). No row arrays — only schemas,
   * paths, sink hints.
   */
  public static JSONObject syntheticPipelineSpec() {
    return new JSONObject()
        .put("engine", "rdp_rust_only_etl")
        .put("json_source_paths", exampleJsonSourcePaths())
        .put("schema_student_json", schemaStudentJsonSource())
        .put("schema_lake_grade_stats", schemaLakeStudentGradeStats())
        .put("schema_postgres_courses_teachers", schemaPostgresCoursesTeachers())
        .put(
            "lake_sink",
            new JSONObject()
                .put("format", "delta_or_iceberg_tbd")
                .put("catalog_uri", "thrift://iceberg-catalog.example:9083")
                .put("warehouse", "s3://school-warehouse/")
                .put("namespace", "curated")
                .put("table_student_grades", "student_grade_stats"))
        .put(
            "relational_sink",
            new JSONObject()
                .put(
                    "postgresql_url",
                    "postgresql://app:CHANGE_ME@db.example:5432/school?sslmode=require")
                .put("courses_teachers_table", "public.courses_teachers"))
        .put(
            "notes",
            "Rust ingests JSON with schema_student_json, aggregates std in Rust, writes lake "
                + "using schema_lake_grade_stats, then projects to schema_postgres_courses_teachers "
                + "and loads PostgreSQL from Rust using postgresql_url (native driver, not JDBC) — "
                + "all without bulk row JSON crossing the JVM boundary.");
  }

  /** One JSON array-of-objects line matching {@link #schemaStudentJsonSource()}. */
  public static String minimalStudentJsonLine(long studentId, String name) {
    return "[{\"student_id\":"
        + studentId
        + ",\"legal_name\":\""
        + name
        + "\",\"email\":\""
        + name.toLowerCase()
        + "@school.example\",\"homeroom\":\"10A\",\"gpa\":3.5,\"enrollment_year\":2024}]";
  }

  /**
   * Example payload for {@link RdpNativeJson#invokeIngestOrderedPathsJson} — {@code dataset} mode
   * returns capped rows as JSON (fine for small demos). Replace paths with absolute filesystem
   * paths.
   */
  public static JSONObject exampleOrderedIngestDatasetPayload(JSONArray absolutePaths) {
    return new JSONObject()
        .put("paths", absolutePaths)
        .put("schema", schemaStudentJsonSource())
        .put("options", new JSONObject().put("format", "json"))
        .put("response", new JSONObject().put("mode", "dataset").put("max_rows", 50));
  }

  public static void printPipelineSpec(JSONObject spec) {
    System.out.println("--- Pipeline spec (schemas + sinks; execution stays in Rust) ---");
    System.out.println(spec.toString(2));
  }

  public static void printDatasetSchema(String label, JSONObject dataset) {
    JSONObject schema = dataset.getJSONObject("schema");
    System.out.println("--- " + label + " (tabular JSON schema over FFI) ---");
    System.out.println(schema.toString(2));
  }

  /**
   * Prints declared schemas, the <strong>conceptual</strong> spec with {@code s3://} paths, runs
   * {@code rdp_run_pipeline_json} on temp JSON fixtures, runs capped {@code rdp_ingest_ordered_paths_json},
   * then small parity exports.
   */
  public static void demonstrateSchemas(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);

      System.out.println("=== Declared schemas (Java-built JSON; Rust consumes same shape) ===");
      System.out.println("Student JSON source schema:");
      System.out.println(schemaStudentJsonSource().toString(2));
      System.out.println("Lake table (grade stats) schema:");
      System.out.println(schemaLakeStudentGradeStats().toString(2));
      System.out.println("PostgreSQL (courses/teachers, no student PII) schema:");
      System.out.println(schemaPostgresCoursesTeachers().toString(2));

      System.out.println("=== Conceptual pipeline spec (S3 paths — ingest requires local paths today) ===");
      printPipelineSpec(syntheticPipelineSpec());

      Path demoDir = Files.createTempDirectory("rdp_student_etl_demo_");
      try {
        Path p0 = demoDir.resolve("part-00000.json");
        Path p1 = demoDir.resolve("part-00001.json");
        Path p2 = demoDir.resolve("part-00002.json");
        Files.writeString(p0, minimalStudentJsonLine(1, "Ada"), StandardCharsets.UTF_8);
        Files.writeString(p1, minimalStudentJsonLine(2, "Bob"), StandardCharsets.UTF_8);
        Files.writeString(p2, minimalStudentJsonLine(3, "Chen"), StandardCharsets.UTF_8);
        JSONArray localPaths =
            new JSONArray()
                .put(p0.toAbsolutePath().toString())
                .put(p1.toAbsolutePath().toString())
                .put(p2.toAbsolutePath().toString());

        JSONObject livePipeline = syntheticPipelineSpec().put("json_source_paths", localPaths);
        System.out.println("=== Live rdp_run_pipeline_json (legacy control-plane JSON) ===");
        JSONObject pipelineRoot =
            RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, livePipeline.toString());
        System.out.println(pipelineRoot.toString(2));
        PytestMirrorAssertions.assertEnvelopeOk(pipelineRoot);
        JSONObject pInter = pipelineRoot.getJSONObject("interchange");
        System.out.println(
            "Ingested rows (Rust, ordered paths): " + pInter.getInt("ingested_row_count"));
        System.out.println("Sink phases: " + pInter.getJSONArray("sink_results").toString(2));

        JSONArray twoPaths =
            new JSONArray()
                .put(p0.toAbsolutePath().toString())
                .put(p1.toAbsolutePath().toString());
        JSONObject orderedDataset =
            RdpNativeJson.invokeIngestOrderedPathsJson(
                linker, lookup, arena, exampleOrderedIngestDatasetPayload(twoPaths).toString());
        System.out.println(
            "=== rdp_ingest_ordered_paths_json (dataset, capped) — granular ingest without sinks ===");
        PytestMirrorAssertions.assertEnvelopeOk(orderedDataset);
        System.out.println(orderedDataset.getJSONObject("interchange").toString(2));
      } finally {
        try (var walk = Files.walk(demoDir)) {
          for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(p);
          }
        }
      }

      JSONObject typesRoot =
          RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_types_dataset");
      PytestMirrorAssertions.assertEnvelopeOk(typesRoot);
      printDatasetSchema(
          "Illustrative: small Rust-built DataSet schema (parity, not the student pipeline)",
          typesRoot.getJSONObject("interchange").getJSONObject("dataset"));

      JSONObject sqlRoot =
          RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_pipeline_sql");
      if (sqlRoot.optBoolean("ok", false)) {
        PytestMirrorAssertions.assertEnvelopeOk(sqlRoot);
        printDatasetSchema(
            "Illustrative: Polars SQL result schema (rdp_parity_pipeline_sql)",
            sqlRoot.getJSONObject("interchange").getJSONObject("dataset"));
      } else {
        System.out.println(
            "--- rdp_parity_pipeline_sql: skipped (ok=false; rebuild rdp_jvm_sys with link-main?) ---");
        System.out.println(sqlRoot.toString(2));
      }
    }
  }

  public static void main(String[] args) throws Throwable {
    String env = System.getenv("RDP_JVM_SYS");
    String prop = System.getProperty("rdp.jvm.sys.library");
    Path lib = null;
    if (env != null && !env.isBlank()) {
      Path p = Path.of(env.strip()).toAbsolutePath();
      if (Files.exists(p)) {
        lib = p;
      }
    }
    if (lib == null && prop != null && !prop.isBlank()) {
      Path p = Path.of(prop.strip()).toAbsolutePath();
      if (Files.exists(p)) {
        lib = p;
      }
    }
    if (lib == null) {
      System.err.println("Set RDP_JVM_SYS or -Drdp.jvm.sys.library to a built rdp_jvm_sys library.");
      System.exit(2);
    }
    demonstrateSchemas(lib);
  }
}
