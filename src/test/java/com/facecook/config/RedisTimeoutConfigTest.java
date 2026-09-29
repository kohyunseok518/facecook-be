package com.facecook.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml의 Redis 명령 대기 시간(#147)을 스프링이 읽는 방식 그대로 {@link RedisProperties}에 바인딩해 확인한다.
 * 이 값이 비면 Redis가 대답 없이 멈췄을 때 채팅 처리 스레드가 오래 붙잡힌다.
 */
class RedisTimeoutConfigTest {

    @Test
    void waitsAtMostOneSecondForARedisCommand() throws IOException {
        RedisProperties properties = bind(Map.of());

        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void environmentVariableOverridesTheTimeout() throws IOException {
        RedisProperties properties = bind(Map.of("REDIS_TIMEOUT", "2s"));

        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    private static RedisProperties bind(Map<String, Object> environment) throws IOException {
        List<PropertySource<?>> sources = new ArrayList<>();
        sources.add(new MapPropertySource("environment", environment));
        sources.addAll(new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")));
        Binder binder = new Binder(
                ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources)
        );
        return binder.bind("spring.data.redis", Bindable.of(RedisProperties.class)).orElseGet(RedisProperties::new);
    }
}
