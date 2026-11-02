package pmtiles.stream;

/** Compression enum as defined by the PMTiles v3 spec (header fields IC and TC). */
public enum Compression {
  UNKNOWN(0x00),
  NONE(0x01),
  GZIP(0x02),
  BROTLI(0x03),
  ZSTD(0x04);

  private final byte value;

  Compression(int value) {
    this.value = (byte) value;
  }

  /** The byte stored in the PMTiles header. */
  public byte value() {
    return value;
  }
}
