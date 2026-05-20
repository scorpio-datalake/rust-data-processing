package io.github.scorpio_datalake.rust_data_processing.spark;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.Text;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/**
 * Loads an on-disk Arrow IPC <strong>file</strong> (Polars {@code IpcWriter} / Feather-style
 * footer) into a Spark {@code Dataset<Row>} without Spark's Parquet reader. Intended for {@code
 * arrow_ipc_export_temp} envelopes only; wide Arrow types may require extending {@link
 * #toSparkType}.
 */
final class ArrowIpcFileToSpark {

  private ArrowIpcFileToSpark() {}

  static Dataset<Row> load(Path path, SparkSession spark) throws IOException {
    List<Row> rows = new ArrayList<>();
    try (BufferAllocator allocator = new RootAllocator();
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
        ArrowFileReader reader = new ArrowFileReader(channel, allocator)) {
      StructType structType = toSparkSchema(reader.getVectorSchemaRoot().getSchema());
      while (reader.loadNextBatch()) {
        VectorSchemaRoot root = reader.getVectorSchemaRoot();
        int n = root.getSchema().getFields().size();
        long nRows = root.getRowCount();
        for (long r = 0; r < nRows; r++) {
          Object[] vals = new Object[n];
          for (int c = 0; c < n; c++) {
            FieldVector v = root.getVector(c);
            vals[c] = sparkCell(v.getObject((int) r));
          }
          rows.add(RowFactory.create(vals));
        }
      }
      return spark.createDataFrame(rows, structType);
    }
  }

  private static Object sparkCell(Object o) {
    if (o instanceof Text t) {
      return t.toString();
    }
    return o;
  }

  private static StructType toSparkSchema(Schema arrowSchema) {
    int n = arrowSchema.getFields().size();
    StructField[] fields = new StructField[n];
    for (int i = 0; i < n; i++) {
      var f = arrowSchema.getFields().get(i);
      DataType dt = toSparkType(f.getType());
      fields[i] = new StructField(f.getName(), dt, true, Metadata.empty());
    }
    return new StructType(fields);
  }

  private static DataType toSparkType(ArrowType type) {
    if (type instanceof ArrowType.Utf8 || type instanceof ArrowType.LargeUtf8) {
      return DataTypes.StringType;
    }
    if (type instanceof ArrowType.Bool) {
      return DataTypes.BooleanType;
    }
    if (type instanceof ArrowType.Int it) {
      if (it.getIsSigned() && it.getBitWidth() == 64) {
        return DataTypes.LongType;
      }
      if (it.getIsSigned() && it.getBitWidth() == 32) {
        return DataTypes.IntegerType;
      }
    }
    if (type instanceof ArrowType.FloatingPoint fp) {
      if (fp.getPrecision() == FloatingPointPrecision.DOUBLE) {
        return DataTypes.DoubleType;
      }
      if (fp.getPrecision() == FloatingPointPrecision.SINGLE) {
        return DataTypes.FloatType;
      }
    }
    throw new IllegalArgumentException("Unsupported Arrow type for Spark bridge: " + type);
  }
}
