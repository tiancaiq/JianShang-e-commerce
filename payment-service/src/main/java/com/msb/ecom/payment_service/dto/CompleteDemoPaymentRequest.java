package com.msb.ecom.payment_service.dto;

import jakarta.validation.constraints.Pattern;

public record CompleteDemoPaymentRequest(
        @Pattern(regexp = "[0-7][0-9A-HJKMNP-TV-Z]{25}") String buyerId,
        @Pattern(regexp = "fake_action_[0-7][0-9a-hjkmnp-tv-z]{25}") String actionReference
) {
}
