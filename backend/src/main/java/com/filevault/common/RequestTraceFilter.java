package com.filevault.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnWebApplication
class RequestTraceFilter extends OncePerRequestFilter {
    static final String HEADER = "X-Request-Id";
    private final TransferTelemetry telemetry;

    RequestTraceFilter(TransferTelemetry telemetry) { this.telemetry = telemetry; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        response.setHeader(HEADER, id);
        String path = request.getServletPath();
        boolean upload = request.getMethod().equals("POST") && (path.equals("/api/v1/files")
                || (path.startsWith("/api/v1/uploads/") && path.endsWith("/complete")));
        long start = System.nanoTime();
        boolean finished = false;
        try (var mdc = MDC.putCloseable("requestId", id)) {
            try {
                chain.doFilter(request, response);
                finished = true;
            } finally {
                if (upload) telemetry.uploadRequest(finished && response.getStatus() < 400, start);
            }
        }
    }
}
