package com.fleettracking.gateway;

import com.fleettracking.gateway.identity.IdentityProperties;
import com.fleettracking.gateway.identity.IdentityResolver;
import com.fleettracking.gateway.identity.StaticIdentityResolver;
import com.fleettracking.gateway.normalize.Edi214Normalizer;
import com.fleettracking.gateway.normalize.MobileAppNormalizer;
import com.fleettracking.gateway.normalize.Normalizer;
import com.fleettracking.gateway.normalize.ReeferNormalizer;
import com.fleettracking.gateway.normalize.TelematicsNormalizer;
import com.fleettracking.gateway.publish.EventPublisher;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.util.List;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wires the gateway.
 *
 * <p>Explicit {@code @Bean} methods rather than {@code @Component} scanning, following the same
 * reasoning as the simulator's emission config: the order things wrap in is the design, and one
 * file showing the whole path a message takes beats a scanning puzzle when something fails to
 * appear at the far end.
 */
@Configuration
@EnableConfigurationProperties({GatewayProperties.class, IdentityProperties.class})
public class GatewayConfig {

  private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

  /**
   * Real wall-clock time, injected rather than called statically so a test can fix it.
   *
   * <p>Unlike the simulator, this service is entitled to ask what time it is: {@code receivedAt} is
   * a statement about when this platform genuinely heard something, and no amount of time-scaling
   * changes that.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * A validator built by hand rather than taken from auto-configuration.
   *
   * <p>Hibernate Validator's default message interpolator needs a Jakarta Expression Language
   * implementation, and there is none on this classpath — the Spring Boot BOM manages no EL
   * dependency and adding one to render error messages nobody reads would be a strange trade.
   * {@link ParameterMessageInterpolator} substitutes constraint parameters without EL. Messages
   * containing an EL fragment come out with the fragment intact and a warning, which is a
   * cosmetic problem in a string that only ever reaches a dead-letter record.
   */
  @Bean
  public Validator validator() {
    ValidatorFactory factory =
        Validation.byProvider(HibernateValidator.class)
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory();
    return factory.getValidator();
  }

  @Bean
  public IdentityResolver identityResolver(IdentityProperties properties) {
    StaticIdentityResolver resolver = new StaticIdentityResolver(properties.assignments());
    log.info("identity reference data loaded: {} vehicles assigned", resolver.size());
    return resolver;
  }

  @Bean
  public TelematicsNormalizer telematicsNormalizer(IdentityResolver identities) {
    return new TelematicsNormalizer(identities);
  }

  @Bean
  public MobileAppNormalizer mobileAppNormalizer(IdentityResolver identities) {
    return new MobileAppNormalizer(identities);
  }

  @Bean
  public Edi214Normalizer edi214Normalizer(IdentityResolver identities) {
    return new Edi214Normalizer(identities);
  }

  @Bean
  public ReeferNormalizer reeferNormalizer(IdentityResolver identities) {
    return new ReeferNormalizer(identities);
  }

  @Bean
  public EventPublisher eventPublisher(
      KafkaTemplate<String, String> kafka, GatewayProperties properties) {
    return new EventPublisher(kafka, properties.sendTimeout().toMillis());
  }

  /**
   * Every normalizer on the classpath is collected here by type.
   *
   * <p>S7 added the other three feeds and this method was not touched, which is the point of
   * collecting them by type rather than naming them one by one. A fifth feed is a fifth bean.
   */
  @Bean
  public IngestService ingestService(
      List<Normalizer> normalizers, Validator validator, EventPublisher publisher) {
    log.info(
        "gateway handles {} of {} feeds: {}",
        normalizers.size(),
        com.fleettracking.events.SourceSystem.values().length,
        normalizers.stream().map(Normalizer::source).toList());
    return new IngestService(normalizers, validator, publisher);
  }
}
