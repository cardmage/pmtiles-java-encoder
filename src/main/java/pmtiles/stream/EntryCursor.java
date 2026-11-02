package pmtiles.stream;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Merges sorted run files of entry records into one stream in ascending tile ID order. */
final class EntryCursor implements Closeable {

  private final List<RunReader> readers = new ArrayList<>();
  private final PriorityQueue<RunReader> heap =
      new PriorityQueue<>(Comparator.comparingLong(r -> r.tileId));
  private RunReader current;

  EntryCursor(List<EntryStore.Run> runs) throws IOException {
    try {
      for (EntryStore.Run run : runs) {
        RunReader r = new RunReader(run);
        readers.add(r);
        if (r.advance()) {
          heap.add(r);
        }
      }
    } catch (IOException e) {
      close();
      throw e;
    }
  }

  boolean next() throws IOException {
    if (current != null && current.advance()) {
      if (heap.isEmpty()) {
        return true; // only one run left: no need to touch the heap
      }
      heap.add(current);
    }
    current = heap.poll();
    return current != null;
  }

  long tileId() {
    return current.tileId;
  }

  long offset() {
    return current.offset;
  }

  int length() {
    return current.length;
  }

  int runLength() {
    return current.runLength;
  }

  @Override
  public void close() throws IOException {
    IOException first = null;
    for (RunReader r : readers) {
      try {
        r.in.close();
      } catch (IOException e) {
        first = first == null ? e : first;
      }
    }
    if (first != null) {
      throw first;
    }
  }

  private static final class RunReader {
    final DataInputStream in;
    long remaining;
    long tileId;
    long offset;
    int length;
    int runLength;

    RunReader(EntryStore.Run run) throws IOException {
      in = EntryStore.openIn(run.path());
      remaining = run.count();
    }

    boolean advance() throws IOException {
      if (remaining == 0) {
        return false;
      }
      remaining--;
      tileId = in.readLong();
      offset = in.readLong();
      length = in.readInt();
      runLength = in.readInt();
      return true;
    }
  }
}
