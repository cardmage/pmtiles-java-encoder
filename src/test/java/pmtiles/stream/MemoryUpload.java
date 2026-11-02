package pmtiles.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** In-memory {@link MultipartUpload} that checks S3's part rules and assembles the object. */
final class MemoryUpload implements MultipartUpload {
  final Map<Integer, byte[]> parts = new TreeMap<>();
  final List<Integer> order = new ArrayList<>();
  boolean completed;
  boolean aborted;

  @Override
  public void uploadPart(int partNumber, byte[] data, int length) {
    if (completed || aborted) {
      throw new IllegalStateException("upload already ended");
    }
    if (partNumber < 1 || partNumber > 10_000 || parts.containsKey(partNumber)) {
      throw new IllegalStateException("bad part number " + partNumber);
    }
    parts.put(partNumber, Arrays.copyOf(data, length)); // data is reused by the caller
    order.add(partNumber);
  }

  @Override
  public void complete() {
    if (completed || aborted) {
      throw new IllegalStateException("upload already ended");
    }
    completed = true;
  }

  @Override
  public void abort() {
    aborted = true;
  }

  /** The completed object; checks parts are contiguous and all but the last have equal size. */
  byte[] object() throws IOException {
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
