package com.msb.ecom.order_service.service;
import java.math.BigDecimal;
public interface ReturnPaymentClient{Result refund(String paymentIntentId,String orderId,String businessOrderId,String returnId,BigDecimal amount,String currency,String key);record Result(String refundId,BigDecimal amount,String status){public Result(String refundId,BigDecimal amount){this(refundId,amount,"SUCCEEDED");}} }
