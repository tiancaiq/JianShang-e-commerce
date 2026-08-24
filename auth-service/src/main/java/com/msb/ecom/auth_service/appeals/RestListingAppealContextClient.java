package com.msb.ecom.auth_service.appeals;

import com.msb.ecom.auth_service.appeals.ListingAppealContextClient.ListingEnforcementContext;
import com.msb.ecom.auth_service.appeals.ListingAppealContextClient.ListingResolutionRequest;
import com.msb.ecom.auth_service.appeals.ListingAppealContextClient.ListingResolutionResult;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class RestListingAppealContextClient implements ListingAppealContextClient {
    private static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private final RestClient client;
    private final String token;

    public RestListingAppealContextClient(RestClient.Builder builder,
            @Value("${service.product.url}") String url,
            @Value("${commerce.internal-service-token}") String token) {
        this.client = builder.baseUrl(url).build();
        this.token = token;
    }

    @Override
    public Optional<ListingEnforcementContext> byActionId(String actionId) {
        try {
            return Optional.ofNullable(client.get()
                    .uri("/api/v1/internal/appeals/listing-enforcements/{id}", actionId)
                    .header(TOKEN_HEADER, token).header(CorrelationId.HEADER_NAME, correlation())
                    .retrieve().body(ListingEnforcementContext.class));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            throw AppealException.unavailable("Listing enforcement context is temporarily unavailable.");
        } catch (RestClientException exception) {
            throw AppealException.unavailable("Listing enforcement context is temporarily unavailable.");
        }
    }

    @Override
    public List<ListingEnforcementContext> activeForActor(String userId, Set<String> businessIds) {
        try {
            List<ListingEnforcementContext> result = client.post()
                    .uri("/api/v1/internal/appeals/listing-enforcements/mine")
                    .header(TOKEN_HEADER, token).header(CorrelationId.HEADER_NAME, correlation())
                    .body(new MineRequest(userId, businessIds)).retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return result == null ? List.of() : result;
        } catch (RestClientException exception) {
            throw AppealException.unavailable("Listing enforcement context is temporarily unavailable.");
        }
    }

    @Override
    public ListingResolutionResult previewResolution(String appealId, ListingResolutionRequest request) {
        return resolution(appealId, request, true);
    }

    @Override
    public ListingResolutionResult executeResolution(String appealId, ListingResolutionRequest request) {
        return resolution(appealId, request, false);
    }

    private ListingResolutionResult resolution(String appealId, ListingResolutionRequest request,
                                               boolean dryRun) {
        String suffix = dryRun ? "/dry-run" : "";
        try {
            return client.post()
                    .uri("/api/v1/internal/admin/appeals/{appealId}/listing-enforcement-resolution" + suffix,
                            appealId)
                    .header(TOKEN_HEADER, token).header(CorrelationId.HEADER_NAME, correlation())
                    .body(request).retrieve().body(ListingResolutionResult.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                throw AppealException.conflict("LISTING_ENFORCEMENT_VERSION_CONFLICT",
                        "Listing enforcement changed. Refresh the appeal and preview the resolution again.");
            }
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw AppealException.forbidden("APPEAL_RESOLUTION_PERMISSION_REQUIRED",
                        "Listing enforcement authority is required to resolve this appeal.");
            }
            if (exception.getStatusCode() == HttpStatus.BAD_REQUEST) {
                throw AppealException.invalid("APPEAL_LISTING_RESOLUTION_INVALID",
                        "The listing appeal resolution is invalid.");
            }
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw AppealException.enforcementNotFound();
            }
            throw AppealException.unavailable("Listing appeal resolution is temporarily unavailable.");
        } catch (RestClientException exception) {
            throw AppealException.unavailable("Listing appeal resolution is temporarily unavailable.");
        }
    }

    private String correlation() {
        return CorrelationId.acceptOrGenerate(MDC.get(CorrelationIdFilter.MDC_KEY)).value();
    }

    private record MineRequest(String userId, Set<String> businessIds) { }
}
