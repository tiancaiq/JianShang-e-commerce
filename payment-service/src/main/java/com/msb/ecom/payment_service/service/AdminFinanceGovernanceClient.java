package com.msb.ecom.payment_service.service;

import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundApproval;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundPreview;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.RefundRequest;

public interface AdminFinanceGovernanceClient {
    RefundApproval evaluate(String accessToken, String paymentId, RefundRequest request,
                            RefundPreview preview, String idempotencyKey);
}
