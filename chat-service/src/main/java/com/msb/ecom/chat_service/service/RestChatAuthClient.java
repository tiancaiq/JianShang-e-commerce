package com.msb.ecom.chat_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.chat_service.model.ChatDependencyUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Set;

@Component
@Slf4j
public class RestChatAuthClient implements ChatAuthClient {

    private final RestClient restClient;

    public RestChatAuthClient(
            RestClient.Builder restClientBuilder,
            @Value("${service.auth.url}") String authServiceUrl) {
        this.restClient = restClientBuilder.baseUrl(authServiceUrl).build();
    }

    @Override
    public CurrentUser currentUser(String bearerToken) {
        CurrentUserEnvelope response = restClient.get()
                .uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service current user lookup failed status={}",
                            clientResponse.getStatusCode().value());
                    throw new ChatDependencyUnavailableException("Current user could not be resolved.");
                })
                .body(CurrentUserEnvelope.class);

        if (response == null || response.data() == null || response.data().id() == null) {
            throw new ChatDependencyUnavailableException("Current user could not be resolved.");
        }
        return new CurrentUser(response.data().id(), response.data().displayName(), response.data().avatarUrl());
    }

    @Override
    public IdentityLabels publicLabels(Set<String> userIds) {
        if (userIds.isEmpty()) {
            return new IdentityLabels(List.of());
        }
        IdentityLabelsEnvelope response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/users/public-labels")
                        .queryParam("userIds", userIds.toArray())
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Auth-service public labels unavailable status={}",
                            clientResponse.getStatusCode().value());
                    throw new ChatDependencyUnavailableException("Participant labels could not be loaded.");
                })
                .body(IdentityLabelsEnvelope.class);

        if (response == null || response.data() == null || response.data().users() == null) {
            return new IdentityLabels(List.of());
        }
        return new IdentityLabels(response.data().users());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurrentUserEnvelope(CurrentUserData data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CurrentUserData(String id, String displayName, String avatarUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IdentityLabelsEnvelope(IdentityLabels data) {
    }
}
