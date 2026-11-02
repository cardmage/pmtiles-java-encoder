package pmtiles.stream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** The fixed 127-byte PMTiles v3 header. */
final class Header {

  static final int LENGTH = 127;
  /** Header + compressed root directory must fit in the first 16 KiB. */
  static final int ROOT_REGION = 16384;

  static final int MAX_ROOT_DIRECTORY_LENGTH = ROOT_REGION - LENGTH;

  long rootOffset;
  long rootLength;
  long metadataOffset;
  long metadataLength;
  long leafDirectoriesOffset;
  long leafDirectoriesLength;
  long tileDataOffset;
  long tileDataLength;
  long addressedTiles;
  long tileEntries;
  long tileContents;
  boolean clustered;
  Compression internalCompression = Compression.GZIP;
  Compression tileCompression = Compression.UNKNOWN;
  TileType tileType = TileType.UNKNOWN;
  int minZoom;
  int maxZoom;
  double minLon;
  double minLat;
  double maxLon;
  double maxLat;
  int centerZoom;
  double centerLon;
  double centerLat;

  byte[] toBytes() {
    ByteBuffer b = ByteBuffer.allocate(LENGTH).order(ByteOrder.LITTLE_ENDIAN);
    b.put("PMTiles".getBytes(StandardCharsets.US_ASCII));
    b.put((byte) 3);
    b.putLong(rootOffset);
    b.putLong(rootLength);
    b.putLong(metadataOffset);
    b.putLong(metadataLength);
    b.putLong(leafDirectoriesOffset);
    b.putLong(leafDirectoriesLength);
    b.putLong(tileDataOffset);
    b.putLong(tileDataLength);
    b.putLong(addressedTiles);
    b.putLong(tileEntries);
    b.putLong(tileContents);
    b.put((byte) (clustered ? 1 : 0));
    b.put(internalCompression.value());
    b.put(tileCompression.value());
    b.put(tileType.value());
    b.put((byte) minZoom);
    b.put((byte) maxZoom);
    b.putInt(e7(minLon));
    b.putInt(e7(minLat));
    b.putInt(e7(maxLon));
    b.putInt(e7(maxLat));
    b.put((byte) centerZoom);
    b.putInt(e7(centerLon));
    b.putInt(e7(centerLat));
    if (b.position() != LENGTH) {
      throw new AssertionError("header length " + b.position());
    }
    return b.array();
  }

  private static int e7(double degrees) {
    return (int) Math.round(degrees * 10_000_000d);
  }
}
