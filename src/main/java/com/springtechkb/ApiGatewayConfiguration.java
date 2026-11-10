package com.springtechkb;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration(proxyBeanMethods = false)
class ApiGatewayConfiguration {

    @Bean
    ApiGatewayFilter apiGatewayFilter(
            ApiRateLimiter rateLimiter,
            MeterRegistry meters,
            @Value("${springtechkb.runtime.role:all}") String runtimeRole) {
        return new ApiGatewayFilter(
                rateLimiter, ApiGatewayFilter.RuntimeRole.from(runtimeRole), meters);
    }

    @Bean
    FilterRegistrationBean<ApiGatewayFilter> apiGatewayFilterRegistration(ApiGatewayFilter filter) {
        FilterRegistrationBean<ApiGatewayFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
