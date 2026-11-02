package pmtiles.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TileIdTest {

  @Test
  void specExamples() {
    // table from the PMTiles v3 spec
    assertEquals(0, TileId.toTileId(0, 0, 0));
    assertEquals(1, TileId.toTileId(1, 0, 0));
    assertEquals(2, TileId.toTileId(1, 0, 1));
    assertEquals(3, TileId.toTileId(1, 1, 1));
    assertEquals(4, TileId.toTileId(1, 1, 0));
    assertEquals(5, TileId.toTileId(2, 0, 0));
    assertEquals(19078479, TileId.toTileId(12, 3423, 1763));
    assertEquals(new TileCoord(12, 3423, 1763), TileId.toCoord(19078479));
  }

  @Test
  void everyIdUpToZoom8RoundTrips() {
    long end = TileId.zoomStart(9);
    for (long id = 0; id < end; id++) {
      TileCoord c = TileId.toCoord(id);
      assertEquals(id, TileId.toTileId(c.z(), c.x(), c.y()));
    }
  }

  @Test
  void hilbertNeighboursAreAdjacent() {
    // consecutive ids within a zoom are always edge-adjacent tiles on a Hilbert curve
    for (int z = 1; z <= 7; z++) {
      for (long id = TileId.zoomStart(z); id + 1 < TileId.zoomStart(z + 1); id++) {
        TileCoord a = TileId.toCoord(id);
        TileCoord b = TileId.toCoord(id + 1);
        assertEquals(1, Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()));
      }
    }
  }

  @Test
  void highZoomsRoundTrip() {
    Random r = new Random(42);
    for (int z = 0; z <= TileId.MAX_ZOOM; z++) {
      long n = 1L << z;
      long[][] samples = {{0, 0}, {n - 1, n - 1}, {0, n - 1}, {n - 1, 0}};
      for (long[] s : samples) {
        long id = TileId.toTileId(z, s[0], s[1]);
        TileCoord c = TileId.toCoord(id);
        assertEquals(z, c.z());
        assertEquals(s[0], c.x());
        assertEquals(s[1], c.y());
      }
      for (int i = 0; i < 1000; i++) {
        int x = (int) (r.nextLong() & (n - 1));
        int y = (int) (r.nextLong() & (n - 1));
        long id = TileId.toTileId(z, x, y);
        assertEquals(new TileCoord(z, x, y), TileId.toCoord(id));
      }
    }
    assertEquals(TileId.zoomStart(32) - 1, TileId.toTileId(31, (1L << 31) - 1, 0));
  }

  @Test
  void rejectsOutOfRange() {
    assertThrows(IllegalArgumentException.class, () -> TileId.toTileId(32, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> TileId.toTileId(3, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> TileId.toTileId(3, 0, -1));
    assertThrows(IllegalArgumentException.class, () -> TileId.toCoord(-1));
    assertThrows(IllegalArgumentException.class, () -> TileId.toCoord(TileId.zoomStart(32)));
  }
}
