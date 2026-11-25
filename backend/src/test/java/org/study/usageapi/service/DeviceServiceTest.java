package org.study.usageapi.service;

import org.junit.jupiter.api.Test;
import org.study.usageapi.api.ApiException;
import org.study.usageapi.api.DeviceDtos;
import org.study.usageapi.persistence.DeviceRepository;
import org.study.usageapi.persistence.RegistrationRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceServiceTest {

    private static final String ID = "P-AAAAAA";
    private static final DeviceDtos.RegisterRequest REQUEST = new DeviceDtos.RegisterRequest(ID,
            new DeviceDtos.PublicKeyJwk("EC", "P-256", "x", "y"), "1.0.0");

    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceService service = new DeviceService(registrations, devices, mock(DeviceKeyCodec.class));

    @Test
    void firstDeviceRegistrationActivatesTheRegistration() {
        for (String status : new String[]{"not_registered", "activated"}) {
            when(registrations.lockStatus(ID)).thenReturn(Optional.of(status));
            when(devices.findActiveByRegistration(ID)).thenReturn(Optional.empty());

            assertEquals(false, service.register(REQUEST).alreadyRegistered(), status);
        }
        verify(registrations, times(2)).activate(ID);
    }

    @Test
    void deactivatedRegistrationCannotRegisterADevice() {
        when(registrations.lockStatus(ID)).thenReturn(Optional.of("deactivated"));

        var exception = assertThrows(ApiException.class, () -> service.register(REQUEST));

        assertEquals(403, exception.status().value());
        verify(devices, never()).insert(any(), anyString(), any(), anyString());
        verify(registrations, never()).activate(anyString());
    }
}
