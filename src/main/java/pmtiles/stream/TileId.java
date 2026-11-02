package pmtiles.stream;

/**
 * PMTiles tile IDs: positions on a cumulative sequence of Hilbert curves, one per zoom level.
 *
 * <p>Writing tiles in ascending tile ID order produces a <em>clustered</em> archive directly,
 * without any reordering at the end. Use {@link #toTileId} to sort your work queue, or iterate
 * {@code for id in [zoomStart(z), zoomStart(z+1))} and convert with {@link #toCoord}.
 */
public final class TileId {

  /** Highest zoom whose tile IDs fit in a signed 64-bit long. */
  public static final int MAX_ZOOM = 31;

  /** ZOOM_START[z] = number of tiles in all zoom levels below z = (4^z - 1) / 3. */
  private static final long[] ZOOM_START = new long[MAX_ZOOM + 2];

  static {
    long acc = 0;
    for (int z = 0; z <= MAX_ZOOM + 1; z++) {
      ZOOM_START[z] = acc;
      if (z <= MAX_ZOOM) {
        acc += 1L << (2 * z);
      }
    }
  }

  private TileId() {}

  /** First tile ID of zoom level {@code z}. {@code zoomStart(z + 1)} is the exclusive end. */
  public static long zoomStart(int z) {
    if (z < 0 || z > MAX_ZOOM + 1) {
      throw new IllegalArgumentException("zoom out of range: " + z);
    }
    return ZOOM_START[z];
  }

  /** Converts z/x/y (XYZ scheme) to a PMTiles tile ID. */
  public static long toTileId(int z, long x, long y) {
    if (z < 0 || z > MAX_ZOOM) {
      throw new IllegalArgumentException("zoom out of range [0," + MAX_ZOOM + "]: " + z);
    }
    long n = 1L << z;
    if (x < 0 || x >= n || y < 0 || y >= n) {
      throw new IllegalArgumentException("tile out of range for zoom " + z + ": " + x + "/" + y);
    }
    // Hilbert xy -> d (Wikipedia "xy2d")
    long d = 0;
    for (long s = n >>> 1; s > 0; s >>>= 1) {
      long rx = (x & s) != 0 ? 1 : 0;
      long ry = (y & s) != 0 ? 1 : 0;
      d += s * s * ((3 * rx) ^ ry);
      if (ry == 0) {
        if (rx == 1) {
          x = n - 1 - x;
          y = n - 1 - y;
        }
        long t = x;
        x = y;
        y = t;
      }
    }
    return ZOOM_START[z] + d;
  }

  /** Converts a PMTiles tile ID back to z/x/y. */
  public static TileCoord toCoord(long tileId) {
    if (tileId < 0 || tileId >= ZOOM_START[MAX_ZOOM + 1]) {
      throw new IllegalArgumentException("tile id out of range: " + tileId);
    }
    int z = 0;
    while (ZOOM_START[z + 1] <= tileId) {
      z++;
    }
    long n = 1L << z;
    long t = tileId - ZOOM_START[z];
    long x = 0;
    long y = 0;
    // Hilbert d -> xy (Wikipedia "d2xy")
    for (long s = 1; s < n; s <<= 1) {
      long rx = 1 & (t >>> 1);
      long ry = 1 & (t ^ rx);
      if (ry == 0) {
        if (rx == 1) {
          x = s - 1 - x;
          y = s - 1 - y;
        }
        long tmp = x;
        x = y;
        y = tmp;
      }
      x += s * rx;
      y += s * ry;
      t >>>= 2;
    }
    return new TileCoord(z, (int) x, (int) y);
  }
}
