package com.qualitygate.platform.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 相関 ID をログに載せる仕組みの登録（docs/spec/05-architecture.md 10.1）。 */
@Configuration
public class ObservabilityConfig implements WebMvcConfigurer {

    /** 認証より前（Spring Security のフィルタより前）に置き、認証の失敗のログにも ID が付くようにする。 */
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RunIdMdcInterceptor()).addPathPatterns("/api/**");
    }
}
