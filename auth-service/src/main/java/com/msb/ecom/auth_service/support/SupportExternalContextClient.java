package com.msb.ecom.auth_service.support;

import java.util.Map;
import java.util.Optional;

interface SupportExternalContextClient {
    Optional<Context> find(SupportContracts.TargetType type, String targetId, String requesterUserId);
    record Context(String safeLabel, String adminPath, Map<String, Object> details) { }
}
