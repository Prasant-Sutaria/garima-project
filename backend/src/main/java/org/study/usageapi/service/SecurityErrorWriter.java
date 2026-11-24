package org.study.usageapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.study.usageapi.api.ApiError;

import java.io.IOException;

@Component
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        if (request.getRequestURI().startsWith("/api/v1/admin/")) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"usage-admin\"");
        } else {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"usage-api\"");
        }
        write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication failed.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException exception) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "forbidden", "Access is not permitted.");
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ApiError(code, message, RequestIdFilter.currentRequestId()));
    }
}
