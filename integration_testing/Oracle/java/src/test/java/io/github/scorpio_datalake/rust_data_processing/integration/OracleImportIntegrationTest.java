package io.github.scorpio_datalake.rust_data_processing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Oracle integration (self-contained under {@code integration_testing/Oracle/java/}).
 *
 * <p>Uses the built {@code rdp_jvm_sys} library for CSV ingest and JDBC for Oracle load (RDP has no
 * Oracle sink yet).
 */
@EnabledIfEnvironmentVariable(named = "RUN_ORACLE_INTEGRATION", matches = "1")
final class OracleImportIntegrationTest {

  private static final Path ORACLE_ROOT =
      Path.of(System.getenv().getOrDefault("RDP_ORACLE_ROOT", "integration_testing/Oracle"))
          .toAbsolutePath()
          .normalize();
  private static final Path INTEGR_ROOT = ORACLE_ROOT.getParent();
  private static final Path SCHEMA = ORACLE_ROOT.resolve("schema/uber_pickups.schema.json");

  @Test
  void javaCsvIngestLoadAndVerify() throws Throwable {
    String url = System.getenv("ORACLE_CONNECT_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "ORACLE_CONNECT_URL not set");

    Path csv = resolveUberCsv();
    String schemaJson = Files.readString(SCHEMA, StandardCharsets.UTF_8);
    int maxRows =
        Integer.parseInt(System.getenv().getOrDefault("INTEG_MAX_IMPORT_ROWS", "500"));

    resetTable(jdbcUrl(url));

    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    Assumptions.assumeTrue(lib != null, "RDP_JVM_SYS / rdp.jvm.sys.library not set");

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);
      JSONObject root =
          RdpNativeJson.invokeIngestCsvPath(
              linker, lookup, arena, csv.toString(), schemaJson, "{}");
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject dataset = root.getJSONObject("interchange").getJSONObject("dataset");
      JSONArray rows = dataset.getJSONArray("rows");
      int expected = Math.min(rows.length(), maxRows);
      assertTrue(expected > 0, "CSV ingest returned no rows");

      loadDataset(jdbcUrl(url), dataset, expected);

      // Verify via RDP read would need db_reads pipeline; count via JDBC here.
      assertEquals(expected, countRows(jdbcUrl(url)));
    }
  }

  private static Path resolveUberCsv() throws Exception {
    Path sample = INTEGR_ROOT.resolve("data/uber_nyc_pickups_sample.csv");
    Path full = INTEGR_ROOT.resolve("data/uber_nyc_pickups_apr2014.csv");
    if (Files.isRegularFile(sample)) {
      return sample;
    }
    if (Files.isRegularFile(full)) {
      return full;
    }
    Assumptions.assumeTrue(false, "Uber CSV missing — run download_uber_data.py");
    return sample;
  }

  /** ConnectorX {@code oracle://} → JDBC thin URL. */
  private static String jdbcUrl(String connectorxUrl) {
    // oracle://user:pass@host:1521/XEPDB1
    String rest = connectorxUrl.strip();
    if (!rest.startsWith("oracle://")) {
      throw new IllegalArgumentException("expected oracle:// URL");
    }
    rest = rest.substring("oracle://".length());
    int at = rest.indexOf('@');
    if (at < 0) {
      throw new IllegalArgumentException("invalid oracle URL");
    }
    String auth = rest.substring(0, at);
    String hostpart = rest.substring(at + 1);
    int colon = auth.indexOf(':');
    String user = auth.substring(0, colon);
    String pass = auth.substring(colon + 1);
    int slash = hostpart.indexOf('/');
    String hostPort = hostpart.substring(0, slash);
    String service = hostpart.substring(slash + 1);
    return "jdbc:oracle:thin:" + user + "/" + pass + "@" + hostPort + "/" + service;
  }

  private static void resetTable(String jdbc) throws SQLException {
    try (Connection conn = DriverManager.getConnection(jdbc);
        Statement st = conn.createStatement()) {
      try {
        st.execute("DROP TABLE UBER_PICKUPS PURGE");
      } catch (SQLException ignored) {
        // table may not exist
      }
      st.execute(
          """
          CREATE TABLE UBER_PICKUPS (
            pickup_time VARCHAR2(64),
            lat NUMBER,
            lon NUMBER,
            base_code VARCHAR2(32)
          )
          """);
      conn.commit();
    }
  }

  private static void loadDataset(String jdbc, JSONObject dataset, int maxRows)
      throws SQLException {
    JSONArray rows = dataset.getJSONArray("rows");
    JSONArray fields = dataset.getJSONObject("schema").getJSONArray("fields");
    int[] colIdx = new int[4];
    for (int i = 0; i < fields.length(); i++) {
      String name = fields.getJSONObject(i).getString("name");
      switch (name) {
        case "Date/Time" -> colIdx[0] = i;
        case "Lat" -> colIdx[1] = i;
        case "Lon" -> colIdx[2] = i;
        case "Base" -> colIdx[3] = i;
        default -> {}
      }
    }

    try (Connection conn = DriverManager.getConnection(jdbc);
        PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO UBER_PICKUPS (pickup_time, lat, lon, base_code)"
                    + " VALUES (?, ?, ?, ?)")) {
      int limit = Math.min(rows.length(), maxRows);
      for (int r = 0; r < limit; r++) {
        JSONArray row = rows.getJSONArray(r);
        ps.setString(1, jsonCell(row, colIdx[0]));
        ps.setDouble(2, Double.parseDouble(jsonCell(row, colIdx[1])));
        ps.setDouble(3, Double.parseDouble(jsonCell(row, colIdx[2])));
        ps.setString(4, jsonCell(row, colIdx[3]));
        ps.addBatch();
      }
      ps.executeBatch();
      conn.commit();
    }
  }

  private static String jsonCell(JSONArray row, int idx) {
    Object cell = row.get(idx);
    if (cell instanceof JSONObject obj) {
      if (obj.has("Utf8")) {
        return obj.getString("Utf8");
      }
      if (obj.has("Float64")) {
        return Double.toString(obj.getDouble("Float64"));
      }
      if (obj.has("Int64")) {
        return Long.toString(obj.getLong("Int64"));
      }
    }
    return cell.toString();
  }

  private static int countRows(String jdbc) throws SQLException {
    try (Connection conn = DriverManager.getConnection(jdbc);
        Statement st = conn.createStatement();
        var rs = st.executeQuery("SELECT COUNT(*) FROM UBER_PICKUPS")) {
      rs.next();
      return rs.getInt(1);
    }
  }
}
