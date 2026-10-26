package com.wp.wdp.auto.response.event.service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestInvokerConfig {

    /**
     * Default RestTemplate for every profile except "local".
     * Uses normal JVM trust-store validation.
     */
    @Bean
    @Profile("!local")
    public RestTemplate createRestTemplate() {
        return new RestTemplate();
    }

    /**
     * LOCAL ONLY. Skips TLS validation (see TrustAllRequestFactory).
     * Only active when spring.profiles.active=local.
     */
    @Bean
    @Profile("local")
    public RestTemplate localTrustAllRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(new TrustAllRequestFactory());
        return restTemplate;
    }
}
