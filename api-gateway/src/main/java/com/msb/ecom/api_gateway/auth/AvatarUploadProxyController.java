package com.msb.ecom.api_gateway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
public class AvatarUploadProxyController {

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final RestClient restClient;
    private final String authServiceUrl;

    public AvatarUploadProxyController(
            OAuth2AuthorizedClientService authorizedClientService,
            RestClient.Builder restClientBuilder,
            @Value("${service.auth.url}") String authServiceUrl) {
        this.authorizedClientService = authorizedClientService;
        this.restClient = restClientBuilder.build();
        this.authServiceUrl = authServiceUrl;
    }

    // Rebuilds the multipart request after the BFF authenticates the browser session; Gateway MVC generic proxying drops the file part.
    @PostMapping(value = "/api/v1/users/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadAvatar(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) throws IOException {
        OAuth2Access access = accessToken(authentication);
        if (access == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""
                            {"error":{"code":"UNAUTHENTICATED","message":"Authentication is required.","fieldErrors":[],"correlationId":null}}""");
        }

        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(file.getContentType()));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(
                new NamedByteArrayResource(file.getBytes(), file.getOriginalFilename()),
                partHeaders));

        try {
            return restClient.post()
                    .uri(authServiceUrl + "/api/v1/users/me/avatar")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + access.token())
                    .headers(headers -> {
                        if (ifMatch != null) {
                            headers.set("If-Match", ifMatch);
                        }
                    })
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException exception) {
            return ResponseEntity.status(exception.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(exception.getResponseBodyAsString());
        }
    }

    @DeleteMapping("/api/v1/users/me/avatar")
    public ResponseEntity<String> deleteAvatar(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            Authentication authentication) {
        OAuth2Access access = accessToken(authentication);
        if (access == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""
                            {"error":{"code":"UNAUTHENTICATED","message":"Authentication is required.","fieldErrors":[],"correlationId":null}}""");
        }

        try {
            return restClient.delete()
                    .uri(authServiceUrl + "/api/v1/users/me/avatar")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + access.token())
                    .headers(headers -> {
                        if (ifMatch != null) {
                            headers.set("If-Match", ifMatch);
                        }
                    })
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException exception) {
            return ResponseEntity.status(exception.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(exception.getResponseBodyAsString());
        }
    }

    private OAuth2Access accessToken(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)) {
            return null;
        }
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(
                oauth2Authentication.getAuthorizedClientRegistrationId(),
                oauth2Authentication.getName());
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            return null;
        }
        return new OAuth2Access(authorizedClient.getAccessToken().getTokenValue());
    }

    private record OAuth2Access(String token) {
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename == null || filename.isBlank() ? "avatar" : filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
