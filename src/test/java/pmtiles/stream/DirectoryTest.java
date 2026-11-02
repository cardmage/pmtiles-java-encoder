package pmtiles.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DirectoryTest {

  @Test
  void encodesExactBytes() {
    Directory d = new Directory(4);
    d.add(0, 0, 10, 1); // first offset written as offset + 1
    d.add(1, 10, 5, 1); // contiguous -> 0
    d.add(5, 0, 10, 300); // points back (dedup) -> offset + 1, run 300 = varint 0xAC 0x02
    byte[] expected = {
      3, // entries
      0, 1, 4, // tile id deltas
      1, 1, (byte) 0xAC, 0x02, // run lengths
      10, 5, 10, // lengths
      1, 0, 1 // offsets
    };
    assertArrayEquals(expected, d.encode());
  }

  @Test
  void roundTripsRandomDirectories() {
    Random r = new Random(7);
    for (int iter = 0; iter < 200; iter++) {
      int n = 1 + r.nextInt(5000);
      Directory d = new Directory(16);
      long id = r.nextInt(100);
      long nextOffset = 0;
      for (int i = 0; i < n; i++) {
        int len = 1 + r.nextInt(100_000);
        int run = r.nextInt(10) == 0 ? 0 : 1 + r.nextInt(r.nextBoolean() ? 3 : 1_000_000);
        long off = r.nextInt(4) == 0 ? (long) r.nextInt(1 << 30) * 1024 : nextOffset;
        d.add(id, off, len, run);
        nextOffset = off + len;
        id += 1 + (r.nextBoolean() ? 0 : r.nextInt(1 << 20));
      }
      List<TestReader.Entry> back = TestReader.parseDirectory(d.encode());
      assertEquals(d.size, back.size());
      for (int i = 0; i < n; i++) {
        TestReader.Entry e = back.get(i);
        assertEquals(d.tileIds[i], e.tileId());
        assertEquals(d.offsets[i], e.offset());
        assertEquals(d.lengths[i], e.length());
        assertEquals(d.runLengths[i], e.runLength());
      }
    }
  }

  @Test
  void chunkSortHandlesAdversarialInputs() {
    Random r = new Random(1);
    int[] sizes = {0, 1, 2, 25, 1000, 100_000};
    for (int size : sizes) {
      for (int pattern = 0; pattern < 5; pattern++) {
        Directory d = new Directory(16);
        for (int i = 0; i < size; i++) {
          long key =
              switch (pattern) {
                case 0 -> i; // sorted
                case 1 -> size - i; // reversed
                case 2 -> r.nextInt(10); // many duplicates
                case 3 -> (i % 2 == 0) ? i : size - i; // organ pipe-ish
                default -> r.nextLong() & Long.MAX_VALUE;
              };
          d.add(key, key * 3, (int) (key & 0xFFFF) + 1, i);
        }
        EntryStore.sortChunk(d, 0, d.size);
        for (int i = 1; i < d.size; i++) {
          if (d.tileIds[i - 1] > d.tileIds[i]) {
            throw new AssertionError("not sorted at " + i + " pattern " + pattern);
          }
        }
        for (int i = 0; i < d.size; i++) { // payload columns moved with their keys
          assertEquals(d.tileIds[i] * 3, d.offsets[i]);
          assertEquals((int) (d.tileIds[i] & 0xFFFF) + 1, d.lengths[i]);
        }
      }
    }
  }
}
