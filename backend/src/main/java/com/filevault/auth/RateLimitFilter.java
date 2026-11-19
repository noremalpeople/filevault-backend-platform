package com.filevault.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class RateLimitFilter extends OncePerRequestFilter {
    private final RequestLimits limits;
    private final int login;
    private final int uploads;
    private final int shares;

    RateLimitFilter(int login, int uploads, int shares) {
        if (login < 1 || uploads < 1 || shares < 1) throw new IllegalArgumentException("Rate limits must be positive.");
        this.login = login;
        this.uploads = uploads;
        this.shares = shares;
        limits = new RequestLimits(Duration.ofMinutes(1), 8192, System::nanoTime);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retry = retryAfter(request);
        if (retry == 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retry));
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Retry later.\"}");
    }

    private long retryAfter(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();
        if ("POST".equals(method)) {
            if ("/api/v1/auth/login".equals(path)) return limits.retryAfter("login", request.getRemoteAddr(), login);
            if ("/api/v1/uploads".equals(path)) {
                var user = SecurityContextHolder.getContext().getAuthentication();
                if (user != null && user.isAuthenticated()) return limits.retryAfter("upload", user.getName(), uploads);
            }
        }
        if (("GET".equals(method) || "HEAD".equals(method)) && path.startsWith("/api/v1/public/shares/")) {
            return limits.retryAfter("share", request.getRemoteAddr(), shares);
        }
        return 0;
    }
}
