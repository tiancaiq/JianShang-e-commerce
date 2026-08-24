package com.msb.ecom.api_gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
public class GatewayHttpClientConfig {

    // Preserve established proxy behavior while using a PATCH-capable HTTP/1.1 transport where required.
    @Bean
    ClientHttpRequestFactory gatewayClientHttpRequestFactory() {
        ClientHttpRequestFactory defaultFactory = new SimpleClientHttpRequestFactory();
        ClientHttpRequestFactory patchFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        return (uri, method) -> method == HttpMethod.PATCH
                ? patchFactory.createRequest(uri, method)
                : defaultFactory.createRequest(uri, method);
    }
}
