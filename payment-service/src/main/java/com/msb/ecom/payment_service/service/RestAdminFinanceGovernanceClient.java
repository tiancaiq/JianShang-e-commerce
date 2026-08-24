package com.msb.ecom.payment_service.service;

import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundApproval;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundPreview;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundRequest;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;

@Component
public class RestAdminFinanceGovernanceClient implements AdminFinanceGovernanceClient {
    private final RestClient client;

    public RestAdminFinanceGovernanceClient(RestClient.Builder builder,
                                            @Value("${service.auth.url}") String authUrl) {
        this.client = builder.baseUrl(authUrl).build();
    }

    @Override
    public RefundApproval evaluate(String accessToken, String paymentId, RefundRequest request,
                                   RefundPreview preview, String idempotencyKey) {
        try {
            RefundApproval result = client.post()
                    .uri("/api/v1/admin/governance/domain/refunds")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new RefundGovernanceRequest(paymentId, request.refundType(),
                            preview.requestedAmount(), preview.currency(), request.reasonCode(),
                            request.reason(), request.disputeId(), preview.expectedPaymentVersion(),
                            idempotencyKey, "Refund " + preview.requestedAmount() + " "
                            + preview.currency() + " for payment " + paymentId))
                    .retrieve().body(RefundApproval.class);
            if (result == null) throw unavailable();
            return result;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private PaymentIntentException unavailable() {
        return new PaymentIntentException(HttpStatus.SERVICE_UNAVAILABLE,
                "REFUND_GOVERNANCE_UNAVAILABLE",
                "Refund governance could not be evaluated. No refund was executed.");
    }

    private record RefundGovernanceRequest(String paymentId, String refundType,
                                           BigDecimal amount, String currency,
                                           String reasonCode, String reason, String disputeId,
                                           long expectedPaymentVersion, String idempotencyKey,
                                           String safeSummary) { }
}
