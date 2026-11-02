package pmtiles.stream;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded LRU map from tile content hash to its (offset, length) in the tile data section.
 *
 * <p>Repeated tiles in real tilesets (ocean, empty land, solid colors) are few distinct payloads
 * repeated millions of times, so a small LRU keeps all of them hot while bounding memory. A
 * 128-bit truncated SHA-256 is used as the key; accidental collisions are not a practical concern.
 */
final class DedupCache {

  record Key(long hi, long lo, int length) {}

  private static final ThreadLocal<MessageDigest> SHA256 =
      ThreadLocal.withInitial(
          () -> {
            try {
              return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
              throw new IllegalStateException(e);
            }
          });

  private final LinkedHashMap<Key, Long> map;

  DedupCache(int maxEntries) {
    this.map =
        new LinkedHashMap<>(Math.min(maxEntries, 1 << 16) * 2, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<Key, Long> eldest) {
            return size() > maxEntries;
          }
        };
  }

  /** Thread-safe; meant to be called outside the writer lock. */
  static Key key(byte[] data) {
    MessageDigest md = SHA256.get();
    byte[] h = md.digest(data);
    long hi = 0;
    long lo = 0;
    for (int i = 0; i < 8; i++) {
      hi = (hi << 8) | (h[i] & 0xFF);
      lo = (lo << 8) | (h[i + 8] & 0xFF);
    }
    return new Key(hi, lo, data.length);
  }

  /** Offset of identical content already written, or null. */
  Long get(Key key) {
    return map.get(key);
  }

  void put(Key key, long offset) {
    map.put(key, offset);
  }
}
