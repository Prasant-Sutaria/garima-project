package org.study.usageapi.service;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeFilter extends OncePerRequestFilter {

    public static final String REQUEST_BODY_ATTRIBUTE = RequestSizeFilter.class.getName() + ".body";

    private final int maximumBytes;
    private final SecurityErrorWriter errorWriter;

    public RequestSizeFilter(@Value("${backend.upload.max-body-bytes}") int maximumBytes,
                             SecurityErrorWriter errorWriter) {
        this.maximumBytes = maximumBytes;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        boolean hasBodyMethod = "POST".equals(request.getMethod()) || "PATCH".equals(request.getMethod());
        return !hasBodyMethod || !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    jakarta.servlet.FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maximumBytes) {
            reject(response);
            return;
        }
        byte[] body = readAtMost(request.getInputStream(), maximumBytes + 1);
        if (body.length > maximumBytes) {
            reject(response);
            return;
        }
        request.setAttribute(REQUEST_BODY_ATTRIBUTE, body);
        filterChain.doFilter(new ReplayableRequest(request, body), response);
    }

    private byte[] readAtMost(InputStream input, int limit) throws IOException {
        var output = new java.io.ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int remaining = limit;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toByteArray();
    }

    private void reject(HttpServletResponse response) throws IOException {
        errorWriter.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large",
                "The request exceeds the maximum permitted size.");
    }

    private static final class ReplayableRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private ReplayableRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Asynchronous request reads are not supported");
                }

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
