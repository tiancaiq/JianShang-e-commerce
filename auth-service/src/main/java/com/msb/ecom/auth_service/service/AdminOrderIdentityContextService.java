package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminIdentityLabelsResponse;
import com.msb.ecom.auth_service.dto.AdminOrderIdentityContextResponse;
import com.msb.ecom.auth_service.dto.AdminOrderIdentityContextResponse.EnforcementSummary;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AdminOrderIdentityContextService {
    private static final int MAX_IDS_PER_TYPE = 50;

    private final AdminAuthorizationService authorization;
    private final EnforcementService enforcement;

    @Transactional(readOnly = true)
    // Resolves only admin-safe labels and active enforcement needed by the Order read model.
    public AdminOrderIdentityContextResponse resolve(Set<String> rawUserIds, Set<String> rawBusinessIds) {
        Set<String> userIds = normalized(rawUserIds);
        Set<String> businessIds = normalized(rawBusinessIds);
        AdminIdentityLabelsResponse labels = authorization.orderIdentityLabels(userIds, businessIds);
        List<EnforcementSummary> actions = Stream.concat(
                        userIds.stream().flatMap(id -> active(TargetType.USER, id)),
                        businessIds.stream().flatMap(id -> active(TargetType.BUSINESS, id)))
                .toList();
        return new AdminOrderIdentityContextResponse(labels.users(), labels.businesses(), actions);
    }

    private Stream<EnforcementSummary> active(TargetType type, String id) {
        return enforcement.actions(type, id).stream()
                .filter(action -> action.lifecycleState() == LifecycleState.ACTIVE)
                .map(action -> new EnforcementSummary(action.enforcementActionId(), type.name(), id,
                        action.actionType().name(), action.scopes().stream()
                                .map(Enum::name).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))));
    }

    private Set<String> normalized(Set<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(FixedLengthIds.requireTrimmed("ID", value, 26));
            }
        }
        if (result.size() > MAX_IDS_PER_TYPE) {
            throw new IllegalArgumentException("At most 50 IDs can be resolved per order context type.");
        }
        return Set.copyOf(result);
    }
}
