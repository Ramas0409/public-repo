package com.wp.wdp.auto.response.event.service.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wp.wdp.auto.response.event.service.client.Downstream;
import com.wp.wdp.auto.response.event.service.client.DownstreamClient;
import com.wp.wdp.auto.response.event.service.client.DownstreamErrorHandler;
import com.wp.wdp.auto.response.event.service.client.DownstreamLoggingInterceptor;
import com.wp.wdp.auto.response.event.service.service.DownstreamHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.util.EnumMap;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class ClientConfiguration {

    private final SchedulerProperties properties;
    private final DownstreamHeaders downstreamHeaders;
    private final ApplicationProps props;

    /**
     * Default request factory for every profile except "local".
     * Normal JVM trust-store validation, with the configured timeouts.
     */
    @Bean
    @Profile("!local")
    public ClientHttpRequestFactory downstreamRequestFactory() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(properties.getConnectTimeout())
                .withReadTimeout(properties.getRunTimeout());
        return ClientHttpRequestFactories.get(settings);
    }

    /**
     * LOCAL ONLY. Skips TLS validation (see TrustAllRequestFactory) but keeps
     * the same timeouts. Only active when spring.profiles.active=local.
     */
    @Bean
    @Profile("local")
    public ClientHttpRequestFactory localTrustAllDownstreamRequestFactory() {
        TrustAllRequestFactory factory = new TrustAllRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getRunTimeout());
        return factory;
    }

    @Bean
    public RestClient.Builder downstreamRestClientBuilder(ClientHttpRequestFactory downstreamRequestFactory) {
        return RestClient.builder().requestFactory(downstreamRequestFactory)
                .requestInterceptor((request, body, execution) -> {
                    downstreamHeaders.addTo(request.getHeaders());
                    return execution.execute(request, body);
                })
                .requestInterceptor(new DownstreamLoggingInterceptor());
    }

    @Bean
    public DownstreamClient downstreamClient(RestClient.Builder downstreamRestClientBuilder) {
        Map<Downstream, RestClient> clients = new EnumMap<>(Downstream.class);
        clients.put(Downstream.ORCHESTRATOR, client(downstreamRestClientBuilder, Downstream.ORCHESTRATOR,
                props.getOrchestratorBaseUrl()));
        clients.put(Downstream.COMPARISON, client(downstreamRestClientBuilder, Downstream.COMPARISON,
                props.getLlmServiceBaseUrl()));
        clients.put(Downstream.QUESTIONNAIRE, client(downstreamRestClientBuilder, Downstream.QUESTIONNAIRE,
                props.getQuestionnaireBaseUrl()));
        clients.put(Downstream.DOCUMENT, client(downstreamRestClientBuilder, Downstream.DOCUMENT,
                props.getDocumentServiceBaseUrl()));
        clients.put(Downstream.CASE_MANAGEMENT, client(downstreamRestClientBuilder, Downstream.CASE_MANAGEMENT,
                props.getDisputeServiceBaseUrl()));
        return new DownstreamClient(clients);
    }

    private RestClient client(RestClient.Builder shared, Downstream service, String baseUrl) {
        return shared.clone().baseUrl(baseUrl)
                .messageConverters(converters -> converters.stream()
                        .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                        .map(MappingJackson2HttpMessageConverter.class::cast)
                        .forEach(converter -> converter.setObjectMapper(converter.getObjectMapper().copy()
                                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                                .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true)))))
                .defaultStatusHandler(HttpStatusCode::isError, new DownstreamErrorHandler(service.getService()))
                .build();
    }
}
