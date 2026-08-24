package com.msb.ecom.auth_service.support;

import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
class RestSupportExternalContextClient implements SupportExternalContextClient {
    private final RestClient orders;
    private final RestClient products;
    private final RestClient payments;
    private final String token;

    RestSupportExternalContextClient(RestClient.Builder builder,
            @Value("${service.order.url}") String orderUrl,
            @Value("${service.product.url}") String productUrl,
            @Value("${service.payment.url}") String paymentUrl,
            @Value("${commerce.internal-service-token}") String token) {
        this.orders=builder.clone().baseUrl(orderUrl).build();
        this.products=builder.clone().baseUrl(productUrl).build();
        this.payments=builder.clone().baseUrl(paymentUrl).build();
        this.token=token;
    }

    @Override
    public Optional<Context> find(SupportContracts.TargetType type, String targetId, String requesterUserId) {
        try {
            Map<String,Object> body = switch (type) {
                case ORDER -> get(orders,"/api/v1/internal/support/orders/{id}",targetId,requesterUserId);
                case DISPUTE -> get(orders,"/api/v1/internal/support/disputes/{id}",targetId,requesterUserId);
                case LISTING -> get(products,"/api/v1/internal/support/listings/{id}",targetId,requesterUserId);
                case PAYMENT -> get(payments,"/api/v1/internal/support/payments/{id}",targetId,requesterUserId);
                case REFUND -> get(payments,"/api/v1/internal/support/refunds/{id}",targetId,requesterUserId);
                default -> null;
            };
            if(body==null)return Optional.empty();
            String label=safe(body.get("safeLabel"),type.name()+" · "+targetId);
            String path=switch(type){case ORDER->"/admin/orders/"+targetId;case DISPUTE->"/admin/disputes/"+targetId;
                case LISTING->"/listings/"+targetId;case PAYMENT->"/admin/payments/"+targetId;
                case REFUND->"/admin/refunds/"+targetId;default->null;};
            return Optional.of(new Context(label,path,Map.copyOf(body)));
        } catch (RestClientResponseException exception) {
            if(exception.getStatusCode()==HttpStatus.NOT_FOUND||exception.getStatusCode()==HttpStatus.FORBIDDEN)return Optional.empty();
            throw SupportException.unavailable();
        } catch (RestClientException exception) { throw SupportException.unavailable(); }
    }

    private Map<String,Object> get(RestClient client,String path,String id,String requester){
        var request=client.get().uri(builder->{builder.path(path);if(requester!=null)builder.queryParam("requesterUserId",requester);return builder.build(id);})
                .header("X-Internal-Service-Token",token).header(CorrelationId.HEADER_NAME,correlation());
        Map<?,?> result=request.retrieve().body(Map.class);if(result==null)return null;
        Map<String,Object> copy=new LinkedHashMap<>();result.forEach((k,v)->copy.put(String.valueOf(k),v));return copy;
    }
    private static String safe(Object value,String fallback){return value instanceof String s&&!s.isBlank()?s:fallback;}
    private static String correlation(){return CorrelationId.acceptOrGenerate(MDC.get(CorrelationIdFilter.MDC_KEY)).value();}
}
