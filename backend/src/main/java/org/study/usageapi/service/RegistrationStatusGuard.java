package org.study.usageapi.service;

import org.springframework.http.HttpStatus;
import org.study.usageapi.api.ApiException;

import java.util.Optional;

/**
 * Status check for requests that already carry a device-signed access token.
 * Unauthenticated login steps keep their generic 401 so they do not reveal a registration's status.
 */
final class RegistrationStatusGuard {

    private RegistrationStatusGuard() {
    }

    static void requireActivated(Optional<String> status) {
        String value = status.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                "The registration is not eligible."));
        switch (value) {
            case "activated" -> {
            }
            case "not_registered" -> throw new ApiException(HttpStatus.FORBIDDEN,
                    "registration_not_activated", "The registration has not been activated.");
            case "deactivated" -> throw new ApiException(HttpStatus.FORBIDDEN,
                    "registration_deactivated", "The registration has been deactivated.");
            default -> throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "The registration is not eligible.");
        }
    }
}
