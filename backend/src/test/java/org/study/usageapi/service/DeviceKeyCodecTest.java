package org.study.usageapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.study.usageapi.api.DeviceDtos;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceKeyCodecTest {

    @Test
    void comparesJsonbJwksIndependentlyOfDatabaseFormatting() {
        var codec = new DeviceKeyCodec(new ObjectMapper());
        var key = new DeviceDtos.PublicKeyJwk("EC", "P-256",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertTrue(codec.matches(
                "{\"crv\": \"P-256\", \"kty\": \"EC\", \"y\": \"" + key.y()
                        + "\", \"x\": \"" + key.x() + "\"}", key));
    }

    @Test
    void rejectsPublicKeyPointOutsideP256Curve() {
        var codec = new DeviceKeyCodec(new ObjectMapper());
        var key = new DeviceDtos.PublicKeyJwk("EC", "P-256",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertThrows(IllegalArgumentException.class, () -> codec.decode(key));
    }
}
