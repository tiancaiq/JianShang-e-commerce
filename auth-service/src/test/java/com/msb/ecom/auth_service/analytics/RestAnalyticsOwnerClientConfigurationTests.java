package com.msb.ecom.auth_service.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.lang.reflect.Constructor;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class RestAnalyticsOwnerClientConfigurationTests {

    @Test
    void localOwnerDefaultsMatchTheServicesAndEnvironmentExample() throws Exception {
        Constructor<?> constructor = RestAnalyticsOwnerClient.class.getConstructors()[0];
        assertThat(constructor.getParameters()[1].getAnnotation(Value.class).value())
                .isEqualTo("${service.order.url:http://localhost:8081}");
        assertThat(constructor.getParameters()[3].getAnnotation(Value.class).value())
                .isEqualTo("${service.payment.url:http://localhost:8084}");

        Properties properties = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));
        assertThat(properties.getProperty("service.order.url"))
                .isEqualTo("${ORDER_SERVICE_URL:http://localhost:8081}");
        assertThat(properties.getProperty("service.payment.url"))
                .isEqualTo("${PAYMENT_SERVICE_URL:http://localhost:8084}");
    }
}
