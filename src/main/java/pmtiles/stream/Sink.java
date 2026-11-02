package pmtiles.stream;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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

  /** Writes straight into a local file through a 1 MiB buffer; the head goes to offset 0 last. */
  final class FileSink implements Sink {
    private static final int BUFFER_BYTES = 1 << 20;

    private final Path path;
    private final RandomAccessFile file;
    private final byte[] buffer = new byte[BUFFER_BYTES];
    private int buffered;
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
      if (len > buffer.length - buffered) {
        flush();
      }
      if (len > buffer.length) {
        file.write(b, off, len);
      } else {
        System.arraycopy(b, off, buffer, buffered, len);
        buffered += len;
      }
    }

    private void flush() throws IOException {
      file.write(buffer, 0, buffered);
      buffered = 0;
    }

    @Override
    public void finish(byte[] head) throws IOException {
      flush();
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
   * Fills parts 2..n while tiles arrive and uploads each full part on a dedicated uploader thread;
   * part 1 (head + first tile bytes) is uploaded last, then the upload is completed.
   *
   * <p>All {@link MultipartUpload} calls run on the uploader thread, one at a time, so a network
   * upload never holds the writer lock and an interrupted producer thread never reaches the
   * storage SDK. Waiting for a free buffer ignores interrupts (the flag is restored afterwards).
   *
   * <p>Memory: three parts. Part 1 stays in memory until the end; two more buffers alternate
   * between being filled and being uploaded, which also throttles producers to the upload speed.
   */
  final class MultipartSink implements Sink {
    static final int MAX_PARTS = 10_000;

    private interface Call {
      void run() throws IOException;
    }

    /** One call for the uploader; {@code recycle} is returned to the free list afterwards. */
    private record Task(Call call, byte[] recycle, boolean always) {}

    private final MultipartUpload upload;
    private final int partSize;
    private final byte[] first;
    private int firstFill = Header.ROOT_REGION;
    private byte[] current; // null until part 1 is full
    private int currentFill;
    private int partNumber = 1; // the part being filled
    private int buffersAllocated;
    private boolean done;

    // guarded by lock
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<Task> tasks = new ArrayDeque<>();
    private final ArrayDeque<byte[]> free = new ArrayDeque<>();
    private boolean running; // a task is executing
    private boolean stopping;
    private Throwable failure;
    private Throwable abortFailure;

    private final Thread uploader;

    MultipartSink(MultipartUpload upload, int partSize) {
      this.upload = upload;
      this.partSize = partSize;
      this.first = new byte[partSize];
      this.uploader = new Thread(this::uploadLoop, "pmtiles-multipart-upload");
      uploader.setDaemon(true);
      uploader.start();
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
            partNumber = 2;
            current = takeBuffer();
          }
        } else {
          if (partNumber > MAX_PARTS) {
            throw new IOException(
                "archive exceeds " + MAX_PARTS + " parts of " + partSize + " bytes; use a larger"
                    + " partSize");
          }
          n = Math.min(len, partSize - currentFill);
          System.arraycopy(b, off, current, currentFill, n);
          currentFill += n;
          if (currentFill == partSize) {
            submitPart(partNumber, current, currentFill);
            partNumber++;
            currentFill = 0;
            current = takeBuffer();
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
        submitPart(partNumber, current, currentFill);
      }
      submit(new Task(() -> upload.uploadPart(1, first, firstFill), null, false));
      submit(new Task(upload::complete, null, false));
      awaitIdle();
      rethrowFailure();
      done = true;
      stop();
    }

    @Override
    public void abort() throws IOException {
      if (done) {
        return;
      }
      done = true;
      lock.lock();
      try {
        tasks.clear(); // parts not yet started are dropped
        tasks.add(new Task(this::callAbort, null, true));
        changed.signalAll();
      } finally {
        lock.unlock();
      }
      awaitIdle();
      stop();
      lock.lock();
      try {
        // earlier upload errors were already reported; only abort()'s own failure matters here
        if (abortFailure != null) {
          throw new IOException("multipart abort failed", abortFailure);
        }
      } finally {
        lock.unlock();
      }
    }

    /** Runs on the uploader thread. */
    private void callAbort() {
      try {
        upload.abort();
      } catch (Throwable e) {
        lock.lock();
        try {
          abortFailure = e;
        } finally {
          lock.unlock();
        }
      }
    }

    private void submitPart(int number, byte[] data, int length) throws IOException {
      submit(new Task(() -> upload.uploadPart(number, data, length), data, false));
    }

    private void submit(Task task) throws IOException {
      lock.lock();
      try {
        rethrowFailure();
        tasks.add(task);
        changed.signalAll();
      } finally {
        lock.unlock();
      }
    }

    /** A buffer for the next part: a new one while fewer than two exist, else one freed by an upload. */
    private byte[] takeBuffer() throws IOException {
      lock.lock();
      try {
        if (free.isEmpty() && buffersAllocated < 2) {
          buffersAllocated++;
          return new byte[partSize];
        }
        while (free.isEmpty() && failure == null) {
          changed.awaitUninterruptibly();
        }
        rethrowFailure();
        return free.poll();
      } finally {
        lock.unlock();
      }
    }

    private void awaitIdle() {
      lock.lock();
      try {
        while (!tasks.isEmpty() || running) {
          changed.awaitUninterruptibly();
        }
      } finally {
        lock.unlock();
      }
    }

    private void rethrowFailure() throws IOException {
      lock.lock();
      try {
        if (failure instanceof IOException e) {
          throw new IOException("multipart upload failed: " + e.getMessage(), e);
        } else if (failure != null) {
          throw new IOException("multipart upload failed", failure);
        }
      } finally {
        lock.unlock();
      }
    }

    private void stop() {
      lock.lock();
      try {
        stopping = true;
        changed.signalAll();
      } finally {
        lock.unlock();
      }
      boolean interrupted = false;
      while (true) {
        try {
          uploader.join();
          break;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    private void uploadLoop() {
      while (true) {
        Task task;
        boolean skip;
        lock.lock();
        try {
          while (tasks.isEmpty() && !stopping) {
            changed.awaitUninterruptibly();
          }
          task = tasks.poll();
          if (task == null) {
            return;
          }
          running = true;
          skip = failure != null && !task.always();
        } finally {
          lock.unlock();
        }
        Throwable error = null;
        if (!skip) {
          try {
            task.call().run();
          } catch (Throwable e) {
            error = e;
          }
        }
        lock.lock();
        try {
          if (error != null && failure == null) {
            failure = error;
          }
          if (task.recycle() != null) {
            free.add(task.recycle());
          }
          running = false;
          changed.signalAll();
        } finally {
          lock.unlock();
        }
      }
    }
  }
}
