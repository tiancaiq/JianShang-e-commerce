package com.msb.ecom.payment_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.payment_service.model.PaymentIntentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Set;

@Component
public class RestAdminFinanceAuthorizationClient implements AdminFinanceAuthorizationClient {
    private final RestClient client;
    public RestAdminFinanceAuthorizationClient(RestClient.Builder builder,@Value("${service.auth.url}") String url) { client=builder.baseUrl(url).build(); }

    @Override public Access requireAdmin(String token) {
        try {
            AdminEnvelope response=client.get().uri("/api/v1/admin/me").header(HttpHeaders.AUTHORIZATION,"Bearer "+token).retrieve()
                    .onStatus(HttpStatusCode::isError,(q,r)->{if(r.getStatusCode().value()==401||r.getStatusCode().value()==403)throw forbidden();throw unavailable();})
                    .body(AdminEnvelope.class);
            if(response==null||response.data()==null)throw unavailable(); return response.data();
        } catch(PaymentIntentException e){throw e;} catch(RestClientException e){throw unavailable();}
    }
    @Override public Labels labels(String token,Set<String> users,Set<String> businesses) {
        try {
            LabelsEnvelope response=client.get().uri(b->b.path("/api/v1/admin/finance-context")
                            .queryParam("userIds",users.toArray()).queryParam("businessIds",businesses.toArray()).build())
                    .header(HttpHeaders.AUTHORIZATION,"Bearer "+token).retrieve().body(LabelsEnvelope.class);
            return response==null||response.data()==null?new Labels(null,null):response.data();
        } catch(RestClientException e){return new Labels(null,null);}
    }
    private PaymentIntentException forbidden(){return new PaymentIntentException(HttpStatus.FORBIDDEN,"ADMIN_FINANCE_PERMISSION_REQUIRED","Administrative finance permission is required.");}
    private PaymentIntentException unavailable(){return new PaymentIntentException(HttpStatus.SERVICE_UNAVAILABLE,"ADMIN_FINANCE_AUTHORIZATION_UNAVAILABLE","Administrative authorization is temporarily unavailable.");}
    @JsonIgnoreProperties(ignoreUnknown=true) private record AdminEnvelope(Access data) { }
    @JsonIgnoreProperties(ignoreUnknown=true) private record LabelsEnvelope(Labels data) { }
}
