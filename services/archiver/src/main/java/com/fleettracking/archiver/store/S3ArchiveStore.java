package com.fleettracking.archiver.store;

import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The archive in S3.
 *
 * <p>{@code Content-Type: application/gzip} and no {@code Content-Encoding}. Setting the encoding to
 * gzip is the usual way to serve a compressed file, and it invites every HTTP client along the way
 * (a browser, curl with the right flag, some SDKs) to decompress it silently, so that what arrives
 * no longer matches its own name. These are files that happen to be gzip, not documents that were
 * compressed for the trip.
 */
public final class S3ArchiveStore implements ArchiveStore {

  private final S3Client s3;
  private final String bucket;

  public S3ArchiveStore(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public void put(String key, byte[] body) {
    s3.putObject(
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType("application/gzip")
            .build(),
        RequestBody.fromBytes(body));
  }

  @Override
  public List<String> list(String prefix) {
    List<String> keys = new ArrayList<>();
    String token = null;
    do {
      ListObjectsV2Response page =
          s3.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket)
                  .prefix(prefix)
                  .continuationToken(token)
                  .build());
      page.contents().stream().map(S3Object::key).forEach(keys::add);
      token = page.isTruncated() ? page.nextContinuationToken() : null;
    } while (token != null);
    keys.sort(null);
    return keys;
  }

  @Override
  public byte[] get(String key) {
    return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
  }

  @Override
  public String describe() {
    return "s3://" + bucket;
  }
}
