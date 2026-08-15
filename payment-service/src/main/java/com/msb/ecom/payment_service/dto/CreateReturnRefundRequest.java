package com.msb.ecom.payment_service.dto;
import java.math.BigDecimal;
public record CreateReturnRefundRequest(String orderId,String businessOrderId,String returnId,BigDecimal amount,String currency){}
