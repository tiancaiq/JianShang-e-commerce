package com.msb.ecom.auth_service.analytics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
public class AnalyticsExecutorConfig {
    @Bean(name = "analyticsExecutor", destroyMethod = "close")
    ExecutorService analyticsExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
