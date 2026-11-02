package pmtiles.stream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Disk-backed buffer of directory entries with external merge sort.
 *
 * <p>Entries accumulate in a bounded in-memory chunk. A full chunk is sorted (if needed) and
 * spilled to a run file of fixed 24-byte records. While input stays in ascending tile ID order,
 * every chunk is appended to the same run, so ordered input ends up as one sorted file and needs
 * no merge. Heap usage is bounded by the chunk size no matter how many tiles are written.
 */
final class EntryStore {

  private static final int MAX_FAN_IN = 128;
  private static final int IO_BUFFER = 1 << 16;

  record Run(Path path, long count) {}

  /** All entries in ascending tile ID order, re-readable (directory building may take passes). */
  record Sorted(List<Run> runs, long count) {
    EntryCursor open() throws IOException {
      return new EntryCursor(runs);
    }
  }

  private final Path dir;
  private final int chunkCapacity;
  private final Directory chunk;
  private final List<Run> runs = new ArrayList<>();
  private boolean chunkSorted = true;
  private boolean ordered = true;
  private long lastEnd = Long.MIN_VALUE;
  private long count;
  private int runSeq;

  EntryStore(Path dir, int chunkCapacity) {
    this.dir = dir;
    this.chunkCapacity = chunkCapacity;
    this.chunk = new Directory(Math.min(chunkCapacity, 4096), chunkCapacity);
  }

  void add(long tileId, long offset, int length, int runLength) throws IOException {
    if (tileId < lastEnd) {
      ordered = false;
    }
    if (chunk.size > 0 && tileId < chunk.tileIds[chunk.size - 1]) {
      chunkSorted = false;
    }
    lastEnd = tileId + runLength;
    chunk.add(tileId, offset, length, runLength);
    count++;
    if (chunk.size == chunkCapacity) {
      spill();
    }
  }

  /** True if every entry so far started at or after the end of the previous one. */
  boolean isOrdered() {
    return ordered;
  }

  /** Ends input and returns all entries sorted by tile ID. */
  Sorted sorted() throws IOException {
    spill();
    while (runs.size() > MAX_FAN_IN) {
      List<Run> merged = new ArrayList<>();
      for (int i = 0; i < runs.size(); i += MAX_FAN_IN) {
        merged.add(mergeToRun(runs.subList(i, Math.min(runs.size(), i + MAX_FAN_IN))));
      }
      runs.clear();
      runs.addAll(merged);
    }
    return new Sorted(List.copyOf(runs), count);
  }

  /** Deletes the run files early; the work directory is removed by the writer anyway. */
  void deleteRuns() throws IOException {
    for (Run r : runs) {
      Files.deleteIfExists(r.path());
    }
    runs.clear();
  }

  private void spill() throws IOException {
    if (chunk.size == 0) {
      return;
    }
    if (!chunkSorted) {
      sortChunk(chunk, 0, chunk.size);
    }
    boolean append = ordered && !runs.isEmpty();
    Path path = append ? runs.get(runs.size() - 1).path() : newRunPath();
    try (DataOutputStream out = openOut(path, append)) {
      for (int i = 0; i < chunk.size; i++) {
        writeRecord(out, chunk.tileIds[i], chunk.offsets[i], chunk.lengths[i], chunk.runLengths[i]);
      }
    }
    if (append) {
      Run last = runs.remove(runs.size() - 1);
      runs.add(new Run(path, last.count() + chunk.size));
    } else {
      runs.add(new Run(path, chunk.size));
    }
    chunk.clear();
    chunkSorted = true;
  }

  private Run mergeToRun(List<Run> group) throws IOException {
    if (group.size() == 1) {
      return group.get(0);
    }
    Path path = newRunPath();
    try (EntryCursor c = new EntryCursor(group);
        DataOutputStream out = openOut(path, false)) {
      while (c.next()) {
        writeRecord(out, c.tileId(), c.offset(), c.length(), c.runLength());
      }
    }
    long n = 0;
    for (Run r : group) {
      n += r.count();
      Files.deleteIfExists(r.path());
    }
    return new Run(path, n);
  }

  private Path newRunPath() {
    return dir.resolve("entries-" + (runSeq++) + ".bin");
  }

  // ---------------------------------------------------------------------------------------------
  // record I/O: tileId (8) | offset (8) | length (4) | runLength (4)

  // java.io streams rather than Files.newXxxStream: NIO channels close on thread interrupt

  static DataOutputStream openOut(Path path, boolean append) throws IOException {
    return new DataOutputStream(
        new BufferedOutputStream(new FileOutputStream(path.toFile(), append), IO_BUFFER));
  }

  static DataInputStream openIn(Path path) throws IOException {
    return new DataInputStream(
        new BufferedInputStream(new FileInputStream(path.toFile()), IO_BUFFER));
  }

  static void writeRecord(DataOutputStream out, long tileId, long offset, int length, int run)
      throws IOException {
    out.writeLong(tileId);
    out.writeLong(offset);
    out.writeInt(length);
    out.writeInt(run);
  }

  // ---------------------------------------------------------------------------------------------
  // in-place quicksort of the columnar chunk by tile ID

  static void sortChunk(Directory d, int lo, int hi) {
    long[] k = d.tileIds;
    while (hi - lo > 24) {
      int mid = (lo + hi) >>> 1;
      // order k[lo] <= k[mid] <= k[hi-1]; the median becomes the pivot
      if (k[mid] < k[lo]) {
        swap(d, mid, lo);
      }
      if (k[hi - 1] < k[lo]) {
        swap(d, hi - 1, lo);
      }
      if (k[hi - 1] < k[mid]) {
        swap(d, hi - 1, mid);
      }
      long pivot = k[mid];
      int i = lo - 1;
      int j = hi;
      while (true) {
        do {
          i++;
        } while (k[i] < pivot);
        do {
          j--;
        } while (k[j] > pivot);
        if (i >= j) {
          break;
        }
        swap(d, i, j);
      }
      // [lo, j] <= pivot <= [j+1, hi); recurse into the smaller half
      if (j + 1 - lo < hi - (j + 1)) {
        sortChunk(d, lo, j + 1);
        lo = j + 1;
      } else {
        sortChunk(d, j + 1, hi);
        hi = j + 1;
      }
    }
    for (int i = lo + 1; i < hi; i++) {
      for (int j = i; j > lo && k[j] < k[j - 1]; j--) {
        swap(d, j, j - 1);
      }
    }
  }

  private static void swap(Directory d, int a, int b) {
    long t = d.tileIds[a];
    d.tileIds[a] = d.tileIds[b];
    d.tileIds[b] = t;
    long o = d.offsets[a];
    d.offsets[a] = d.offsets[b];
    d.offsets[b] = o;
    int l = d.lengths[a];
    d.lengths[a] = d.lengths[b];
    d.lengths[b] = l;
    int r = d.runLengths[a];
    d.runLengths[a] = d.runLengths[b];
    d.runLengths[b] = r;
  }
}
