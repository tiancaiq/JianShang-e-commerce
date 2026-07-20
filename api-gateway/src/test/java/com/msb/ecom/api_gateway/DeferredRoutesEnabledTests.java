package com.msb.ecom.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "msb.gateway.features.agent=true",
                "msb.gateway.features.category-guidance=true",
                "msb.gateway.features.cart-checkout=true",
                "msb.gateway.features.inventory=true",
                "msb.gateway.features.buyer-addresses=true",
                "msb.gateway.features.payment=true",
                "msb.gateway.features.business-orders=true"
        })
class DeferredRoutesEnabledTests {

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void explicitlyEnabledDeferredRoutesRegisterWithTheGateway() {
        assertThat(applicationContext.containsBean("agentServiceRoute")).isTrue();
        assertThat(applicationContext.containsBean("categoryGuidanceServiceRoute")).isTrue();
        assertThat(applicationContext.containsBean("orderServiceRoute")).isTrue();
        assertThat(applicationContext.containsBean("inventoryServiceRoute")).isTrue();
        assertThat(applicationContext.containsBean("buyerAddressServiceRoute")).isTrue();
        assertThat(applicationContext.containsBean("paymentServiceRoute")).isTrue();
        assertThat(applicationContext.containsBean("businessOrderServiceRoute")).isTrue();

        assertThat(applicationContext.containsBean("disabledAgentServiceRoute")).isFalse();
        assertThat(applicationContext.containsBean("disabledCategoryGuidanceServiceRoute")).isFalse();
        assertThat(applicationContext.containsBean("disabledOrderServiceRoute")).isFalse();
        assertThat(applicationContext.containsBean("disabledInventoryServiceRoute")).isFalse();
        assertThat(applicationContext.containsBean("disabledBuyerAddressServiceRoute")).isFalse();
        assertThat(applicationContext.containsBean("disabledPaymentServiceRoute")).isFalse();
        assertThat(applicationContext.containsBean("disabledBusinessOrderServiceRoute")).isFalse();
    }
}
