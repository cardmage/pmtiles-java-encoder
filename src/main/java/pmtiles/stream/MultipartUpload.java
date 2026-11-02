package pmtiles.stream;

import java.io.IOException;

/**
 * Target for {@link PmtilesWriter.Builder#open(MultipartUpload, int)}: an object store upload
 * whose parts can be sent in any order and are assembled by part number, such as an S3 multipart
 * upload. Implement it with your storage SDK; the library has no dependency on one.
 *
 * <p>Tile data is uploaded while it is written, in parts 2, 3, ... Part 1 holds the header and root
 * directory, which are only known at the end, so it is uploaded last, just before {@link
 * #complete()}. Every part except the highest-numbered one is exactly {@code partSize} bytes.
 *
 * <p>Calls come from whichever thread is writing tiles or calling {@code finish()}, one at a time.
 */
public interface MultipartUpload {

  /**
   * Uploads one part. {@code data} is reused after this method returns, so copy it or finish
   * sending it before returning.
   *
   * @param partNumber 1-based; at most 10,000
   * @param data part bytes in {@code data[0..length)}
   */
  void uploadPart(int partNumber, byte[] data, int length) throws IOException;

  /** Assembles the uploaded parts into the final object. Called once, after all parts. */
  void complete() throws IOException;

  /**
   * Discards the upload. Called when the writer is closed without a successful {@code finish()},
   * so no partial archive becomes visible.
   */
  void abort() throws IOException;
}
