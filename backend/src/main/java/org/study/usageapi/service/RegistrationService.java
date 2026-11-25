package org.study.usageapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.study.usageapi.api.ApiException;
import org.study.usageapi.api.RegistrationDtos;
import org.study.usageapi.crypto.KeyProvider;
import org.study.usageapi.persistence.AuditRepository;
import org.study.usageapi.persistence.DeviceRepository;
import org.study.usageapi.persistence.RegistrationRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

@Service
public class RegistrationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegistrationService.class);
    private static final String ID_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ID_ATTEMPTS = 16;

    private final RegistrationRepository registrations;
    private final DeviceRepository devices;
    private final AuditRepository audit;
    private final KeyProvider keyProvider;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;

    public RegistrationService(RegistrationRepository registrations, DeviceRepository devices,
                               AuditRepository audit, KeyProvider keyProvider,
                               ObjectMapper objectMapper, SecureRandom secureRandom) {
        this.registrations = registrations;
        this.devices = devices;
        this.audit = audit;
        this.keyProvider = keyProvider;
        this.objectMapper = objectMapper;
        this.secureRandom = secureRandom;
    }

    @Transactional
    public RegistrationDtos.SelfRegistrationResponse selfRegister(
            RegistrationDtos.SelfRegistrationRequest request, String requestId) {
        byte[] verifier = keyProvider.contactFingerprint("system", "contact-index-key-verifier-v1");
        if (!MessageDigest.isEqual(verifier, registrations.contactIndexVerifier(verifier))) {
            LOGGER.error("Contact index key verification failed; restore the matching keystore and database backup");
            throw new IllegalStateException("The contact index key is unavailable or does not match the database");
        }
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        String mobile = request.mobileNumber();
        if (!registrations.isSchemeActive(request.schemeMonths())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_scheme",
                    "The selected scheme is not available.");
        }
        byte[] emailFingerprint = keyProvider.contactFingerprint("email", email);
        byte[] mobileFingerprint = keyProvider.contactFingerprint("mobile", mobile);
        var encryptedFields = keyProvider.encryptFields(
                request.age().toString().getBytes(StandardCharsets.UTF_8),
                request.gender().strip().getBytes(StandardCharsets.UTF_8));
        byte[] details;
        try {
            details = objectMapper.writeValueAsBytes(Map.of(
                    "user_name", request.userName().strip(),
                    "mobile_number", mobile,
                    "email", email,
                    "indoor_activity", request.indoorActivity().strip(),
                    "outdoor_activity", request.outdoorActivity().strip()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize registration details", exception);
        }
        var encryptedDetails = keyProvider.encrypt(details);
        for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
            String registrationId = generateRegistrationId();
            if (!registrations.insertGenerated(registrationId, encryptedFields.firstCiphertext(),
                    encryptedFields.secondCiphertext(), encryptedFields.wrappedDek(), encryptedFields.keyId())) {
                continue;
            }
            if (!registrations.insertEnrollmentDetails(registrationId, encryptedDetails.ciphertext(),
                    encryptedDetails.wrappedDek(), encryptedDetails.keyId(), emailFingerprint, mobileFingerprint,
                    request.schemeMonths(), request.profession())) {
                throw new ApiException(HttpStatus.CONFLICT, "registration_exists",
                        "A registration could not be completed.");
            }
            audit.append("self-registration", "registration.create", null, requestId, "success");
            return new RegistrationDtos.SelfRegistrationResponse(registrationId);
        }
        throw new IllegalStateException("Unable to allocate a unique registration ID");
    }

    private String generateRegistrationId() {
        StringBuilder id = new StringBuilder("P-");
        for (int i = 0; i < 6; i++) {
            id.append(ID_CHARACTERS.charAt(secureRandom.nextInt(ID_CHARACTERS.length())));
        }
        return id.toString();
    }

    @Transactional
    public int importRegistrations(RegistrationDtos.ImportRequest request, String actor, String requestId) {
        var ids = new HashSet<String>();
        for (var participant : request.registrations()) {
            if (!ids.add(participant.registrationId())
                    || registrations.findStatus(participant.registrationId()).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "registration_exists",
                        "A registration could not be imported.");
            }
        }
        for (var participant : request.registrations()) {
            byte[] age = participant.age().toString().getBytes(StandardCharsets.UTF_8);
            byte[] gender = participant.gender().strip().getBytes(StandardCharsets.UTF_8);
            var encrypted = keyProvider.encryptFields(age, gender);
            registrations.insert(participant.registrationId(), encrypted.firstCiphertext(),
                    encrypted.secondCiphertext(), encrypted.wrappedDek(), encrypted.keyId());
        }
        audit.append(actor, "registrations.import", null, requestId, "success");
        return request.registrations().size();
    }

    @Transactional
    public void updateStatus(String registrationId, String status, String actor, String requestId) {
        String current = registrations.lockStatus(registrationId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "not_found", "The requested resource was not found."));
        if (!current.equals(status) && !isAllowedAdminTransition(registrationId, current, status)) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_status_transition",
                    "The registration cannot change from " + current + " to " + status + ".");
        }
        registrations.updateStatus(registrationId, status);
        if ("deactivated".equals(status)) {
            devices.findActiveByRegistration(registrationId)
                    .ifPresent(device -> devices.revokeActive(device.id()));
        }
        audit.append(actor, "registration.status.update", registrationId, requestId, "success");
    }

    // Only device registration activates a new registration. Reactivation restores the status held
    // before deactivation, which is "activated" exactly when a device was ever registered.
    private boolean isAllowedAdminTransition(String registrationId, String current, String target) {
        return switch (target) {
            case "deactivated" -> true;
            case "activated" -> "deactivated".equals(current) && devices.hasAnyDevice(registrationId);
            case "not_registered" -> "deactivated".equals(current) && !devices.hasAnyDevice(registrationId);
            default -> false;
        };
    }
}
