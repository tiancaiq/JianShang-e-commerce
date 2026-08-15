package com.msb.ecom.payment_service.dto;
import java.math.BigDecimal;
import java.time.Instant;
public record ReturnRefundResponse(String refundId,String returnId,String orderId,String businessOrderId,
 BigDecimal amount,String currency,String status,Instant completedAt,String displayName,String disclosure){}
