package pmtiles.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Compressor used for the archive's <em>internal</em> data (directories and JSON metadata).
 *
 * <p>Tile payloads are never compressed by the writer: pass tile bytes exactly as they should be
 * served, and declare their encoding with {@link PmtilesWriter.Builder#tileCompression}.
 *
 * <p>{@link Compression#NONE} and {@link Compression#GZIP} are built in. To use zstd or brotli for
 * internal compression, register a codec backed by a library of your choice, e.g.
 *
 * <pre>{@code
 * builder.internalCompression(Compression.ZSTD, Zstd::compress);   // com.github.luben:zstd-jni
 * }</pre>
 */
@FunctionalInterface
public interface Codec {

  byte[] compress(byte[] input) throws IOException;

  /** Identity codec. */
  static Codec none() {
    return input -> input;
  }

  /** gzip at maximum compression; keeps the root directory as small as possible. */
  static Codec gzip() {
    return input -> {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, input.length / 2));
      try (GZIPOutputStream gz =
          new GZIPOutputStream(bytes) {
            {
              def.setLevel(Deflater.BEST_COMPRESSION);
            }
          }) {
        gz.write(input);
      }
      return bytes.toByteArray();
    };
  }
}
