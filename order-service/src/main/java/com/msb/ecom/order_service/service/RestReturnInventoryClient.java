package com.msb.ecom.order_service.service;
import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Component;import org.springframework.web.client.RestClient;
import java.util.Map;
@Component
public class RestReturnInventoryClient implements ReturnInventoryClient{
 private final RestClient client;private final String token;public RestReturnInventoryClient(RestClient.Builder b,@Value("${service.inventory.url}") String url,@Value("${commerce.internal-service-token}") String token){client=b.baseUrl(url).build();this.token=token;}
 public void restock(String reservation,String order,String ret,String business,String key){client.post().uri("/api/v1/internal/inventory/reservations/{id}/return-restocks",reservation).header("X-Internal-Service-Token",token).header("Idempotency-Key",key).body(Map.of("orderId",order,"returnId",ret,"businessId",business)).retrieve().toBodilessEntity();}
}
