package org.study.usageapi.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.study.usageapi.api.ApiException;

import java.io.IOException;

@Component
@Profile("local & !prod")
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class SelfRegistrationRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final SecurityErrorWriter errorWriter;

    public SelfRegistrationRateLimitFilter(RateLimiter rateLimiter, SecurityErrorWriter errorWriter) {
        this.rateLimiter = rateLimiter;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !request.getRequestURI().equals(request.getContextPath() + "/api/v1/registrations");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            rateLimiter.check("self-registration:ip:" + request.getRemoteAddr(), 10);
        } catch (ApiException exception) {
            response.setHeader(HttpHeaders.RETRY_AFTER, "60");
            errorWriter.write(response, exception.status(), exception.code(), exception.clientMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
