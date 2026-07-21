package com.msb.ecom.notification_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.notification_service.config.NotificationReadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.regex.Pattern;

@Component
public class RestNotificationActorIdentityClient implements NotificationActorIdentityClient {

    private static final Logger log =
            LoggerFactory.getLogger(RestNotificationActorIdentityClient.class);
    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");

    private final RestClient client;

    @Autowired
    public RestNotificationActorIdentityClient(
            RestClient.Builder builder,
            NotificationReadProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = builder
                .baseUrl(properties.authServiceUrl())
                .requestFactory(requestFactory)
                .build();
    }

    RestNotificationActorIdentityClient(RestClient client) {
        this.client = client;
    }

    // Resolves the opaque active application user without trusting browser identity headers.
    @Override
    public String requireActiveUserId(String authorizationHeader, String correlationId) {
        try {
            CurrentUserEnvelope response = client.get()
                    .uri("/api/v1/users/me")
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .header(CorrelationId.HEADER_NAME, correlationId)
                    .retrieve()
                    .onStatus(status -> status.value() == 401, (request, clientResponse) -> {
                        throw new AuthenticationRequiredException();
                    })
                    .onStatus(status -> status.value() == 403, (request, clientResponse) -> {
                        throw new AccessDeniedException();
                    })
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        log.warn(
                                "Notification identity lookup failed outcome=dependency_status status={}",
                                clientResponse.getStatusCode().value());
                        throw new DependencyUnavailableException();
                    })
                    .body(CurrentUserEnvelope.class);

            if (response == null
                    || response.data() == null
                    || response.data().id() == null
                    || !ULID.matcher(response.data().id()).matches()
                    || response.data().status() == null) {
                throw new DependencyUnavailableException();
            }
            if ("ACTIVE".equals(response.data().status())) {
                return response.data().id();
            }
            if ("SUSPENDED".equals(response.data().status())
                    || "CLOSED".equals(response.data().status())) {
                throw new AccessDeniedException();
            }
            throw new DependencyUnavailableException();
        } catch (AuthenticationRequiredException
                 | AccessDeniedException
                 | DependencyUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn(
                    "Notification identity lookup failed outcome=transport type={}",
                    exception.getClass().getSimpleName());
            throw new DependencyUnavailableException();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurrentUserEnvelope(CurrentUser data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurrentUser(String id, String status) {
    }
}
