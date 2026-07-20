package com.msb.ecom.api_gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.stereotype.Service;

@Service
public class OAuth2SessionTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(OAuth2SessionTokenRefresher.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final OAuth2AuthorizedClientService authorizedClientService;

    public OAuth2SessionTokenRefresher(
            OAuth2AuthorizedClientManager authorizedClientManager,
            OAuth2AuthorizedClientService authorizedClientService) {
        this.authorizedClientManager = authorizedClientManager;
        this.authorizedClientService = authorizedClientService;
    }

    // Keeps the server-side OAuth client usable for token relay without exposing tokens to the browser.
    public RefreshResult refreshIfNecessary(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)) {
            return RefreshResult.NOT_APPLICABLE;
        }

        String registrationId = oauth2Authentication.getAuthorizedClientRegistrationId();
        String principalName = oauth2Authentication.getName();
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(
                registrationId,
                principalName);
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            return RefreshResult.SESSION_UNAVAILABLE;
        }

        try {
            OAuth2AuthorizedClient currentClient = authorizedClientManager.authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                            .principal(oauth2Authentication)
                            .build());
            return currentClient == null
                    ? RefreshResult.SESSION_UNAVAILABLE
                    : RefreshResult.SESSION_USABLE;
        } catch (OAuth2AuthorizationException exception) {
            if (!OAuth2ErrorCodes.INVALID_GRANT.equals(exception.getError().getErrorCode())) {
                throw exception;
            }
            authorizedClientService.removeAuthorizedClient(registrationId, principalName);
            log.info("OAuth session requires re-authentication after invalid_grant for client={}", registrationId);
            return RefreshResult.SESSION_UNAVAILABLE;
        }
    }

    public enum RefreshResult {
        NOT_APPLICABLE,
        SESSION_USABLE,
        SESSION_UNAVAILABLE
    }
}
