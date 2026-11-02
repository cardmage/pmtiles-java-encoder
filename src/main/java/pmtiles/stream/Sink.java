package pmtiles.stream;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the archive goes. The writer produces it in two pieces: everything after the reserved
 * first 16 KiB, in order, while tiles arrive; then the first 16 KiB (header, root directory, zero
 * padding) once at the end. Each archive byte is written exactly once.
 */
interface Sink {

  /** Appends archive bytes, starting at offset {@link Header#ROOT_REGION}. */
  void write(byte[] b, int off, int len) throws IOException;

  /** Writes the first {@link Header#ROOT_REGION} bytes and commits the archive. */
  void finish(byte[] head) throws IOException;

  /** Discards a partial archive. Idempotent; a no-op after a successful {@link #finish}. */
  void abort() throws IOException;

  /** Writes straight into a local file; the head is written last at offset 0. */
  final class FileSink implements Sink {
    private final Path path;
    private final RandomAccessFile file;
    private boolean done;

    FileSink(Path path) throws IOException {
      this.path = path;
      this.file = new RandomAccessFile(path.toFile(), "rw");
      try {
        file.setLength(0);
        file.seek(Header.ROOT_REGION);
      } catch (IOException | RuntimeException e) {
        file.close();
        Files.deleteIfExists(path);
        throw e;
      }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      file.write(b, off, len);
    }

    @Override
    public void finish(byte[] head) throws IOException {
      file.seek(0);
      file.write(head);
      file.getFD().sync();
      file.close();
      done = true;
    }

    @Override
    public void abort() throws IOException {
      if (done) {
        return;
      }
      done = true;
      try {
        file.close();
      } finally {
        Files.deleteIfExists(path);
      }
    }
  }

  /**
   * Uploads parts 2..n as they fill and part 1 (head + first tile bytes) last. Holds at most two
   * parts in memory: part 1 and the part being filled.
   */
  final class MultipartSink implements Sink {
    static final int MAX_PARTS = 10_000;

    private final MultipartUpload upload;
    private final byte[] first;
    private int firstFill = Header.ROOT_REGION;
    private byte[] current; // allocated once part 1 is full
    private int currentFill;
    private int partNumber = 1; // the part being filled
    private boolean done;

    MultipartSink(MultipartUpload upload, int partSize) {
      this.upload = upload;
      this.first = new byte[partSize];
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      while (len > 0) {
        int n;
        if (partNumber == 1) {
          n = Math.min(len, first.length - firstFill);
          System.arraycopy(b, off, first, firstFill, n);
          firstFill += n;
          if (firstFill == first.length) {
            current = new byte[first.length];
            partNumber = 2;
          }
        } else {
          if (partNumber > MAX_PARTS) {
            throw new IOException(
                "archive exceeds "
                    + MAX_PARTS
                    + " parts of "
                    + first.length
                    + " bytes; use a larger partSize");
          }
          n = Math.min(len, current.length - currentFill);
          System.arraycopy(b, off, current, currentFill, n);
          currentFill += n;
          if (currentFill == current.length) {
            upload.uploadPart(partNumber, current, currentFill);
            partNumber++;
            currentFill = 0;
          }
        }
        off += n;
        len -= n;
      }
    }

    @Override
    public void finish(byte[] head) throws IOException {
      System.arraycopy(head, 0, first, 0, head.length);
      if (currentFill > 0) {
        upload.uploadPart(partNumber, current, currentFill);
      }
      upload.uploadPart(1, first, firstFill);
      upload.complete();
      done = true;
    }

    @Override
    public void abort() throws IOException {
      if (!done) {
        done = true;
        upload.abort();
      }
    }
  }
}
