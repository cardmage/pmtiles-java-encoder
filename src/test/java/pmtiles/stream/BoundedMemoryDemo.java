package pmtiles.stream;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes every tile from z0 to {@code maxZoom} under a small heap to show memory does not grow
 * with tile count. Run with e.g. {@code java -Xmx64m ... BoundedMemoryDemo out.pmtiles 12 rowmajor
 * file}.
 *
 * <p>args: output maxZoom (hilbert|rowmajor) (file|stream)
 */
public final class BoundedMemoryDemo {

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]);
    int maxZoom = Integer.parseInt(args[1]);
    boolean rowMajor = args[2].equals("rowmajor");
    boolean stream = args[3].equals("stream");

    long start = System.nanoTime();
    PmtilesWriter.Builder b =
        PmtilesWriter.builder()
            .tileType(TileType.MVT)
            .tileCompression(Compression.NONE)
            .metadata("{\"name\":\"demo\",\"vector_layers\":[{\"id\":\"demo\",\"fields\":{}}]}");
    WriteResult res;
    try (OutputStream os = stream ? new BufferedOutputStream(Files.newOutputStream(out)) : null;
        PmtilesWriter w = stream ? b.open(os) : b.open(out)) {
      if (rowMajor) {
        for (int z = 0; z <= maxZoom; z++) {
          for (int y = 0; y < (1 << z); y++) {
            for (int x = 0; x < (1 << z); x++) {
              w.write(z, x, y, payload(z, x, y));
            }
          }
        }
      } else {
        long end = TileId.zoomStart(maxZoom + 1);
        for (long id = 0; id < end; id++) {
          TileCoord c = TileId.toCoord(id);
          w.writeTileId(id, payload(c.z(), c.x(), c.y()));
        }
      }
      res = w.finish();
    }
    double secs = (System.nanoTime() - start) / 1e9;
    System.out.printf(
        "%s z0-%d %s %s: %,d tiles in %.1fs (%,.0f tiles/s), %,d bytes, leaves=%d,"
            + " clustered=%s, max heap %.0f MiB%n",
        out.getFileName(),
        maxZoom,
        rowMajor ? "row-major" : "hilbert",
        stream ? "stream" : "file",
        res.addressedTiles(),
        secs,
        res.addressedTiles() / secs,
        res.totalBytes(),
        res.leafDirectories(),
        res.clustered(),
        Runtime.getRuntime().maxMemory() / 1048576d);
  }

  private static byte[] payload(int z, int x, int y) {
    return (z + "/" + x + "/" + y).getBytes(StandardCharsets.US_ASCII);
  }
}
