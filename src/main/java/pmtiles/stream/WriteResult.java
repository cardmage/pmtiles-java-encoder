package pmtiles.stream;

/**
 * Summary of a finished archive.
 *
 * @param addressedTiles number of tiles written (before run-length encoding)
 * @param tileEntries number of directory entries after run-length encoding
 * @param tileContents number of distinct tile payloads stored after deduplication
 * @param tileDataBytes size of the tile data section
 * @param rootDirectoryBytes compressed size of the root directory
 * @param leafDirectoryBytes total compressed size of all leaf directories
 * @param leafDirectories number of leaf directories
 * @param clustered whether tile data is laid out in tile ID order
 * @param totalBytes total archive size
 */
public record WriteResult(
    long addressedTiles,
    long tileEntries,
    long tileContents,
    long tileDataBytes,
    int rootDirectoryBytes,
    long leafDirectoryBytes,
    int leafDirectories,
    boolean clustered,
    long totalBytes) {}
