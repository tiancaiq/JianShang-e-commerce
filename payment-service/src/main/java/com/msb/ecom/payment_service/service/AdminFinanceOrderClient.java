package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface AdminFinanceOrderClient {
    Map<String,Context> contexts(Set<String> paymentIntentIds);
    Optional<Context> order(String orderId);

    @JsonIgnoreProperties(ignoreUnknown=true)
    record Context(String paymentIntentId,String orderId,String orderNumber,String buyerUserId,
            BigDecimal totalAmount,String currency,Instant createdAt,List<Business> businesses,
            List<Item> items,List<Dispute> disputes) {
        public Context { businesses=businesses==null?List.of():List.copyOf(businesses);items=items==null?List.of():List.copyOf(items);disputes=disputes==null?List.of():List.copyOf(disputes); }
    }
    @JsonIgnoreProperties(ignoreUnknown=true) record Business(String businessId,String storeNameAtPurchase,BigDecimal totalAmount) { }
    @JsonIgnoreProperties(ignoreUnknown=true) record Item(String listingId,String titleAtPurchase,int quantity,BigDecimal lineTotal) { }
    @JsonIgnoreProperties(ignoreUnknown=true) record Dispute(String disputeId,String businessOrderId,String businessId,
            String resolutionType,BigDecimal recommendedRefundAmount,String currency,String resolutionReason,Instant resolvedAt) { }
}
