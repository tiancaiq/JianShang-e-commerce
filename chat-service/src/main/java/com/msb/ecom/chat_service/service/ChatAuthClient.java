package com.msb.ecom.chat_service.service;

import java.util.List;
import java.util.Set;

public interface ChatAuthClient {

    CurrentUser currentUser(String bearerToken);

    IdentityLabels publicLabels(Set<String> userIds);

    record CurrentUser(String id, String displayName, String avatarUrl) {
    }

    record IdentityLabels(List<UserLabel> users) {
    }

    record UserLabel(String id, String displayName, String publicHandle, String avatarUrl) {
    }
}
