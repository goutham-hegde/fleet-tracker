package com.fleettracking.publicview;

import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The two AWS clients, built the way a Lambda should build them.
 *
 * <p>Everything the SDK would otherwise discover is stated instead, because discovery costs time on
 * the request that woke the function. The runtime puts the role's temporary credentials in
 * environment variables and names the region in {@code AWS_REGION}, so the default provider chain,
 * which tries several sources in turn and can probe the instance metadata endpoint, is replaced by
 * the one source that is known to be there. The HTTP stack is the JDK's own, as in the archiver.
 *
 * <p>Built once per execution environment, in the handler's constructor. Lambda runs that during
 * the init phase, which gets a full CPU regardless of the memory setting, and every later invocation
 * in the same environment reuses the connections.
 */
public final class Clients {

  private Clients() {}

  public static Region region() {
    String region = System.getenv("AWS_REGION");
    return region == null ? Region.AP_SOUTH_1 : Region.of(region);
  }

  public static DynamoDbClient dynamo() {
    return DynamoDbClient.builder()
        .region(region())
        .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
        .httpClient(UrlConnectionHttpClient.create())
        .build();
  }

  public static S3Client s3() {
    return S3Client.builder()
        .region(region())
        .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
        .httpClient(UrlConnectionHttpClient.create())
        .build();
  }

  /** A required setting from the function's environment, which Terraform writes. */
  public static String env(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is not set in the function's environment");
    }
    return value;
  }
}
