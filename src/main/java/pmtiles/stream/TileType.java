package pmtiles.stream;

/** Tile type enum as defined by the PMTiles v3 spec (header field TT). */
public enum TileType {
  UNKNOWN(0x00),
  /** Mapbox Vector Tile. */
  MVT(0x01),
  PNG(0x02),
  JPEG(0x03),
  WEBP(0x04),
  AVIF(0x05),
  /** MapLibre Vector Tile. */
  MLT(0x06);

  private final byte value;

  TileType(int value) {
    this.value = (byte) value;
  }

  /** The byte stored in the PMTiles header. */
  public byte value() {
    return value;
  }
}
