package org.study.usageapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.study.usageapi.api.ApiException;
import org.study.usageapi.api.RegistrationDtos;
import org.study.usageapi.crypto.EncryptedEnvelope;
import org.study.usageapi.crypto.EncryptedFields;
import org.study.usageapi.crypto.KeyProvider;
import org.study.usageapi.persistence.AuditRepository;
import org.study.usageapi.persistence.DeviceRepository;
import org.study.usageapi.persistence.RegistrationRepository;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {

    @Test
    void retriesAnIdCollisionWithoutReusingAnExistingRegistration() {
        var registrations = mock(RegistrationRepository.class);
        var keys = mock(KeyProvider.class);
        var random = mock(SecureRandom.class);
        byte[] bytes = {1};
        when(keys.contactFingerprint(anyString(), anyString())).thenReturn(bytes);
        when(registrations.contactIndexVerifier(any())).thenReturn(bytes);
        when(keys.encryptFields(any(), any())).thenReturn(new EncryptedFields(bytes, bytes, bytes, "test"));
        when(keys.encrypt(any())).thenReturn(new EncryptedEnvelope(bytes, bytes, "test"));
        when(random.nextInt(36)).thenReturn(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1);
        when(registrations.insertGenerated(anyString(), any(), any(), any(), anyString()))
                .thenReturn(false, true);
        when(registrations.insertEnrollmentDetails(anyString(), any(), any(), anyString(), any(), any(), anyInt(),
                anyString()))
                .thenReturn(true);

        var service = new RegistrationService(registrations, mock(DeviceRepository.class),
                mock(AuditRepository.class), keys, new ObjectMapper(), random);
        when(registrations.isSchemeActive(6)).thenReturn(true);
        var request = new RegistrationDtos.SelfRegistrationRequest("Example", "+14155550123",
                "person@example.test", 25, "test", "Reading", "Walking", 6, "homemaker");

        assertEquals("P-BBBBBB", service.selfRegister(request, "request-id").registrationId());
        verify(registrations, times(1)).insertGenerated(eq("P-AAAAAA"), any(), any(), any(), anyString());
        verify(registrations, times(1)).insertEnrollmentDetails(eq("P-BBBBBB"), any(), any(), anyString(),
                any(), any(), eq(6), eq("homemaker"));
    }

    @Test
    void rejectsAnInactiveScheme() {
        var registrations = mock(RegistrationRepository.class);
        var keys = mock(KeyProvider.class);
        byte[] bytes = {1};
        when(keys.contactFingerprint(anyString(), anyString())).thenReturn(bytes);
        when(registrations.contactIndexVerifier(any())).thenReturn(bytes);
        when(registrations.isSchemeActive(12)).thenReturn(false);

        var service = new RegistrationService(registrations, mock(DeviceRepository.class),
                mock(AuditRepository.class), keys, new ObjectMapper(), new SecureRandom());
        var request = new RegistrationDtos.SelfRegistrationRequest("Example", "+14155550123",
                "person@example.test", 25, "test", "Reading", "Walking", 12, "student");

        var exception = assertThrows(ApiException.class, () -> service.selfRegister(request, "request-id"));
        assertEquals("invalid_scheme", exception.code());
        verify(registrations, never()).insertGenerated(anyString(), any(), any(), any(), anyString());
    }
}
