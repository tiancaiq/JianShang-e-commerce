package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Component
class RestCaseListingEnforcementClient implements CaseListingEnforcementClient {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private final RestClient client;
    private final String token;
    private final CurrentActorProvider actors;
    private final ObjectMapper mapper;

    RestCaseListingEnforcementClient(RestClient.Builder builder,
            @Value("${service.product.url}") String productUrl,
            @Value("${commerce.internal-service-token}") String token,
            CurrentActorProvider actors, ObjectMapper mapper) {
        this.client = builder.baseUrl(productUrl).build();
        this.token = token;
        this.actors = actors;
        this.mapper = mapper;
    }

    @Override public DispatchResult dryRun(Command command) { return call(command, true); }
    @Override public DispatchResult execute(Command command) { return call(command, false); }

    private DispatchResult call(Command command, boolean dryRun) {
        String path = "/api/v1/internal/admin/cases/{caseId}/listings/{listingId}/enforcements"
                + (dryRun ? "/dry-run" : "");
        Request body = new Request(command.actionType(), command.scopes(), command.reasonCode(), command.reason(),
                command.effectiveAt(), command.expiresAt(), command.expectedTargetVersion(),
                dryRun ? "case-dry-run-" + command.proposalId() : command.idempotencyKey(),
                Map.of("origin", "INVESTIGATION_CASE", "policyReference", command.proposalId()));
        try {
            JsonNode response = client.post().uri(path, command.caseId(), command.listingId())
                    .header(INTERNAL_TOKEN_HEADER, token)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + actors.currentActor().accessToken())
                    .header(CorrelationId.HEADER_NAME,
                            CorrelationId.acceptOrGenerate(MDC.get(CorrelationIdFilter.MDC_KEY)).value())
                    .body(body).retrieve().body(JsonNode.class);
            JsonNode action = dryRun ? response.path("proposedAction") : response;
            String actionId = action.path("enforcementActionId").isTextual()
                    ? action.path("enforcementActionId").asText() : null;
            return new DispatchResult(actionId, response);
        } catch (RestClientResponseException exception) {
            String message = safeMessage(exception);
            if (exception.getStatusCode().value() == 403)
                throw new CaseEnforcementDispatchException("CASE_ENFORCEMENT_PERMISSION_DENIED", 403, message);
            if (exception.getStatusCode().value() == 409)
                throw new CaseEnforcementDispatchException("CASE_TARGET_VERSION_CONFLICT", 409, message);
            throw new CaseEnforcementDispatchException("CASE_PROPOSAL_EXECUTION_FAILED",
                    exception.getStatusCode().value(), message);
        } catch (RuntimeException exception) {
            if (exception instanceof CaseEnforcementDispatchException known) throw known;
            throw new CaseEnforcementDispatchException("CASE_PROPOSAL_EXECUTION_UNCERTAIN", 503,
                    "Listing enforcement outcome could not be confirmed. Retry with the same execution key.");
        }
    }

    private String safeMessage(RestClientResponseException exception) {
        try {
            JsonNode body = mapper.readTree(exception.getResponseBodyAsString());
            String value = body.path("error").path("message").asText();
            return value.isBlank() ? "Listing enforcement validation failed." : value;
        } catch (Exception ignored) {
            return "Listing enforcement validation failed.";
        }
    }

    private record Request(com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType actionType,
                           java.util.Set<com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope> scopes,
                           String reasonCode, String reason, java.time.Instant effectiveAt,
                           java.time.Instant expiresAt, Long expectedListingVersion, String idempotencyKey,
                           Map<String, String> safeMetadata) { }
}
