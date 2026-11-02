package pmtiles.stream;

/** A z/x/y tile address (XYZ scheme, y=0 at the north). x and y fit in an int up to zoom 31. */
public record TileCoord(int z, int x, int y) {

  public TileCoord {
    if (z < 0 || z > TileId.MAX_ZOOM) {
      throw new IllegalArgumentException("zoom out of range [0," + TileId.MAX_ZOOM + "]: " + z);
    }
    long n = 1L << z;
    if (x < 0 || x >= n || y < 0 || y >= n) {
      throw new IllegalArgumentException("tile out of range for zoom " + z + ": " + x + "/" + y);
    }
  }

  public long tileId() {
    return TileId.toTileId(z, x, y);
  }

  @Override
  public String toString() {
    return z + "/" + x + "/" + y;
  }
}
