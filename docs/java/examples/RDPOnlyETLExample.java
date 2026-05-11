import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * <p><b>Principle:</b> <strong>Rust does all heavy work.</strong> The JVM only supplies <em>pipeline
 * metadata</em> and <em>schemas</em> (and small control-plane JSON). Do <strong>not</strong> pull
 * large tables into Java objects only to push them back into Rust — that doubles serialization cost,
 * blows the heap, and makes GC worse. Prefer:
 *
 * <ul>
 *   <li>FFI payloads where Rust reads paths and writes <strong>sinks inside Rust</strong> (future:
 *       Iceberg / Delta / database orchestration entrypoint), or
 *   <li>{@code rdp_ingest_ordered_paths_json} with {@code response.mode} = {@code parquet_temp} or
 *       {@code arrow_ipc_temp} so the JVM receives only <strong>path + row_count + schema</strong>,
 *       not every row as JSON.
 * </ul>
 *
 * <p><b>Story (student / grades / lake / PostgreSQL):</b> many JSON files list students (names,
 * courses, grades, teachers). Rust ingests with a <strong>shared schema</strong>, normalizes to
 * <strong>one row per student</strong> (or your chosen grain — still decided in Rust). A data-lake
 * table stores <strong>base student fields plus per-grade statistics</strong> (e.g. std dev of
 * scores) computed in Rust. A final PostgreSQL load keeps <strong>courses and teachers</strong>;
 * <strong>student PII is dropped in Rust</strong> before the database write — Java never materializes those
 * rows for “cleanup”.
 *
 * <ol>
 *   <li>Rust: read many JSON paths (list or glob resolved in Rust) using JVM-supplied schema.
 *   <li>Rust: union / validate → one logical table (Polars DataFrame stays in Rust).
 *   <li>Rust: write lake table(s) (Iceberg / Delta — placeholders below until wired in RDP).
 *   <li>Rust: aggregate grades (e.g. std) in Rust; land wide/narrow tables in the lake.
 *   <li>Rust: project to course/teacher columns, drop student fields, load PostgreSQL from Rust.
 * </ol>
 *
 * <p><b>What this class prints:</b> (1) <strong>Concrete {@code Schema} JSON</strong> for each
 * stage (student JSON lines, lake grade stats, PostgreSQL course/teacher) using serde field names
 * ({@code Int64}, {@code Utf8}, …). (2) A compact <strong>pipeline spec</strong> (paths + sink
 * placeholders). (3) A few <strong>tiny</strong> live FFI calls that only return <strong>schemas</strong>
 * or temp-file metadata — not bulk rows for the student story.
 *
 * <h2>Shortcomings (current implementation)</h2>
 *
 * <ul>
 *   <li><b>No single orchestration FFI</b> yet that runs the full student→lake→PG story from one JSON
 *       document; compose <strong>Rust</strong> jobs or call granular symbols ({@code
 *       rdp_ingest_ordered_paths_json}, future sink FFI) from Java.
 *   <li><b>Iceberg / Delta / PostgreSQL</b> are not implemented inside {@code rdp_jvm_sys}; URLs and
 *       table names are <strong>contracts Java would pass to Rust</strong>, not live drivers from
 *       Java.
 *   <li>For large N, avoid {@code response.mode} = {@code dataset} over FFI; use {@code parquet_temp}
 *       or {@code arrow_ipc_temp} (or keep sinks entirely in Rust).
 * </ul>
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
   * Full <strong>control-plane</strong> document Java would register with Rust (or split across
   * calls). No row arrays — only schemas, paths, sink hints.
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
                // Rust does not use JDBC. Native clients (e.g. tokio-postgres, sqlx, ConnectorX)
                // expect a libpq-style URL such as postgresql://… — not jdbc:postgresql:…
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

  /**
   * Example payload for {@link RdpNativeJson#invokeIngestOrderedPathsJson} — Rust reads every path,
   * applies one schema, returns <strong>only</strong> a temp Parquet path when {@code mode} is
   * {@code parquet_temp} (no {@code dataset} rows on the JVM). Replace paths with real absolute
   * filesystem paths for a local demo.
   */
  public static JSONObject exampleOrderedIngestParquetTempPayload() {
    JSONArray paths =
        new JSONArray()
            .put("/data/students/part-00000.json")
            .put("/data/students/part-00001.json");
    return new JSONObject()
        .put("paths", paths)
        .put("schema", schemaStudentJsonSource())
        .put("options", new JSONObject().put("format", "json"))
        .put("response", new JSONObject().put("mode", "parquet_temp"));
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

  public static void printInterchangeSchema(String label, JSONObject interchange) {
    if (!interchange.has("schema")) {
      System.out.println("--- " + label + " (no schema field on interchange) ---");
      return;
    }
    System.out.println("--- " + label + " ---");
    System.out.println(interchange.get("schema").toString());
  }

  /**
   * Prints <strong>declared</strong> student/lake/PostgreSQL schemas, the pipeline spec, an example
   * {@code rdp_ingest_ordered_paths_json} payload (no call — paths are fake), then tiny FFI calls
   * that only surface schemas or temp-file metadata.
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

      printPipelineSpec(syntheticPipelineSpec());

      System.out.println(
          "=== Example ordered ingest payload (parquet_temp = no row JSON on JVM) ===");
      System.out.println(exampleOrderedIngestParquetTempPayload().toString(2));

      JSONObject typesRoot =
          RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_types_dataset");
      PytestMirrorAssertions.assertEnvelopeOk(typesRoot);
      printDatasetSchema(
          "Illustrative: small Rust-built DataSet schema (not the student pipeline)",
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

      JSONObject parquetTempRoot = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena);
      PytestMirrorAssertions.assertEnvelopeOk(parquetTempRoot);
      JSONObject interchange = parquetTempRoot.getJSONObject("interchange");
      printInterchangeSchema(
          "Illustrative: temp Parquet handoff schema (path on disk; rows not in JSON)",
          interchange);
      Path parquetPath = Path.of(interchange.getString("path"));
      System.out.println("Temp Parquet path (Rust wrote bytes; JVM deletes after readers): " + parquetPath);
      Files.deleteIfExists(parquetPath);

      System.out.println(
          "--- Lake / PostgreSQL execution: stays in Rust (native URLs, not JDBC); "
              + "Java never round-trips student rows ---");
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
