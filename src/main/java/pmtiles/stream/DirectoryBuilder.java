package pmtiles.stream;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * Builds the root directory and (if needed) one level of leaf directories from sorted entries,
 * writing leaves to a file so their total size never has to fit in memory.
 *
 * <p>Strategy follows go-pmtiles: try a single root directory when there are fewer than 16384
 * entries; otherwise start with {@code max(4096, n / 3500)} entries per leaf and grow the leaf
 * size by 20% until the compressed root fits in {@code 16384 - 127} bytes.
 */
final class DirectoryBuilder {

  record Result(byte[] root, long leavesLength, int leafCount) {}

  private DirectoryBuilder() {}

  static Result build(EntryStore.Sorted entries, Codec codec, Path leavesFile) throws IOException {
    long n = entries.count();
    if (n < 16384) {
      Directory all = new Directory((int) n);
      try (EntryCursor c = entries.open()) {
        while (c.next()) {
          all.add(c.tileId(), c.offset(), c.length(), c.runLength());
        }
      }
      byte[] root = codec.compress(all.encode());
      if (root.length <= Header.MAX_ROOT_DIRECTORY_LENGTH) {
        return new Result(root, 0, 0);
      }
    }
    for (double leafSize = Math.max(4096d, n / 3500d); ; leafSize *= 1.2) {
      Result r = buildWithLeaves(entries, codec, leavesFile, (int) Math.min(leafSize, n));
      if (r.root.length <= Header.MAX_ROOT_DIRECTORY_LENGTH) {
        return r;
      }
      if (leafSize >= n) {
        throw new IllegalStateException("root directory does not fit even with a single leaf");
      }
    }
  }

  private static Result buildWithLeaves(
      EntryStore.Sorted entries, Codec codec, Path leavesFile, int leafSize) throws IOException {
    Directory root = new Directory(1024);
    Directory leaf = new Directory(Math.min(leafSize, 1 << 16), leafSize);
    long leafOffset = 0;
    try (EntryCursor c = entries.open();
        OutputStream out =
            new BufferedOutputStream(new FileOutputStream(leavesFile.toFile()), 1 << 16)) {
      while (c.next()) {
        leaf.add(c.tileId(), c.offset(), c.length(), c.runLength());
        if (leaf.size == leafSize) {
          leafOffset += flushLeaf(leaf, root, leafOffset, codec, out);
        }
      }
      if (leaf.size > 0) {
        leafOffset += flushLeaf(leaf, root, leafOffset, codec, out);
      }
    }
    return new Result(codec.compress(root.encode()), leafOffset, root.size);
  }

  private static int flushLeaf(
      Directory leaf, Directory root, long leafOffset, Codec codec, OutputStream out)
      throws IOException {
    byte[] bytes = codec.compress(leaf.encode());
    out.write(bytes);
    // run length 0 marks a pointer to a leaf directory; offset is relative to the leaf section
    root.add(leaf.tileIds[0], leafOffset, bytes.length, 0);
    leaf.clear();
    return bytes.length;
  }
}
