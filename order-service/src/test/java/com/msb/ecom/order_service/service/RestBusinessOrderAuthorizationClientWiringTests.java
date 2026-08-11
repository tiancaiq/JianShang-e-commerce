package com.msb.ecom.order_service.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(classes = {
        RestBusinessOrderAuthorizationClient.class,
        RestBusinessOrderAuthorizationClientWiringTests.TestConfig.class
})
@TestPropertySource(properties = "service.auth.url=http://auth.test")
class RestBusinessOrderAuthorizationClientWiringTests {

    @Autowired
    RestBusinessOrderAuthorizationClient client;

    @Test
    void springSelectsProductionConstructorAndCreatesAuthorizationClient() {
        assertThat(client).isNotNull();
    }

    @Configuration
    static class TestConfig {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }
}
