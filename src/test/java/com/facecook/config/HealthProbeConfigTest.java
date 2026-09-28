package com.facecook.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml의 헬스체크 설정(facecook-be#145)을 확인한다.
 *
 * <p>ALB는 {@code /actuator/health/liveness}를 본다. DB·Redis가 멈춰도 liveness는 UP이어야 두 서버가 함께
 * 교체되지 않는다. 배포 스크립트가 보는 {@code /actuator/health}는 지금처럼 DB 상태를 반영해야 한다.
 * DB·Redis 대신 항상 DOWN인 {@code db} 지표를 넣어 멈춘 상황을 흉내 낸다.</p>
 */
class HealthProbeConfigTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(context -> applicationYml().forEach(
                    source -> context.getEnvironment().getPropertySources().addLast(source)))
            .withConfiguration(AutoConfigurations.of(
                    ApplicationAvailabilityAutoConfiguration.class,
                    AvailabilityHealthContributorAutoConfiguration.class,
                    AvailabilityProbesAutoConfiguration.class,
                    EndpointAutoConfiguration.class,
                    HealthContributorAutoConfiguration.class,
                    HealthEndpointAutoConfiguration.class))
            .withBean("db", HealthIndicator.class, () -> () -> Health.down().build());

    @Test
    void livenessStaysUpWhenDatabaseIsDown() {
        runner.run(context -> {
            // 실제 앱은 시작이 끝나면 SpringApplication이 CORRECT를 발행한다. 컨텍스트 러너는 발행하지 않아 직접 맞춘다.
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);

            HealthComponent liveness = context.getBean(HealthEndpoint.class).healthForPath("liveness");

            assertThat(liveness).isNotNull();
            assertThat(liveness.getStatus()).isEqualTo(Status.UP);
        });
    }

    @Test
    void fullHealthStillReflectsDatabase() {
        runner.run(context -> {
            HealthComponent health = context.getBean(HealthEndpoint.class).health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        });
    }

    private static List<PropertySource<?>> applicationYml() {
        try {
            return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
