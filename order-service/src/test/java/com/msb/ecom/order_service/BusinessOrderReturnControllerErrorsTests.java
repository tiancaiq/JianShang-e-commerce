package com.msb.ecom.order_service;

import com.msb.ecom.common.web.autoconfigure.CommonWebAutoConfiguration;
import com.msb.ecom.order_service.config.SecurityConfig;
import com.msb.ecom.order_service.controller.BusinessOrderReturnController;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.service.BusinessOrderReturnService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BusinessOrderReturnController.class)
@Import({SecurityConfig.class, CommonWebAutoConfiguration.class})
class BusinessOrderReturnControllerErrorsTests {

    @Autowired MockMvc mockMvc;
    @MockitoBean BusinessOrderReturnService service;

    @Test
    void foreignBuyerReturnReadIsPrivacySafeNotFound() throws Exception {
        when(service.buyerDetail("01M3JA73M6AJY6NBEX9ZMZ1PMJ", "01M3JA73MAM9PCT9DF34ZSN0SN"))
                .thenThrow(new BuyerOrderException(HttpStatus.NOT_FOUND,
                        "RETURN_NOT_FOUND", "Return was not found."));

        mockMvc.perform(get("/api/v1/orders/{orderId}/groups/{groupId}/return",
                        "01M3JA73M6AJY6NBEX9ZMZ1PMJ", "01M3JA73MAM9PCT9DF34ZSN0SN")
                        .with(jwt()).header("X-Correlation-Id", "return-isolation-check"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RETURN_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Return was not found."))
                .andExpect(jsonPath("$.error.correlationId").value("return-isolation-check"));
    }

    @Test
    void unauthorizedSellerReturnReadUsesScopedErrorEnvelope() throws Exception {
        when(service.sellerDetail("01KZCARTB00000000000000002", "01M3JA73MAM9PCT9DF34ZSN0SN"))
                .thenThrow(new BusinessOrderException(HttpStatus.NOT_FOUND,
                        "BUSINESS_RETURN_NOT_FOUND", "Return was not found."));

        mockMvc.perform(get("/api/v1/businesses/{businessId}/orders/{groupId}/return",
                        "01KZCARTB00000000000000002", "01M3JA73MAM9PCT9DF34ZSN0SN")
                        .with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RETURN_NOT_FOUND"));
    }
}
