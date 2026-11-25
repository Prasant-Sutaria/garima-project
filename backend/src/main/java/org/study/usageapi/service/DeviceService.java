package org.study.usageapi.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.study.usageapi.api.ApiException;
import org.study.usageapi.api.DeviceDtos;
import org.study.usageapi.persistence.DeviceRepository;
import org.study.usageapi.persistence.RegistrationRepository;

import java.util.UUID;

@Service
public class DeviceService {

    private final RegistrationRepository registrations;
    private final DeviceRepository devices;
    private final DeviceKeyCodec keyCodec;

    public DeviceService(RegistrationRepository registrations, DeviceRepository devices,
                         DeviceKeyCodec keyCodec) {
        this.registrations = registrations;
        this.devices = devices;
        this.keyCodec = keyCodec;
    }

    @Transactional
    public DeviceRegistrationResult register(DeviceDtos.RegisterRequest request) {
        String status = registrations.lockStatus(request.registrationId())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                        "This registration is not eligible."));
        if (!"not_registered".equals(status) && !"activated".equals(status)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "This registration is not eligible.");
        }
        try {
            keyCodec.decode(request.publicKey());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_key", "The public key is invalid.");
        }

        var existing = devices.findActiveByRegistration(request.registrationId());
        if (existing.isPresent()) {
            if (keyCodec.matches(existing.get().publicKeyJson(), request.publicKey())) {
                return new DeviceRegistrationResult(existing.get().id(), true);
            }
            throw new ApiException(HttpStatus.CONFLICT, "device_exists",
                    "An active device is already registered.");
        }

        UUID deviceId = UUID.randomUUID();
        devices.insert(deviceId, request.registrationId(), request.publicKey(), request.appVersion());
        registrations.activate(request.registrationId());
        return new DeviceRegistrationResult(deviceId, false);
    }

    public record DeviceRegistrationResult(UUID deviceId, boolean alreadyRegistered) {
    }
}
