package com.msb.ecom.order_service.service;
import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Component;import org.springframework.web.client.RestClient;
import java.math.BigDecimal;import java.util.Map;
@Component
public class RestReturnPaymentClient implements ReturnPaymentClient{
 private final RestClient client;private final String token;public RestReturnPaymentClient(RestClient.Builder b,@Value("${service.payment.url}") String url,@Value("${service.payment.internal-service-token}") String token){client=b.baseUrl(url).build();this.token=token;}
 public Result refund(String intent,String order,String group,String ret,BigDecimal amount,String currency,String key){Map<?,?> body=client.post().uri("/api/v1/internal/payment-intents/{id}/return-refunds",intent).header("X-Internal-Service-Token",token).header("Idempotency-Key",key).body(Map.of("orderId",order,"businessOrderId",group,"returnId",ret,"amount",amount,"currency",currency)).retrieve().body(Map.class);if(body==null||!(body.get("refundId") instanceof String id)||!(body.get("status") instanceof String status))throw new IllegalStateException("Return refund response was incomplete.");return new Result(id,new BigDecimal(body.get("amount").toString()),status);}
}
