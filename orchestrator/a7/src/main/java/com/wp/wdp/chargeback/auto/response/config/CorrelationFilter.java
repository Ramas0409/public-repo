package com.wp.wdp.chargeback.auto.response.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.extern.slf4j.Slf4j;
import com.wp.wdp.chargeback.auto.response.util.LogContext;
import com.wp.wdp.chargeback.auto.response.util.RequestCorrelation;

/**
 * Carries the caller's {@code v-correlation-id} where it passes the check, else a new one, through the request and
 * back on the response; an id refused is logged by its length alone. Every logging-context key the request put is
 * cleared at its end.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        LogContext.clear();
        String incoming = request.getHeader(RequestCorrelation.HEADER);
        String id = RequestCorrelation.start(RequestCorrelation.accepted(incoming));
        response.setHeader(RequestCorrelation.HEADER, id);
        try {
            if (incoming != null && RequestCorrelation.accepted(incoming) == null) {
                log.warn("event=corr.replaced len={}", incoming.length());
            }
            chain.doFilter(request, response);
        } finally {
            RequestCorrelation.clear();
            LogContext.clear();
        }
    }
}
