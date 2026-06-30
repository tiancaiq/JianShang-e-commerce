package com.msb.ecom.auth_service.dto;

import java.util.List;

public record AdminIdentityLabelsResponse(
        List<UserIdentityLabelResponse> users,
        List<BusinessIdentityLabelResponse> businesses
) {
}
