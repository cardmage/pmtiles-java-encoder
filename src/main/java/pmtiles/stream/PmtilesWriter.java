package pmtiles.stream;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streaming PMTiles v3 writer with bounded memory.
 *
 * <p>Tiles are written to disk as they arrive; directory entries are buffered in a fixed-size
 * chunk and spilled to temporary files, so heap usage does not grow with the number of tiles. No
 * MBTiles/SQLite intermediate is needed.
 *
 * <h2>Output modes</h2>
 *
 * <ul>
 *   <li>{@link Builder#open(Path)} &mdash; tile bytes go straight into the output file, starting
 *       at byte 16384. On {@link #finish()} leaf directories and metadata are appended and the
 *       header + root directory are written into the reserved first 16 KiB. Each tile byte is
 *       written exactly once.
 *   <li>{@link Builder#open(OutputStream)} &mdash; for non-seekable targets (S3 multipart upload,
 *       HTTP response, pipes). Tiles are staged in a temp file; {@link #finish()} streams header,
 *       root, metadata, leaves, then tile data in canonical order. If tiles arrived out of tile ID
 *       order, the tile data is reordered while copying so the result is still clustered.
 * </ul>
 *
 * <h2>Ordering</h2>
 *
 * Tiles may be written in any order. Writing in ascending tile ID order (see {@link TileId}) is
 * cheapest and always yields a clustered archive. Out-of-order input is sorted with an external
 * merge sort; in {@code open(Path)} mode the archive is then valid but marked not clustered.
 *
 * <h2>Lifecycle and failures</h2>
 *
 * Call {@link #finish()} to complete the archive. In {@code open(Path)} mode, {@link #close()}
 * without a successful {@code finish()} deletes the output, so an exception inside
 * try-with-resources never leaves a broken file behind. In {@code open(OutputStream)} mode nothing
 * is sent before {@code finish()}; if {@code finish()} itself fails part-way, the stream holds a
 * truncated archive and the caller must abort the upload or response.
 *
 * <pre>{@code
 * try (PmtilesWriter w = PmtilesWriter.builder()
 *         .tileType(TileType.MVT)
 *         .tileCompression(Compression.GZIP)
 *         .metadata("{\"vector_layers\":[...]}")
 *         .open(Path.of("out.pmtiles"))) {
 *   for (...) w.write(z, x, y, gzippedMvtBytes);
 *   w.finish();
 * }
 * }</pre>
 *
 * <p>All methods are thread-safe; content hashing for deduplication runs outside the lock.
 * Writing uses {@code java.io} streams, which unlike NIO channels are not closed when a producer
 * thread is interrupted (e.g. a cancelled task), so one interrupt cannot destroy the archive.
 */
public final class PmtilesWriter implements Closeable {

  private static final int WRITE_BUFFER_BYTES = 1 << 20;

  private final Builder config;
  private final Codec codec;
  private final Path outputPath; // file mode
  private final OutputStream outputStream; // stream mode
  private final Path workDir;
  private final EntryStore entries;
  private final DedupCache dedup;
  /**
   * Stream mode: each offset that gets its first dedup hit, so tile data can be reordered at
   * finish. Kept on disk; only read back if the input turned out to be unordered.
   */
  private final Path repeatedFile;
  private final DataOutputStream repeatedLog;
  private final RandomAccessFile data; // output file (file mode) or staged tiles (stream mode)

  private final byte[] buffer = new byte[WRITE_BUFFER_BYTES];
  private int buffered;
  private long dataLength;
  private String metadataJson;

  // run-length encoding: the last entry is held back until it can no longer grow
  private boolean hasPending;
  private long pendingTileId;
  private long pendingOffset;
  private int pendingLength;
  private int pendingRun;

  private long addressedTiles;
  private long tileContents;
  private int minZoom = Integer.MAX_VALUE;
  private int maxZoom = -1;
  // tile extent at maxZoom, for default bounds
  private long minX;
  private long minY;
  private long maxX;
  private long maxY;

  private boolean finished;
  private boolean failed;
  private boolean closed;
  private WriteResult result;

  private PmtilesWriter(Builder b, Path outputPath, OutputStream outputStream) throws IOException {
    this.config = b.copy();
    this.codec = config.codecs.get(config.internalCompression);
    this.metadataJson = config.metadata;
    this.outputPath = outputPath;
    this.outputStream = outputStream;

    Path tempParent = config.tempDirectory;
    if (tempParent == null && outputPath != null) {
      tempParent = outputPath.toAbsolutePath().getParent(); // spills are small in file mode
    }
    this.workDir =
        tempParent == null
            ? Files.createTempDirectory("pmtiles-writer-")
            : Files.createTempDirectory(tempParent, ".pmtiles-writer-");
    this.repeatedFile = workDir.resolve("repeated.bin");
    DataOutputStream log = null;
    try {
      this.entries = new EntryStore(workDir, config.sortChunkEntries);
      this.dedup = config.dedupCacheEntries > 0 ? new DedupCache(config.dedupCacheEntries) : null;
      if (outputStream != null && dedup != null) {
        log = EntryStore.openOut(repeatedFile, false);
      }
      this.repeatedLog = log;
      // opened last, so nothing can fail after the output file has been truncated
      if (outputPath != null) {
        this.data = new RandomAccessFile(outputPath.toFile(), "rw");
        data.setLength(0);
        data.seek(Header.ROOT_REGION);
      } else {
        this.data = new RandomAccessFile(workDir.resolve("tiles.bin").toFile(), "rw");
      }
    } catch (IOException | RuntimeException | Error e) {
      if (log != null) {
        log.close();
      }
      deleteDirectory(workDir);
      throw e;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  // -----------------------------------------------------------------------------------------------
  // writing

  /** Writes one tile. {@code tile} is stored as-is (already compressed per tileCompression). */
  public void write(int z, int x, int y, byte[] tile) throws IOException {
    write(TileId.toTileId(z, x, y), z, x, y, tile);
  }

  /** Writes one tile addressed by its PMTiles tile ID. */
  public void writeTileId(long tileId, byte[] tile) throws IOException {
    TileCoord c = TileId.toCoord(tileId);
    write(tileId, c.z(), c.x(), c.y(), tile);
  }

  /** Replaces the JSON metadata. May be called any time before {@link #finish()}. */
  public synchronized void setMetadata(String json) {
    checkWritable();
    this.metadataJson = validateJson(json);
  }

  private void write(long tileId, int z, int x, int y, byte[] tile) throws IOException {
    if (tile.length == 0) {
      throw new IllegalArgumentException(
          "empty tile " + z + "/" + x + "/" + y + ": PMTiles requires length > 0, skip it instead");
    }
    DedupCache.Key key = dedup == null ? null : DedupCache.key(tile);
    synchronized (this) {
      checkWritable();
      try {
        DedupCache.Slot hit = key == null ? null : dedup.get(key);
        long offset;
        if (hit != null) {
          offset = hit.offset;
          if (repeatedLog != null && !hit.repeated) {
            hit.repeated = true;
            repeatedLog.writeLong(offset);
          }
        } else {
          offset = dataLength;
          append(tile);
          tileContents++;
          if (key != null) {
            dedup.put(key, offset);
          }
        }
        addEntry(tileId, offset, tile.length);
        addressedTiles++;
        trackExtent(z, x, y);
      } catch (IOException | RuntimeException | Error e) {
        failed = true;
        throw e;
      }
    }
  }

  private void trackExtent(int z, int x, int y) {
    minZoom = Math.min(minZoom, z);
    if (z > maxZoom) {
      maxZoom = z;
      minX = maxX = x;
      minY = maxY = y;
    } else if (z == maxZoom) {
      minX = Math.min(minX, x);
      maxX = Math.max(maxX, x);
      minY = Math.min(minY, y);
      maxY = Math.max(maxY, y);
    }
  }

  private void addEntry(long tileId, long offset, int length) throws IOException {
    if (hasPending
        && tileId == pendingTileId + pendingRun
        && offset == pendingOffset
        && length == pendingLength
        && pendingRun < Integer.MAX_VALUE) {
      pendingRun++;
      return;
    }
    flushPending();
    hasPending = true;
    pendingTileId = tileId;
    pendingOffset = offset;
    pendingLength = length;
    pendingRun = 1;
  }

  private void flushPending() throws IOException {
    if (hasPending) {
      entries.add(pendingTileId, pendingOffset, pendingLength, pendingRun);
      hasPending = false;
    }
  }

  /** Appends at the end of the tile data; the file pointer stays there until finish(). */
  private void append(byte[] tile) throws IOException {
    if (tile.length > buffer.length - buffered) {
      flushBuffer();
    }
    if (tile.length > buffer.length) {
      data.write(tile);
    } else {
      System.arraycopy(tile, 0, buffer, buffered, tile.length);
      buffered += tile.length;
    }
    dataLength += tile.length;
  }

  private void flushBuffer() throws IOException {
    data.write(buffer, 0, buffered);
    buffered = 0;
  }

  // -----------------------------------------------------------------------------------------------
  // finishing

  /**
   * Completes the archive: sorts entries if needed, builds directories, writes header, root,
   * metadata and leaves. Returns the same result if called again after success.
   */
  public synchronized WriteResult finish() throws IOException {
    if (finished) {
      return result;
    }
    checkWritable();
    if (addressedTiles == 0) {
      throw new IllegalStateException(
          "no tiles written: a PMTiles directory must contain at least one entry");
    }
    try {
      flushPending();
      flushBuffer();
      if (repeatedLog != null) {
        repeatedLog.close();
      }

      boolean ordered = entries.isOrdered();
      boolean reorder = outputStream != null && !ordered;
      Path planFile = workDir.resolve("copy-plan.bin");
      EntryStore.Sorted sorted = entries.sorted();
      EntryStore.Sorted finalEntries =
          ordered ? sorted : finalizeEntries(sorted, reorder ? planFile : null);

      Path leavesFile = workDir.resolve("leaves.bin");
      DirectoryBuilder.Result dirs = DirectoryBuilder.build(finalEntries, codec, leavesFile);
      byte[] root = dirs.root();
      byte[] meta = codec.compress(metadataJson.getBytes(StandardCharsets.UTF_8));

      Header h = new Header();
      h.internalCompression = config.internalCompression;
      h.tileCompression = config.tileCompression;
      h.tileType = config.tileType;
      h.clustered = ordered || reorder;
      h.addressedTiles = addressedTiles;
      h.tileEntries = finalEntries.count();
      h.tileContents = tileContents;
      h.tileDataLength = dataLength;
      h.rootOffset = Header.LENGTH;
      h.rootLength = root.length;
      h.metadataLength = meta.length;
      h.leafDirectoriesLength = dirs.leavesLength();
      fillGeo(h);

      long total =
          outputPath != null
              ? finishFile(h, root, meta, leavesFile)
              : finishStream(h, root, meta, leavesFile, reorder ? planFile : null);
      result =
          new WriteResult(
              addressedTiles,
              h.tileEntries,
              tileContents,
              dataLength,
              root.length,
              dirs.leavesLength(),
              dirs.leafCount(),
              h.clustered,
              total);
      finished = true;
    } catch (IOException | RuntimeException | Error e) {
      failed = true;
      throw e;
    }
    try {
      releaseResources();
    } catch (IOException e) {
      // the archive is complete; close() retries the cleanup and reports the error
    }
    return result;
  }

  /**
   * Single pass over sorted entries: rejects duplicate tiles, merges runs that became adjacent
   * after sorting and, when {@code planFile != null}, assigns clustered offsets (first occurrence
   * in tile ID order wins) and records which staged bytes to copy, in output order.
   */
  private EntryStore.Sorted finalizeEntries(EntryStore.Sorted sorted, Path planFile)
      throws IOException {
    // offset -> -1 until placed, then its new offset; only payloads that repeat are tracked
    Map<Long, Long> placed = planFile == null ? null : readRepeatedOffsets();
    Path out = workDir.resolve("entries-final.bin");
    long count = 0;
    long nextOffset = 0;
    try (EntryCursor c = sorted.open();
        DataOutputStream o = EntryStore.openOut(out, false);
        DataOutputStream plan = planFile == null ? null : EntryStore.openOut(planFile, false)) {
      boolean has = false;
      long t = 0;
      long off = 0;
      int len = 0;
      int run = 0;
      while (c.next()) {
        long ct = c.tileId();
        long co = c.offset();
        int cl = c.length();
        int cr = c.runLength();
        if (has && ct < t + run) {
          throw new IllegalStateException("tile written more than once: " + TileId.toCoord(ct));
        }
        if (plan != null) {
          Long newOffset = placed.get(co);
          if (newOffset == null || newOffset == -1) {
            if (newOffset != null) {
              placed.put(co, nextOffset);
            }
            plan.writeLong(co);
            plan.writeInt(cl);
            co = nextOffset;
            nextOffset += cl;
          } else {
            co = newOffset;
          }
        }
        if (has && ct == t + run && co == off && cl == len && (long) run + cr <= Integer.MAX_VALUE) {
          run += cr;
          continue;
        }
        if (has) {
          EntryStore.writeRecord(o, t, off, len, run);
          count++;
        }
        has = true;
        t = ct;
        off = co;
        len = cl;
        run = cr;
      }
      if (has) {
        EntryStore.writeRecord(o, t, off, len, run);
        count++;
      }
    }
    entries.deleteRuns();
    return new EntryStore.Sorted(List.of(new EntryStore.Run(out, count)), count);
  }

  private Map<Long, Long> readRepeatedOffsets() throws IOException {
    Map<Long, Long> placed = new HashMap<>();
    if (repeatedLog != null) {
      long n = Files.size(repeatedFile) / Long.BYTES;
      try (DataInputStream in = EntryStore.openIn(repeatedFile)) {
        for (long i = 0; i < n; i++) {
          placed.put(in.readLong(), -1L);
        }
      }
    }
    return placed;
  }

  /** Layout: header | root | [metadata] | gap | tile data @16384 | leaves | [metadata]. */
  private long finishFile(Header h, byte[] root, byte[] meta, Path leavesFile) throws IOException {
    long pos = Header.ROOT_REGION + dataLength;
    h.tileDataOffset = Header.ROOT_REGION;
    h.leafDirectoriesOffset = pos;
    data.seek(pos);
    copyFile(leavesFile, h.leafDirectoriesLength, asOutputStream(data));
    pos += h.leafDirectoriesLength;
    if (Header.LENGTH + (long) root.length + meta.length <= Header.ROOT_REGION) {
      h.metadataOffset = Header.LENGTH + root.length; // tuck metadata into the reserved region
    } else {
      h.metadataOffset = pos;
      pos += meta.length;
    }
    writeAt(h.metadataOffset, meta);
    writeAt(Header.LENGTH, root);
    writeAt(0, h.toBytes());
    data.getFD().sync();
    return pos;
  }

  /** Layout: header | root | metadata | leaves | tile data, streamed sequentially. */
  private long finishStream(Header h, byte[] root, byte[] meta, Path leavesFile, Path planFile)
      throws IOException {
    h.metadataOffset = Header.LENGTH + root.length;
    h.leafDirectoriesOffset = h.metadataOffset + meta.length;
    h.tileDataOffset = h.leafDirectoriesOffset + h.leafDirectoriesLength;

    // never closed: the caller owns outputStream
    OutputStream out = new BufferedOutputStream(outputStream, 1 << 16);
    out.write(h.toBytes());
    out.write(root);
    out.write(meta);
    copyFile(leavesFile, h.leafDirectoriesLength, out);
    if (planFile == null) {
      copyData(0, dataLength, out);
    } else {
      try (DataInputStream plan = EntryStore.openIn(planFile)) {
        for (long copied = 0; copied < dataLength; ) {
          long from = plan.readLong();
          int len = plan.readInt();
          copyData(from, len, out);
          copied += len;
        }
      }
    }
    out.flush();
    return h.tileDataOffset + dataLength;
  }

  private void fillGeo(Header h) {
    h.minZoom = minZoom;
    h.maxZoom = maxZoom;
    double[] bounds = config.bounds;
    if (bounds != null) {
      h.minLon = bounds[0];
      h.minLat = bounds[1];
      h.maxLon = bounds[2];
      h.maxLat = bounds[3];
    } else {
      h.minLon = tileLon(minX, maxZoom);
      h.maxLon = tileLon(maxX + 1, maxZoom);
      h.maxLat = tileLat(minY, maxZoom);
      h.minLat = tileLat(maxY + 1, maxZoom);
    }
    double[] center = config.center;
    h.centerLon = center != null ? center[0] : (h.minLon + h.maxLon) / 2;
    h.centerLat = center != null ? center[1] : (h.minLat + h.maxLat) / 2;
    h.centerZoom =
        config.centerZoom != null
            ? Math.max(minZoom, Math.min(maxZoom, config.centerZoom))
            : minZoom;
  }

  private static double tileLon(long x, int z) {
    return x / (double) (1L << z) * 360d - 180d;
  }

  private static double tileLat(long y, int z) {
    double n = Math.PI - 2d * Math.PI * y / (double) (1L << z);
    return Math.toDegrees(Math.atan(Math.sinh(n)));
  }

  // -----------------------------------------------------------------------------------------------
  // lifecycle

  /**
   * Releases resources and deletes temp files. If {@link #finish()} has not completed
   * successfully, an {@code open(Path)} output is deleted. Bytes a failed {@code finish()} already
   * sent to an {@code open(OutputStream)} target cannot be recalled.
   */
  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      releaseResources();
    } finally {
      if (!finished && outputPath != null) {
        Files.deleteIfExists(outputPath);
      }
    }
  }

  /** Idempotent. */
  private void releaseResources() throws IOException {
    try {
      try {
        if (repeatedLog != null) {
          repeatedLog.close();
        }
      } finally {
        data.close();
      }
    } finally {
      deleteDirectory(workDir);
    }
  }

  private void checkWritable() {
    if (closed) {
      throw new IllegalStateException("writer is closed");
    }
    if (finished) {
      throw new IllegalStateException("archive already finished");
    }
    if (failed) {
      throw new IllegalStateException("writer failed earlier; close() it and start over");
    }
  }

  // -----------------------------------------------------------------------------------------------
  // helpers

  private void writeAt(long position, byte[] bytes) throws IOException {
    data.seek(position);
    data.write(bytes);
  }

  /**
   * Copies {@code length} bytes of staged tile data starting at {@code position}. Uses positional
   * channel reads (one syscall instead of seek + read per tile when reordering), so interrupting
   * the thread inside {@link #finish()} aborts it; producer threads are unaffected.
   */
  private void copyData(long position, long length, OutputStream out) throws IOException {
    while (length > 0) {
      int n = (int) Math.min(buffer.length, length);
      ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, n);
      while (chunk.hasRemaining()) {
        if (data.getChannel().read(chunk, position + chunk.position()) < 0) {
          throw new IOException("unexpected end of staged tile data");
        }
      }
      out.write(buffer, 0, n);
      position += n;
      length -= n;
    }
  }

  private static void copyFile(Path file, long length, OutputStream out) throws IOException {
    if (length > 0) { // leaves.bin only exists if leaf directories were built
      try (InputStream in = new FileInputStream(file.toFile())) {
        in.transferTo(out);
      }
    }
  }

  private static OutputStream asOutputStream(RandomAccessFile file) {
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        file.write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        file.write(b, off, len);
      }
    };
  }

  /** The work directory is flat: spill files only. */
  private static void deleteDirectory(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
      for (Path f : files) {
        Files.deleteIfExists(f);
      }
    }
    Files.deleteIfExists(dir);
  }

  private static String validateJson(String json) {
    String s = Objects.requireNonNull(json, "metadata").strip();
    if (!s.startsWith("{") || !s.endsWith("}")) {
      throw new IllegalArgumentException("metadata must be a JSON object");
    }
    return s;
  }

  // -----------------------------------------------------------------------------------------------
  // builder

  public static final class Builder {
    private TileType tileType = TileType.UNKNOWN;
    private Compression tileCompression = Compression.UNKNOWN;
    private Compression internalCompression = Compression.GZIP;
    private final Map<Compression, Codec> codecs = new EnumMap<>(Compression.class);
    private String metadata = "{}";
    private double[] bounds;
    private double[] center;
    private Integer centerZoom;
    private Path tempDirectory;
    private int dedupCacheEntries = 100_000;
    private int sortChunkEntries = 1 << 20;

    private Builder() {
      codecs.put(Compression.NONE, Codec.none());
      codecs.put(Compression.GZIP, Codec.gzip());
    }

    /** Snapshot, so reusing the builder after open() cannot change a running writer. */
    private Builder copy() {
      Builder b = new Builder();
      b.tileType = tileType;
      b.tileCompression = tileCompression;
      b.internalCompression = internalCompression;
      b.codecs.putAll(codecs);
      b.metadata = metadata;
      b.bounds = bounds;
      b.center = center;
      b.centerZoom = centerZoom;
      b.tempDirectory = tempDirectory;
      b.dedupCacheEntries = dedupCacheEntries;
      b.sortChunkEntries = sortChunkEntries;
      return b;
    }

    public Builder tileType(TileType type) {
      this.tileType = Objects.requireNonNull(type);
      return this;
    }

    /** Declares how tile payloads are already compressed. The writer never re-encodes tiles. */
    public Builder tileCompression(Compression compression) {
      this.tileCompression = Objects.requireNonNull(compression);
      return this;
    }

    /** Compression for directories and metadata: {@code NONE} or {@code GZIP} (default). */
    public Builder internalCompression(Compression compression) {
      if (compression == Compression.UNKNOWN) {
        throw new IllegalArgumentException("internal compression must be known");
      }
      this.internalCompression = compression;
      return this;
    }

    /** Internal compression with a user-supplied codec, e.g. zstd or brotli. */
    public Builder internalCompression(Compression compression, Codec codec) {
      internalCompression(compression);
      codecs.put(compression, Objects.requireNonNull(codec));
      return this;
    }

    /** JSON metadata object. For MVT the spec requires a {@code vector_layers} key. */
    public Builder metadata(String json) {
      this.metadata = validateJson(json);
      return this;
    }

    /** Header bounds in WGS84 degrees. Defaults to the extent of tiles at the max zoom. */
    public Builder bounds(double minLon, double minLat, double maxLon, double maxLat) {
      this.bounds = new double[] {minLon, minLat, maxLon, maxLat};
      return this;
    }

    /** Header center. Defaults to the middle of the bounds at the min zoom. */
    public Builder center(double lon, double lat, int zoom) {
      this.center = new double[] {lon, lat};
      this.centerZoom = zoom;
      return this;
    }

    /** Directory for spill files. Defaults to the output's directory, or java.io.tmpdir. */
    public Builder tempDirectory(Path dir) {
      this.tempDirectory = dir;
      return this;
    }

    /**
     * Number of distinct tile hashes kept for deduplication (LRU). {@code 0} disables it. Each
     * entry costs roughly 120 bytes of heap. Default 100,000.
     */
    public Builder dedupCacheEntries(int entries) {
      if (entries < 0) {
        throw new IllegalArgumentException("entries < 0");
      }
      this.dedupCacheEntries = entries;
      return this;
    }

    /**
     * Directory entries held in memory before spilling to disk (24 bytes each). Default 1,048,576
     * (about 24 MiB).
     */
    public Builder sortChunkEntries(int entries) {
      if (entries < 2) {
        throw new IllegalArgumentException("entries < 2");
      }
      this.sortChunkEntries = entries;
      return this;
    }

    /** Writes directly into a file. Existing content is overwritten. */
    public PmtilesWriter open(Path output) throws IOException {
      validate();
      return new PmtilesWriter(this, Objects.requireNonNull(output), null);
    }

    /**
     * Writes to a non-seekable stream. Nothing is written until {@link #finish()}; the stream is
     * flushed but not closed. If {@code finish()} fails part-way, the stream may hold a truncated
     * archive, so abort the upload or response in that case.
     */
    public PmtilesWriter open(OutputStream output) throws IOException {
      validate();
      return new PmtilesWriter(this, null, Objects.requireNonNull(output));
    }

    private void validate() {
      if (!codecs.containsKey(internalCompression)) {
        throw new IllegalStateException(
            "no codec for internal compression "
                + internalCompression
                + "; use internalCompression(compression, codec)");
      }
    }
  }
}
