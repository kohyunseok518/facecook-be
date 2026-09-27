package com.facecook.cook.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 콕 설정값({@link CookProperties}, {@link CookSendLimitProperties})을 빈으로 등록한다. */
@Configuration
@EnableConfigurationProperties({CookProperties.class, CookSendLimitProperties.class})
public class CookConfig {
}
