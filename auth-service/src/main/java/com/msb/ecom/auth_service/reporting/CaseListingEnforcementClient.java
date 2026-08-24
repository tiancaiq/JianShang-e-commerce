package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

interface CaseListingEnforcementClient {
    DispatchResult dryRun(Command command);
    DispatchResult execute(Command command);

    record Command(String caseId, String proposalId, String listingId, ActionType actionType, Set<Scope> scopes,
                   String reasonCode, String reason, Instant effectiveAt, Instant expiresAt,
                   long expectedTargetVersion, String idempotencyKey) { }
    record DispatchResult(String enforcementActionId, JsonNode detail) { }
}
