package pmtiles.stream;

import java.util.Arrays;

/**
 * Columnar directory entries plus the PMTiles v3 directory serializer.
 *
 * <p>Wire format (before internal compression): varint entry count, then delta-encoded tile IDs,
 * run lengths, lengths, and offsets, each column as unsigned LEB128 varints. An offset is written
 * as 0 when it equals the previous entry's offset + length, otherwise as offset + 1.
 */
final class Directory {

  long[] tileIds;
  long[] offsets;
  int[] lengths;
  int[] runLengths;
  int size;
  private final int maxCapacity;

  Directory(int initialCapacity) {
    this(initialCapacity, Integer.MAX_VALUE - 8);
  }

  /** Arrays grow by doubling but never beyond {@code maxCapacity}, so a full chunk has no slack. */
  Directory(int initialCapacity, int maxCapacity) {
    this.maxCapacity = Math.max(16, maxCapacity);
    int capacity = Math.min(Math.max(initialCapacity, 16), this.maxCapacity);
    tileIds = new long[capacity];
    offsets = new long[capacity];
    lengths = new int[capacity];
    runLengths = new int[capacity];
  }

  void clear() {
    size = 0;
  }

  void add(long tileId, long offset, int length, int runLength) {
    if (size == tileIds.length) {
      if (size >= maxCapacity) {
        throw new IllegalStateException("directory buffer full: " + size);
      }
      int cap = (int) Math.min(maxCapacity, Math.max(16, size * 2L));
      tileIds = Arrays.copyOf(tileIds, cap);
      offsets = Arrays.copyOf(offsets, cap);
      lengths = Arrays.copyOf(lengths, cap);
      runLengths = Arrays.copyOf(runLengths, cap);
    }
    tileIds[size] = tileId;
    offsets[size] = offset;
    lengths[size] = length;
    runLengths[size] = runLength;
    size++;
  }

  /** Serializes all entries as one directory (uncompressed). */
  byte[] encode() {
    if (size == 0) {
      throw new IllegalStateException("a directory must contain at least one entry");
    }
    ByteSink out = new ByteSink(size * 6 + 16);
    out.varint(size);
    long last = 0;
    for (int i = 0; i < size; i++) {
      out.varint(tileIds[i] - last);
      last = tileIds[i];
    }
    for (int i = 0; i < size; i++) {
      out.varint(runLengths[i]);
    }
    for (int i = 0; i < size; i++) {
      out.varint(lengths[i]);
    }
    for (int i = 0; i < size; i++) {
      boolean contiguous = i > 0 && offsets[i] == offsets[i - 1] + lengths[i - 1];
      out.varint(contiguous ? 0 : offsets[i] + 1);
    }
    return out.toByteArray();
  }

  /** Minimal growable byte buffer. */
  private static final class ByteSink {
    private byte[] buf;
    private int len;

    ByteSink(int capacity) {
      buf = new byte[Math.max(16, capacity)];
    }

    void varint(long v) {
      ensure(10);
      while ((v & ~0x7FL) != 0) {
        buf[len++] = (byte) ((v & 0x7F) | 0x80);
        v >>>= 7;
      }
      buf[len++] = (byte) v;
    }

    private void ensure(int extra) {
      if (len + extra > buf.length) {
        buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + extra));
      }
    }

    byte[] toByteArray() {
      return Arrays.copyOf(buf, len);
    }
  }
}
