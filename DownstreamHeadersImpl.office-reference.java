package com.wp.wdp.auto.response.event.service.service.impl;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.wp.wdp.auto.response.event.service.exception.ApplicationConstants;
import com.wp.wdp.auto.response.event.service.service.DownstreamHeaders;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DownstreamHeadersImpl implements DownstreamHeaders {

    private final TokenService tokenService;

    @Override
    public void addTo(HttpHeaders headers) {
        headers.add(ApplicationConstants.V_CORRELATION_ID, RequestCorrelation.getId());
        try {
            headers.add(HttpHeaders.AUTHORIZATION, ApplicationConstants.BEARER + " " + tokenService.getIdpToken());
        } catch (Exception exception) {
            throw new IllegalStateException("no IdP token", exception);
        }
    }
}
