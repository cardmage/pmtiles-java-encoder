package pmtiles.stream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal PMTiles v3 reader used only to verify writer output. Written directly against the spec
 * and deliberately independent of the writer's own Directory class.
 */
final class TestReader {

  record Entry(long tileId, long offset, int length, int runLength) {}

  final byte[] file;
  final long rootOffset;
  final long rootLength;
  final long metadataOffset;
  final long metadataLength;
  final long leafOffset;
  final long leafLength;
  final long dataOffset;
  final long dataLength;
  final long addressedTiles;
  final long tileEntries;
  final long tileContents;
  final boolean clustered;
  final int internalCompression;
  final int tileCompression;
  final int tileType;
  final int minZoom;
  final int maxZoom;
  final double minLon;
  final double minLat;
  final double maxLon;
  final double maxLat;
  final int centerZoom;
  final double centerLon;
  final double centerLat;

  TestReader(Path path) throws IOException {
    this(Files.readAllBytes(path));
  }

  TestReader(byte[] file) {
    this.file = file;
    if (file.length < 127) {
      throw new IllegalArgumentException("file shorter than header");
    }
    String magic = new String(file, 0, 7, StandardCharsets.US_ASCII);
    if (!magic.equals("PMTiles") || file[7] != 3) {
      throw new IllegalArgumentException("not a PMTiles v3 file");
    }
    ByteBuffer b = ByteBuffer.wrap(file, 0, 127).order(ByteOrder.LITTLE_ENDIAN);
    b.position(8);
    rootOffset = b.getLong();
    rootLength = b.getLong();
    metadataOffset = b.getLong();
    metadataLength = b.getLong();
    leafOffset = b.getLong();
    leafLength = b.getLong();
    dataOffset = b.getLong();
    dataLength = b.getLong();
    addressedTiles = b.getLong();
    tileEntries = b.getLong();
    tileContents = b.getLong();
    clustered = b.get() == 1;
    internalCompression = b.get();
    tileCompression = b.get();
    tileType = b.get();
    minZoom = b.get() & 0xFF;
    maxZoom = b.get() & 0xFF;
    minLon = b.getInt() / 1e7;
    minLat = b.getInt() / 1e7;
    maxLon = b.getInt() / 1e7;
    maxLat = b.getInt() / 1e7;
    centerZoom = b.get() & 0xFF;
    centerLon = b.getInt() / 1e7;
    centerLat = b.getInt() / 1e7;
    if (rootOffset + rootLength > 16384) {
      throw new IllegalArgumentException("root directory not within first 16 KiB");
    }
    checkRange(rootOffset, rootLength);
    checkRange(metadataOffset, metadataLength);
    checkRange(leafOffset, leafLength);
    checkRange(dataOffset, dataLength);
  }

  private void checkRange(long off, long len) {
    if (off < 0 || len < 0 || off + len > file.length) {
      throw new IllegalArgumentException("section out of bounds: " + off + "+" + len);
    }
  }

  String metadata() throws IOException {
    return new String(
        decompress(slice(metadataOffset, (int) metadataLength)), StandardCharsets.UTF_8);
  }

  byte[] getTile(int z, int x, int y) throws IOException {
    return getTile(TileId.toTileId(z, x, y));
  }

  byte[] getTile(long tileId) throws IOException {
    long dirOffset = rootOffset;
    long dirLength = rootLength;
    for (int depth = 0; depth <= 3; depth++) {
      List<Entry> entries = readDirectory(dirOffset, (int) dirLength);
      Entry found = null;
      int lo = 0;
      int hi = entries.size() - 1;
      while (lo <= hi) { // last entry with tileId <= target
        int mid = (lo + hi) >>> 1;
        if (entries.get(mid).tileId <= tileId) {
          found = entries.get(mid);
          lo = mid + 1;
        } else {
          hi = mid - 1;
        }
      }
      if (found == null) {
        return null;
      }
      if (found.runLength > 0) {
        if (tileId >= found.tileId + found.runLength) {
          return null;
        }
        return slice(dataOffset + found.offset, found.length);
      }
      dirOffset = leafOffset + found.offset;
      dirLength = found.length;
    }
    throw new IllegalStateException("directory nesting too deep");
  }

  /** All tile entries in archive order (root, then each leaf in turn). */
  List<Entry> allTileEntries() throws IOException {
    List<Entry> out = new ArrayList<>();
    collect(rootOffset, (int) rootLength, false, out);
    return out;
  }

  int leafDirectoryCount() throws IOException {
    int n = 0;
    for (Entry e : readDirectory(rootOffset, (int) rootLength)) {
      if (e.runLength == 0) {
        n++;
      }
    }
    return n;
  }

  private void collect(long off, int len, boolean isLeaf, List<Entry> out) throws IOException {
    for (Entry e : readDirectory(off, len)) {
      if (e.runLength == 0) {
        if (isLeaf) {
          throw new IllegalStateException("more than one level of leaves");
        }
        collect(leafOffset + e.offset, e.length, true, out);
      } else {
        out.add(e);
      }
    }
  }

  private final Map<Long, List<Entry>> directoryCache = new HashMap<>();

  List<Entry> readDirectory(long off, int len) throws IOException {
    List<Entry> cached = directoryCache.get(off);
    if (cached == null) {
      cached = parseDirectory(off, len);
      directoryCache.put(off, cached);
    }
    return cached;
  }

  private List<Entry> parseDirectory(long off, int len) throws IOException {
    return parseDirectory(decompress(slice(off, len)));
  }

  /** Parses an uncompressed directory, validating the spec's structural rules. */
  static List<Entry> parseDirectory(byte[] raw) {
    int[] p = {0};
    int n = (int) varint(raw, p);
    if (n <= 0) {
      throw new IllegalStateException("directory with no entries");
    }
    long[] ids = new long[n];
    long[] runs = new long[n];
    long[] lens = new long[n];
    long[] offs = new long[n];
    long last = 0;
    for (int i = 0; i < n; i++) {
      last += varint(raw, p);
      ids[i] = last;
    }
    for (int i = 0; i < n; i++) {
      runs[i] = varint(raw, p);
    }
    for (int i = 0; i < n; i++) {
      lens[i] = varint(raw, p);
      if (lens[i] <= 0) {
        throw new IllegalStateException("entry length must be > 0");
      }
    }
    for (int i = 0; i < n; i++) {
      long v = varint(raw, p);
      offs[i] = (v == 0 && i > 0) ? offs[i - 1] + lens[i - 1] : v - 1;
    }
    if (p[0] != raw.length) {
      throw new IllegalStateException("trailing bytes in directory");
    }
    List<Entry> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      if (i > 0 && ids[i] <= ids[i - 1]) {
        throw new IllegalStateException("directory not strictly ascending");
      }
      out.add(new Entry(ids[i], offs[i], (int) lens[i], (int) runs[i]));
    }
    return out;
  }

  private static long varint(byte[] b, int[] p) {
    long r = 0;
    for (int shift = 0; ; shift += 7) {
      int x = b[p[0]++] & 0xFF;
      r |= (long) (x & 0x7F) << shift;
      if (x < 0x80) {
        return r;
      }
    }
  }

  private byte[] slice(long off, int len) {
    byte[] out = new byte[len];
    System.arraycopy(file, (int) off, out, 0, len);
    return out;
  }

  private byte[] decompress(byte[] data) throws IOException {
    if (internalCompression == 1) {
      return data;
    }
    if (internalCompression == 2) {
      try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
        return in.readAllBytes();
      }
    }
    throw new IllegalStateException("unsupported internal compression " + internalCompression);
  }
}
