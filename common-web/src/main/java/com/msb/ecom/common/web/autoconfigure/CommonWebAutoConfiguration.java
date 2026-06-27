package com.msb.ecom.common.web.autoconfigure;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.CommonApiExceptionHandler;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.common.web.security.SpringSecurityCurrentActorProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    CommonApiExceptionHandler commonApiExceptionHandler() {
        return new CommonApiExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    CurrentActorProvider currentActorProvider() {
        return new SpringSecurityCurrentActorProvider();
    }
}
