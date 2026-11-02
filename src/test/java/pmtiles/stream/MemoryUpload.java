package pmtiles.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-memory {@link MultipartUpload} that checks S3's part rules and assembles the object. Like the
 * AWS SDK, it fails when called on an interrupted thread.
 */
final class MemoryUpload implements MultipartUpload {
  final Map<Integer, byte[]> parts = new TreeMap<>();
  final List<Integer> order = new ArrayList<>();
  final Set<Thread> callers = new HashSet<>();
  boolean completed;
  boolean aborted;
  /** If > 0, uploading this part number throws. */
  int failOnPart;

  @Override
  public synchronized void uploadPart(int partNumber, byte[] data, int length) throws IOException {
    enter();
    if (completed || aborted) {
      throw new IllegalStateException("upload already ended");
    }
    if (partNumber < 1 || partNumber > 10_000 || parts.containsKey(partNumber)) {
      throw new IllegalStateException("bad part number " + partNumber);
    }
    if (partNumber == failOnPart) {
      throw new IOException("simulated failure of part " + partNumber);
    }
    parts.put(partNumber, Arrays.copyOf(data, length)); // data is reused by the caller
    order.add(partNumber);
  }

  @Override
  public synchronized void complete() throws IOException {
    enter();
    if (completed || aborted) {
      throw new IllegalStateException("upload already ended");
    }
    completed = true;
  }

  @Override
  public synchronized void abort() throws IOException {
    enter();
    aborted = true;
  }

  private void enter() throws IOException {
    callers.add(Thread.currentThread());
    if (Thread.currentThread().isInterrupted()) {
      throw new IOException("called on an interrupted thread (the AWS SDK would fail here)");
    }
  }

  /** The completed object; checks parts are contiguous and all but the last have equal size. */
  synchronized byte[] object() throws IOException {
    if (!completed) {
      throw new IllegalStateException("not completed");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int expected = 1;
    int size = -1;
    for (Map.Entry<Integer, byte[]> e : parts.entrySet()) {
      if (e.getKey() != expected++) {
        throw new IllegalStateException("missing part " + (expected - 1));
      }
      boolean last = e.getKey() == parts.size();
      if (!last) {
        if (size == -1) {
          size = e.getValue().length;
        } else if (e.getValue().length != size) {
          throw new IllegalStateException("part " + e.getKey() + " has a different size");
        }
      }
      out.write(e.getValue());
    }
    return out.toByteArray();
  }
}
