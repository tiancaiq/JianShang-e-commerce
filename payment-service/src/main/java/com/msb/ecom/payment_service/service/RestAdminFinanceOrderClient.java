package com.msb.ecom.payment_service.service;

import com.msb.ecom.payment_service.model.PaymentIntentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class RestAdminFinanceOrderClient implements AdminFinanceOrderClient {
    private final RestClient client; private final String token;
    public RestAdminFinanceOrderClient(RestClient.Builder builder,@Value("${service.order.url}")String url,
            @Value("${commerce.internal-service-token}")String token){client=builder.baseUrl(url).build();this.token=token;}
    @Override public Map<String,Context> contexts(Set<String> ids){if(ids.isEmpty())return Map.of();try{
        Context[] values=client.post().uri("/api/v1/internal/admin-finance/payment-contexts")
                .header("X-Internal-Service-Token",token).body(Map.of("paymentIntentIds",ids)).retrieve().body(Context[].class);
        Map<String,Context> result=new LinkedHashMap<>();if(values!=null)Arrays.stream(values).forEach(v->result.put(v.paymentIntentId(),v));return result;
    }catch(RestClientException e){return Map.of();}}
    @Override public Optional<Context> order(String orderId){try{return Optional.ofNullable(client.get()
            .uri("/api/v1/internal/admin-finance/orders/{id}",orderId).header("X-Internal-Service-Token",token)
            .retrieve().body(Context.class));}catch(HttpClientErrorException.NotFound e){return Optional.empty();}
        catch(RestClientException e){throw new PaymentIntentException(HttpStatus.SERVICE_UNAVAILABLE,"ADMIN_ORDER_CONTEXT_UNAVAILABLE","Order context is temporarily unavailable.");}}
}
