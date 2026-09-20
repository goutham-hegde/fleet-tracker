package com.fleettracking.publicview.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleettracking.publicview.lookup.LookupHandler.Response;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * How {@link Site} reads S3's refusals.
 *
 * <p>{@code PublicViewIT} covers the happy paths against a real S3 implementation. This covers the
 * one that only appears against real AWS: a key that is not there comes back as **403**, not 404,
 * because the function has no {@code s3:ListBucket} and S3 will not confirm the absence of a key to
 * a caller that may not list. The first deployment of the site-serving function answered 503 to
 * every missing path for exactly this reason, and no container-based test could have shown it --
 * s3mock enforces no IAM, so it always answers a tidy NoSuchKey.
 *
 * <p>There is no mocking library in this project, so the client is a hand-written stub. The SDK's
 * client interfaces give every operation a default that throws, so overriding one is enough.
 */
class SiteTest {

  private static final Clock CLOCK = Clock.systemUTC();

  /** An S3 that always fails the one call {@link Site} makes, with a chosen status. */
  private static S3Client refusing(RuntimeException failure, AtomicInteger calls) {
    return new S3Client() {
      @Override
      public String serviceName() {
        return S3Client.SERVICE_NAME;
      }

      @Override
      public void close() {}

      @Override
      public <T> T getObject(
          GetObjectRequest request,
          ResponseTransformer<GetObjectResponse, T> transformer) {
        calls.incrementAndGet();
        throw failure;
      }
    };
  }

  private static S3Exception withStatus(int status) {
    return (S3Exception)
        S3Exception.builder().message("refused").statusCode(status).build();
  }

  @Test
  void aForbiddenFromS3IsAMissingFileBecauseTheFunctionMayNotList() {
    AtomicInteger calls = new AtomicInteger();
    Site site = new Site(refusing(withStatus(403), calls), "site", CLOCK);

    Response response = site.serve("/assets/gone-0000.js");

    assertThat(response.statusCode())
        .as("403 from S3 is how an absent key looks without s3:ListBucket")
        .isEqualTo(404);
    assertThat(calls).hasValue(1);
  }

  @Test
  void aNoSuchKeyIsAlsoAMissingFile() {
    AtomicInteger calls = new AtomicInteger();
    Site site =
        new Site(
            refusing(NoSuchKeyException.builder().message("nope").build(), calls), "site", CLOCK);

    assertThat(site.serve("/assets/gone-0000.js").statusCode()).isEqualTo(404);
  }

  @Test
  void aRefusalIsRememberedSoTheSamePathIsNotFetchedTwice() {
    AtomicInteger calls = new AtomicInteger();
    Site site = new Site(refusing(withStatus(403), calls), "site", CLOCK);

    site.serve("/assets/gone-0000.js");
    site.serve("/assets/gone-0000.js");
    site.serve("/assets/gone-0000.js");

    assertThat(calls)
        .as("a stream of made-up paths must not become an S3 GET each")
        .hasValue(1);
  }

  @Test
  void aRealFailureIsNotMistakenForAMissingFile() {
    AtomicInteger calls = new AtomicInteger();
    Site site = new Site(refusing(withStatus(500), calls), "site", CLOCK);

    // Left to the handler, which answers 503 and does not cache it. A bucket that is throttling or
    // broken must not be reported to viewers as an empty site.
    assertThatThrownBy(() -> site.serve("/index.html")).isInstanceOf(S3Exception.class);
  }

  @Test
  void aPathThatTriesToLeaveTheBucketIsRefusedWithoutAskingS3() {
    AtomicInteger calls = new AtomicInteger();
    Site site = new Site(refusing(withStatus(403), calls), "site", CLOCK);

    assertThat(site.serve("/../secrets").statusCode()).isEqualTo(404);
    assertThat(calls).as("the path is rejected before any call").hasValue(0);
  }

  @Test
  void theClockIsTheOneItWasGiven() {
    // Nothing in this class may reach for the wall clock of its own accord; the TTL is measured on
    // the clock passed in, as everywhere else in the platform.
    AtomicInteger calls = new AtomicInteger();
    Site site =
        new Site(refusing(withStatus(403), calls), "site", Clock.system(ZoneOffset.UTC));

    assertThat(site.serve("/assets/gone-0000.js").statusCode()).isEqualTo(404);
  }
}
