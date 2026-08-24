package com.msb.ecom.auth_service.system;

import java.util.List;

public interface SystemOperationsClient {
    List<SystemContracts.ServiceHealth> health();
    List<SystemContracts.SourceSnapshot> snapshots();
    SystemContracts.OwnerAction action(String ownerService, String targetType, String targetId,
            String commandType, boolean dryRun, String reason, String idempotencyKey,
            String correlationId);
}
