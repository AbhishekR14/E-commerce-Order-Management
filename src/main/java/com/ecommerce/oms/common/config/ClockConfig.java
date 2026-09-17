package com.ecommerce.oms.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of "now". Services inject {@link Clock} instead of calling {@code Instant.now()}
 * so that tests can substitute a fixed or mutable clock (e.g. for the return window).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
