package com.msb.ecom.auth_service.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** Provides one UTC time source so time-sensitive rules remain deterministic in tests. */
    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
