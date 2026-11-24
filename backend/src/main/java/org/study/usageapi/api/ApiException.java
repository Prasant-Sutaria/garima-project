package org.study.usageapi.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String clientMessage;

    public ApiException(HttpStatus status, String code, String clientMessage) {
        super(clientMessage);
        this.status = status;
        this.code = code;
        this.clientMessage = clientMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String clientMessage() {
        return clientMessage;
    }
}
