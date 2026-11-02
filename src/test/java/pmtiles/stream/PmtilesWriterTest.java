package pmtiles.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PmtilesWriterTest {

  // ---------------------------------------------------------------------------------------------
  // helpers

  private static Path tempDir() throws IOException {
    return Files.createTempDirectory("pmtiles-test-");
  }

  private static void deleteTree(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> s = Files.walk(dir)) {
      for (Path p : s.sorted(Collections.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  private static byte[] content(long tileId) {
    TileCoord c = TileId.toCoord(tileId);
    return (c.z() + "/" + c.x() + "/" + c.y()).getBytes(StandardCharsets.US_ASCII);
  }

  private static List<Long> idsUpTo(int maxZoom) {
    List<Long> ids = new ArrayList<>();
    for (long id = 0; id < TileId.zoomStart(maxZoom + 1); id++) {
      ids.add(id);
    }
    return ids;
  }

  /** Row-major z, y, x order: what a naive generator produces; NOT tile ID order. */
  private static List<Long> rowMajor(int maxZoom) {
    List<Long> ids = new ArrayList<>();
    for (int z = 0; z <= maxZoom; z++) {
      for (int y = 0; y < (1 << z); y++) {
        for (int x = 0; x < (1 << z); x++) {
          ids.add(TileId.toTileId(z, x, y));
        }
      }
    }
    return ids;
  }

  private static void assertAllTiles(TestReader r, List<Long> ids) throws IOException {
    for (long id : ids) {
      assertArrayEquals(content(id), r.getTile(id), "tile " + TileId.toCoord(id));
    }
  }

  /** Clustered: offsets either continue the data written so far or point back (dedup). */
  private static void assertClusteredLayout(TestReader r) throws IOException {
    long end = 0;
    boolean first = true;
    for (TestReader.Entry e : r.allTileEntries()) {
      if (first) {
        assertEquals(0, e.offset(), "first entry offset");
        first = false;
      }
      if (e.offset() == end) {
        end += e.length();
      } else {
        assertTrue(e.offset() + e.length() <= end, "offset neither contiguous nor backwards");
      }
    }
    assertEquals(r.dataLength, end, "tile data fully referenced");
  }

  private static void assertNoTempFilesLeft(Path dir, Path... expected) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      List<Path> left = s.filter(p -> !List.of(expected).contains(p)).toList();
      assertTrue(left.isEmpty(), "leftover files: " + left);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // tests

  @Test
  void orderedInputToFileIsClusteredWithRootOnly() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      List<Long> ids = idsUpTo(6);
      WriteResult res;
      try (PmtilesWriter w =
          PmtilesWriter.builder()
              .tileType(TileType.MVT)
              .tileCompression(Compression.NONE)
              .metadata("{\"name\":\"test\",\"vector_layers\":[]}")
              .open(out)) {
        for (long id : ids) {
          w.writeTileId(id, content(id));
        }
        res = w.finish();
      }
      TestReader r = new TestReader(out);
      assertEquals(ids.size(), r.addressedTiles);
      assertEquals(ids.size(), r.tileEntries);
      assertEquals(ids.size(), r.tileContents);
      assertTrue(r.clustered);
      assertEquals(0, r.leafDirectoryCount());
      assertEquals(0, r.minZoom);
      assertEquals(6, r.maxZoom);
      assertEquals(TileType.MVT.value(), r.tileType);
      assertEquals(Compression.NONE.value(), r.tileCompression);
      assertEquals(Compression.GZIP.value(), r.internalCompression);
      assertEquals(-180, r.minLon, 1e-6);
      assertEquals(180, r.maxLon, 1e-6);
      assertEquals(85.0511287, r.maxLat, 1e-6);
      assertEquals(-85.0511287, r.minLat, 1e-6);
      assertEquals(16384, r.dataOffset);
      assertEquals("{\"name\":\"test\",\"vector_layers\":[]}", r.metadata());
      assertTrue(r.metadataOffset < 16384, "small metadata stored in reserved region");
      assertAllTiles(r, ids);
      assertClusteredLayout(r);
      assertNull(r.getTile(7, 0, 0));
      assertEquals(Files.size(out), res.totalBytes());
      assertTrue(res.clustered());
      assertNoTempFilesLeft(dir, out);
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void rowMajorInputToFileIsValidButNotClustered() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      List<Long> ids = rowMajor(7);
      try (PmtilesWriter w = PmtilesWriter.builder().sortChunkEntries(1000).open(out)) {
        for (long id : ids) {
          TileCoord c = TileId.toCoord(id);
          w.write(c.z(), c.x(), c.y(), content(id));
        }
        WriteResult res = w.finish();
        assertFalse(res.clustered());
      }
      TestReader r = new TestReader(out);
      assertFalse(r.clustered);
      assertEquals(ids.size(), r.addressedTiles);
      assertEquals(ids.size(), r.allTileEntries().size());
      assertAllTiles(r, ids);
      assertNoTempFilesLeft(dir, out);
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void rowMajorInputToStreamIsReclustered() throws IOException {
    Path dir = tempDir();
    try {
      List<Long> ids = rowMajor(7);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (PmtilesWriter w =
          PmtilesWriter.builder().tempDirectory(dir).sortChunkEntries(1000).open(bytes)) {
        for (long id : ids) {
          w.writeTileId(id, content(id));
        }
        WriteResult res = w.finish();
        assertTrue(res.clustered());
        assertEquals(bytes.size(), res.totalBytes());
      }
      TestReader r = new TestReader(bytes.toByteArray());
      assertTrue(r.clustered);
      assertEquals(127, r.rootOffset);
      assertEquals(r.rootOffset + r.rootLength, r.metadataOffset);
      assertEquals(r.metadataOffset + r.metadataLength, r.leafOffset);
      assertEquals(r.leafOffset + r.leafLength, r.dataOffset);
      assertEquals(bytes.size(), r.dataOffset + r.dataLength);
      assertAllTiles(r, ids);
      assertClusteredLayout(r);
      assertNoTempFilesLeft(dir);
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void deduplicatesAndRunLengthEncodesRepeatedTiles() throws IOException {
    for (boolean stream : new boolean[] {false, true}) {
      for (boolean shuffle : new boolean[] {false, true}) {
        Path dir = tempDir();
        try {
          byte[] ocean = "ocean".getBytes(StandardCharsets.US_ASCII);
          long start = TileId.zoomStart(8);
          List<Long> ids = new ArrayList<>();
          for (long id = start; id < start + 20_000; id++) {
            ids.add(id);
          }
          if (shuffle) {
            Collections.shuffle(ids, new Random(3));
          }
          Path out = dir.resolve("out.pmtiles");
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          PmtilesWriter.Builder b = PmtilesWriter.builder().tempDirectory(dir);
          WriteResult res;
          try (PmtilesWriter w = stream ? b.open(bytes) : b.open(out)) {
            for (long id : ids) {
              w.writeTileId(id, (id - start) % 100 == 0 ? content(id) : ocean);
            }
            res = w.finish();
          }
          TestReader r = stream ? new TestReader(bytes.toByteArray()) : new TestReader(out);
          String label = "stream=" + stream + " shuffle=" + shuffle;
          assertEquals(20_000, r.addressedTiles, label);
          assertEquals(201, r.tileContents, label); // 200 unique + 1 ocean
          assertEquals(400, r.tileEntries, label); // 200 unique + 200 ocean runs after each
          assertEquals(400, r.allTileEntries().size(), label);
          assertEquals(!shuffle || stream, r.clustered, label);
          assertEquals(res.tileEntries(), r.tileEntries);
          for (long id : ids) {
            byte[] expected = (id - start) % 100 == 0 ? content(id) : ocean;
            assertArrayEquals(expected, r.getTile(id), label + " " + TileId.toCoord(id));
          }
          if (r.clustered) {
            assertClusteredLayout(r);
          }
        } finally {
          deleteTree(dir);
        }
      }
    }
  }

  @Test
  void largeShuffledArchiveUsesLeavesAndMultiLevelMerge() throws IOException {
    Path dir = tempDir();
    try {
      List<Long> ids = idsUpTo(9); // 349,525 tiles
      Collections.shuffle(ids, new Random(11));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      WriteResult res;
      // tiny chunks -> ~350 sorted runs -> exceeds merge fan-in and forces intermediate merges
      try (PmtilesWriter w =
          PmtilesWriter.builder()
              .tempDirectory(dir)
              .sortChunkEntries(1000)
              .dedupCacheEntries(0)
              .open(bytes)) {
        for (long id : ids) {
          w.writeTileId(id, content(id));
        }
        res = w.finish();
      }
      TestReader r = new TestReader(bytes.toByteArray());
      assertTrue(res.leafDirectories() > 0);
      assertEquals(res.leafDirectories(), r.leafDirectoryCount());
      assertTrue(r.rootLength <= 16384 - 127);
      assertEquals(ids.size(), r.addressedTiles);
      assertEquals(ids.size(), r.allTileEntries().size());
      assertTrue(r.clustered);
      assertClusteredLayout(r);
      assertAllTiles(r, ids);
      assertNoTempFilesLeft(dir);
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void orderedLargeArchiveToFileUsesLeaves() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      List<Long> ids = idsUpTo(9);
      WriteResult res;
      try (PmtilesWriter w = PmtilesWriter.builder().sortChunkEntries(50_000).open(out)) {
        for (long id : ids) {
          w.writeTileId(id, content(id));
        }
        res = w.finish();
      }
      TestReader r = new TestReader(out);
      assertTrue(res.leafDirectories() > 0);
      assertTrue(r.clustered);
      assertEquals(r.dataOffset + r.dataLength, r.leafOffset);
      assertAllTiles(r, ids);
      assertClusteredLayout(r);
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void largeMetadataIsPlacedAfterLeaves() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      StringBuilder json = new StringBuilder("{\"description\":\"");
      Random rnd = new Random(5);
      for (int i = 0; i < 40_000; i++) {
        json.append((char) ('a' + rnd.nextInt(26))); // incompressible-ish
      }
      json.append("\"}");
      try (PmtilesWriter w = PmtilesWriter.builder().open(out)) {
        w.write(0, 0, 0, new byte[] {1});
        w.setMetadata(json.toString());
        w.finish();
      }
      TestReader r = new TestReader(out);
      assertTrue(r.metadataOffset >= r.dataOffset + r.dataLength);
      assertEquals(json.toString(), r.metadata());
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void uncompressedInternals() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Path dir = tempDir();
    try {
      try (PmtilesWriter w =
          PmtilesWriter.builder()
              .tempDirectory(dir)
              .internalCompression(Compression.NONE)
              .open(bytes)) {
        for (long id : idsUpTo(4)) {
          w.writeTileId(id, content(id));
        }
        w.finish();
      }
      TestReader r = new TestReader(bytes.toByteArray());
      assertEquals(Compression.NONE.value(), r.internalCompression);
      assertAllTiles(r, idsUpTo(4));
      assertEquals("{}", r.metadata());
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void duplicateTileFailsAndLeavesNoFile() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      PmtilesWriter w = PmtilesWriter.builder().open(out);
      w.write(1, 0, 0, new byte[] {1});
      w.write(1, 1, 1, new byte[] {2});
      w.write(1, 0, 0, new byte[] {3});
      IllegalStateException e = assertThrows(IllegalStateException.class, w::finish);
      assertTrue(e.getMessage().contains("1/0/0"), e.getMessage());
      w.close();
      assertFalse(Files.exists(out));
      assertNoTempFilesLeft(dir);
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void closeWithoutFinishDiscardsOutput() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      try (PmtilesWriter w = PmtilesWriter.builder().open(out)) {
        w.write(0, 0, 0, new byte[] {1});
        assertTrue(Files.exists(out));
      }
      assertFalse(Files.exists(out));
      assertNoTempFilesLeft(dir);
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void rejectsInvalidUsage() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      try (PmtilesWriter w = PmtilesWriter.builder().open(out)) {
        assertThrows(IllegalArgumentException.class, () -> w.write(0, 0, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> w.write(2, 4, 0, new byte[] {1}));
        assertThrows(IllegalStateException.class, w::finish); // no tiles
      }
      assertThrows(
          IllegalStateException.class,
          () -> PmtilesWriter.builder().internalCompression(Compression.ZSTD).open(out));
      assertThrows(
          IllegalArgumentException.class, () -> PmtilesWriter.builder().metadata("[1,2]"));
      try (PmtilesWriter w = PmtilesWriter.builder().open(out)) {
        w.write(0, 0, 0, new byte[] {1});
        w.finish();
        assertThrows(IllegalStateException.class, () -> w.write(1, 0, 0, new byte[] {1}));
        w.finish(); // idempotent
      }
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void customCodecIsUsedForInternalCompression() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      int[] calls = {0};
      Codec counting =
          input -> {
            calls[0]++;
            return Codec.gzip().compress(input);
          };
      // pretend gzip is a third-party codec registered under GZIP
      try (PmtilesWriter w =
          PmtilesWriter.builder().internalCompression(Compression.GZIP, counting).open(out)) {
        w.write(0, 0, 0, new byte[] {1});
        w.finish();
      }
      assertEquals(2, calls[0]); // root + metadata
      assertArrayEquals(new byte[] {1}, new TestReader(out).getTile(0, 0, 0));
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void concurrentWritersProduceCompleteArchive() throws Exception {
    Path dir = tempDir();
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      Path out = dir.resolve("out.pmtiles");
      List<Long> ids = idsUpTo(8);
      try (PmtilesWriter w = PmtilesWriter.builder().sortChunkEntries(5000).open(out)) {
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
          int shard = t;
          futures.add(
              pool.submit(
                  () -> {
                    for (int i = shard; i < ids.size(); i += 4) {
                      long id = ids.get(i);
                      w.writeTileId(id, content(id));
                    }
                    return null;
                  }));
        }
        for (Future<?> f : futures) {
          f.get();
        }
        w.finish();
      }
      TestReader r = new TestReader(out);
      assertEquals(ids.size(), r.addressedTiles);
      assertAllTiles(r, ids);
    } finally {
      pool.shutdownNow();
      deleteTree(dir);
    }
  }

  @Test
  void tilesLargerThanWriteBufferAndDeepZooms() throws IOException {
    for (boolean stream : new boolean[] {false, true}) {
      Path dir = tempDir();
      try {
        Random rnd = new Random(9);
        byte[] big = new byte[3 * 1024 * 1024 + 17];
        rnd.nextBytes(big);
        byte[] small = {42};
        int max = Integer.MAX_VALUE; // x/y limit at zoom 31
        Path out = dir.resolve("out.pmtiles");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PmtilesWriter.Builder b = PmtilesWriter.builder().tempDirectory(dir);
        try (PmtilesWriter w = stream ? b.open(bytes) : b.open(out)) {
          w.write(31, max, max, small);
          w.write(3, 1, 2, big);
          w.write(31, 0, 0, big); // dedup of a large tile
          w.write(20, 1000, 2000, small);
          w.finish();
        }
        TestReader r = stream ? new TestReader(bytes.toByteArray()) : new TestReader(out);
        assertArrayEquals(big, r.getTile(3, 1, 2));
        assertArrayEquals(big, r.getTile(31, 0, 0));
        assertArrayEquals(small, r.getTile(31, max, max));
        assertArrayEquals(small, r.getTile(20, 1000, 2000));
        assertEquals(2, r.tileContents);
        assertEquals(3, r.minZoom);
        assertEquals(31, r.maxZoom);
      } finally {
        deleteTree(dir);
      }
    }
  }

  @Test
  void interruptedWriterThreadDoesNotBreakTheArchive() throws IOException {
    for (boolean stream : new boolean[] {false, true}) {
      Path dir = tempDir();
      try {
        List<Long> ids = rowMajor(8); // unordered: exercises spills, merge and reordering
        byte[] padding = new byte[4096]; // pushes data past the 1 MiB write buffer
        Path out = dir.resolve("out.pmtiles");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PmtilesWriter.Builder b = PmtilesWriter.builder().tempDirectory(dir).sortChunkEntries(500);
        try (PmtilesWriter w = stream ? b.open(bytes) : b.open(out)) {
          Thread.currentThread().interrupt(); // producer interrupted, e.g. a cancelled task
          for (long id : ids) {
            byte[] c = content(id);
            w.writeTileId(id, id % 1000 == 0 ? concat(c, padding) : c);
          }
          assertTrue(Thread.interrupted(), "interrupt flag preserved");
          w.finish(); // documented: interrupting finish() itself may abort it
        }
        TestReader r = stream ? new TestReader(bytes.toByteArray()) : new TestReader(out);
        assertEquals(ids.size(), r.addressedTiles);
        for (long id : ids) {
          byte[] c = content(id);
          assertArrayEquals(id % 1000 == 0 ? concat(c, padding) : c, r.getTile(id));
        }
      } finally {
        Thread.interrupted();
        deleteTree(dir);
      }
    }
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  @Test
  void dedupHitsBeforeInputTurnsUnorderedAreStillReclustered() throws IOException {
    Path dir = tempDir();
    try {
      byte[] same = "same".getBytes(StandardCharsets.US_ASCII);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (PmtilesWriter w = PmtilesWriter.builder().tempDirectory(dir).open(bytes)) {
        w.writeTileId(10, same); // written
        w.writeTileId(11, content(11));
        w.writeTileId(20, same); // dedup hit while input is still ordered
        w.writeTileId(30, content(30));
        w.writeTileId(5, same); // input turns unordered; 5 now comes first in tile ID order
        w.writeTileId(3, content(3));
        WriteResult res = w.finish();
        assertTrue(res.clustered());
        assertEquals(4, res.tileContents());
      }
      TestReader r = new TestReader(bytes.toByteArray());
      assertClusteredLayout(r);
      for (long id : new long[] {5, 10, 20}) {
        assertArrayEquals(same, r.getTile(id));
      }
      for (long id : new long[] {3, 11, 30}) {
        assertArrayEquals(content(id), r.getTile(id));
      }
      assertEquals(4, r.tileContents);
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void failedOpenLeavesNothingBehind() throws IOException {
    Path dir = tempDir();
    try {
      Path notAFile = Files.createDirectory(dir.resolve("out.pmtiles"));
      assertThrows(
          IOException.class, () -> PmtilesWriter.builder().tempDirectory(dir).open(notAFile));
      assertNoTempFilesLeft(dir, notAFile);

      Path out = dir.resolve("other.pmtiles");
      assertThrows(
          IOException.class,
          () -> PmtilesWriter.builder().tempDirectory(dir.resolve("missing")).open(out));
      assertFalse(Files.exists(out), "output must not be created when setup fails");
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void explicitBoundsAndCenter() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      try (PmtilesWriter w =
          PmtilesWriter.builder()
              .bounds(124.5, 33.0, 131.9, 38.7)
              .center(127.0, 37.5, 9)
              .open(out)) {
        w.write(7, 109, 49, new byte[] {1});
        w.write(10, 873, 396, new byte[] {2});
        w.finish();
      }
      TestReader r = new TestReader(out);
      assertEquals(124.5, r.minLon, 1e-7);
      assertEquals(33.0, r.minLat, 1e-7);
      assertEquals(131.9, r.maxLon, 1e-7);
      assertEquals(38.7, r.maxLat, 1e-7);
      assertEquals(127.0, r.centerLon, 1e-7);
      assertEquals(37.5, r.centerLat, 1e-7);
      assertEquals(9, r.centerZoom);
      assertEquals(7, r.minZoom);
      assertEquals(10, r.maxZoom);
    } finally {
      deleteTree(dir);
    }
  }

  @Test
  void defaultBoundsFollowDeepestZoomExtent() throws IOException {
    Path dir = tempDir();
    try {
      Path out = dir.resolve("out.pmtiles");
      try (PmtilesWriter w = PmtilesWriter.builder().open(out)) {
        w.write(0, 0, 0, new byte[] {1});
        w.write(1, 1, 0, new byte[] {2}); // north-east quadrant
        w.finish();
      }
      TestReader r = new TestReader(out);
      assertEquals(0, r.minLon, 1e-7);
      assertEquals(180, r.maxLon, 1e-7);
      assertEquals(0, r.minLat, 1e-7);
      assertEquals(85.0511287, r.maxLat, 1e-6);
      assertEquals(0, r.centerZoom);
    } finally {
      deleteTree(dir);
    }
  }
}
