package pmtiles.stream;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Streaming PMTiles v3 writer with bounded memory.
 *
 * <p>Tile bytes go to their final place in the archive as they arrive; directory entries are
 * buffered in a fixed-size chunk and spilled to temporary files, so heap usage does not grow with
 * the number of tiles. No MBTiles/SQLite intermediate and no staging copy of the tile data.
 *
 * <h2>Layout and targets</h2>
 *
 * <pre>
 * | header | root | zero padding | tile data ... | leaf dirs | metadata |
 * 0        127                   16384
 * </pre>
 *
 * Tile data starts at byte 16384 and is written while tiles arrive. On {@link #finish()} the leaf
 * directories and metadata are appended, then the first 16 KiB (header + root) is written last.
 * Each archive byte is written exactly once. The length is {@code 16384 + tiles + leaves +
 * metadata}, which {@code pmtiles verify} (go-pmtiles) accepts.
 *
 * <ul>
 *   <li>{@link Builder#open(Path)} &mdash; a local file; the head is written at offset 0 last.
 *   <li>{@link Builder#open(MultipartUpload, int)} &mdash; an object store multipart upload (e.g.
 *       S3). Parts 2, 3, ... are uploaded on a background thread as they fill; part 1 (head + first
 *       tile bytes) is uploaded last. Memory: three parts.
 * </ul>
 *
 * <h2>Ordering</h2>
 *
 * Tiles may be written in any order. Writing in ascending tile ID order (see {@link TileId}) is
 * cheapest and yields a clustered archive. Out-of-order input is sorted with an external merge
 * sort; the archive is then valid but marked not clustered, because tile data is never moved.
 *
 * <h2>Lifecycle and failures</h2>
 *
 * Call {@link #finish()} to complete the archive. {@link #close()} without a successful {@code
 * finish()} deletes the output file or aborts the multipart upload, so an exception inside
 * try-with-resources never leaves a broken archive behind.
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
 * Interrupting a producer thread (e.g. a cancelled task) does not break the archive: files are
 * written with {@code java.io} streams, which unlike NIO channels are not closed by an interrupt,
 * and multipart parts are uploaded on the writer's own thread.
 */
public final class PmtilesWriter implements Closeable {

  /** S3's minimum size for every part except the last. */
  static final int MIN_PART_SIZE = 5 << 20;

  /** Three parts are held in memory, so keep them well below the array size limit. */
  static final int MAX_PART_SIZE = 1 << 30;

  private final Builder config;
  private final Codec codec;
  private final Path workDir;
  private final EntryStore entries;
  private final DedupCache dedup;
  private final Sink sink;

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

  private interface SinkFactory {
    Sink open() throws IOException;
  }

  private PmtilesWriter(Builder b, Path tempParent, SinkFactory sinkFactory) throws IOException {
    this.config = b.copy();
    this.codec = config.codecs.get(config.internalCompression);
    this.metadataJson = config.metadata;
    if (config.tempDirectory != null) {
      tempParent = config.tempDirectory;
    }
    this.workDir =
        tempParent == null
            ? Files.createTempDirectory("pmtiles-writer-")
            : Files.createTempDirectory(tempParent, ".pmtiles-writer-");
    try {
      this.entries = new EntryStore(workDir, config.sortChunkEntries);
      this.dedup = config.dedupCacheEntries > 0 ? new DedupCache(config.dedupCacheEntries) : null;
      // opened last, so nothing can fail after the output file has been truncated
      this.sink = sinkFactory.open();
    } catch (IOException | RuntimeException | Error e) {
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
        Long hit = key == null ? null : dedup.get(key);
        long offset;
        if (hit != null) {
          offset = hit;
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

  /** Appends at the end of the tile data; the sink does its own buffering. */
  private void append(byte[] tile) throws IOException {
    sink.write(tile, 0, tile.length);
    dataLength += tile.length;
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

      boolean ordered = entries.isOrdered();
      EntryStore.Sorted sorted = entries.sorted();
      EntryStore.Sorted finalEntries = ordered ? sorted : finalizeEntries(sorted);

      Path leavesFile = workDir.resolve("leaves.bin");
      DirectoryBuilder.Result dirs = DirectoryBuilder.build(finalEntries, codec, leavesFile);
      byte[] root = dirs.root();
      byte[] meta = codec.compress(metadataJson.getBytes(StandardCharsets.UTF_8));

      Header h = new Header();
      h.internalCompression = config.internalCompression;
      h.tileCompression = config.tileCompression;
      h.tileType = config.tileType;
      h.clustered = ordered;
      h.addressedTiles = addressedTiles;
      h.tileEntries = finalEntries.count();
      h.tileContents = tileContents;
      h.rootOffset = Header.LENGTH;
      h.rootLength = root.length;
      h.tileDataOffset = Header.ROOT_REGION;
      h.tileDataLength = dataLength;
      h.leafDirectoriesOffset = h.tileDataOffset + dataLength;
      h.leafDirectoriesLength = dirs.leavesLength();
      h.metadataOffset = h.leafDirectoriesOffset + h.leafDirectoriesLength;
      h.metadataLength = meta.length;
      fillGeo(h);

      copyFile(leavesFile, h.leafDirectoriesLength);
      sink.write(meta, 0, meta.length);
      byte[] head = new byte[Header.ROOT_REGION];
      System.arraycopy(h.toBytes(), 0, head, 0, Header.LENGTH);
      System.arraycopy(root, 0, head, Header.LENGTH, root.length);
      sink.finish(head);
      long total = h.metadataOffset + meta.length;
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
      deleteDirectory(workDir);
    } catch (IOException e) {
      // the archive is complete; close() retries the cleanup and reports the error
    }
    return result;
  }

  /**
   * Single pass over sorted entries: rejects duplicate tiles and merges runs that became adjacent
   * after sorting. Offsets are unchanged; tile data is never moved.
   */
  private EntryStore.Sorted finalizeEntries(EntryStore.Sorted sorted) throws IOException {
    Path out = workDir.resolve("entries-final.bin");
    long count = 0;
    try (EntryCursor c = sorted.open();
        DataOutputStream o = EntryStore.openOut(out, false)) {
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
   * successfully, the output file is deleted or the multipart upload is aborted.
   */
  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      if (!finished) {
        sink.abort();
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

  /** Appends the leaf directories file to the archive. */
  private void copyFile(Path file, long length) throws IOException {
    if (length > 0) { // leaves.bin only exists if leaf directories were built
      byte[] buffer = new byte[1 << 16];
      try (InputStream in = new FileInputStream(file.toFile())) {
        int n;
        while ((n = in.read(buffer)) > 0) {
          sink.write(buffer, 0, n);
        }
      }
    }
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

    /**
     * Directory for spill files (24 bytes per directory entry, plus leaf directories; never tile
     * data). Defaults to the output file's directory, or java.io.tmpdir for multipart uploads.
     */
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

    /**
     * Writes directly into a file. Existing content is overwritten. Spill files go next to it
     * unless {@link #tempDirectory} is set.
     */
    public PmtilesWriter open(Path output) throws IOException {
      validate();
      Objects.requireNonNull(output);
      return new PmtilesWriter(
          this, output.toAbsolutePath().getParent(), () -> new Sink.FileSink(output));
    }

    /**
     * Uploads to an object store multipart upload (e.g. S3) while tiles are written; no local copy
     * of the tile data. The writer owns {@code upload} from here on: it is completed by {@link
     * #finish()} and aborted by {@link #close()} without a successful finish, or by this method
     * if opening fails.
     *
     * <p>Uploads run on a dedicated thread, so producers are not blocked by the network unless
     * they outpace it. Holds three parts in memory.
     *
     * @param partSize bytes per part, from 5 MiB (S3's minimum) to 1 GiB. The archive can have at
     *     most 10,000 parts, so the maximum size is {@code 10,000 × partSize}: 16 MiB parts allow
     *     about 160 GB.
     */
    public PmtilesWriter open(MultipartUpload upload, int partSize) throws IOException {
      return openMultipart(upload, partSize, MIN_PART_SIZE);
    }

    /** {@link #open(MultipartUpload, int)} with a lower minimum, so tests can use tiny parts. */
    PmtilesWriter openMultipart(MultipartUpload upload, int partSize, int minPartSize)
        throws IOException {
      Objects.requireNonNull(upload);
      try {
        validate();
        if (partSize < Math.max(minPartSize, Header.ROOT_REGION + 1)
            || partSize > MAX_PART_SIZE) {
          throw new IllegalArgumentException(
              "partSize must be between " + minPartSize + " and " + MAX_PART_SIZE);
        }
        return new PmtilesWriter(this, null, () -> new Sink.MultipartSink(upload, partSize));
      } catch (IOException | RuntimeException | Error e) {
        try {
          upload.abort(); // nothing else will: no writer was returned to close
        } catch (IOException | RuntimeException abortError) {
          e.addSuppressed(abortError);
        }
        throw e;
      }
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
