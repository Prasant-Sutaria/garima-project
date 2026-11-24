package org.study.usageapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.study.usageapi.api.DeviceDtos;
import org.study.usageapi.persistence.DeviceRepository;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Base64;

@Component
public class DeviceKeyCodec {

    private final ObjectMapper objectMapper;
    private final ECParameterSpec curve;

    public DeviceKeyCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            curve = parameters.getParameterSpec(ECParameterSpec.class);
        } catch (Exception exception) {
            throw new IllegalStateException("P-256 is not available in this Java runtime", exception);
        }
    }

    public PublicKey decode(DeviceDtos.PublicKeyJwk jwk) {
        try {
            byte[] x = Base64.getUrlDecoder().decode(jwk.x());
            byte[] y = Base64.getUrlDecoder().decode(jwk.y());
            if (x.length != 32 || y.length != 32
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(x).equals(jwk.x())
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(y).equals(jwk.y())) {
                throw new IllegalArgumentException("Invalid P-256 coordinate length");
            }
            BigInteger xCoordinate = new BigInteger(1, x);
            BigInteger yCoordinate = new BigInteger(1, y);
            var field = (java.security.spec.ECFieldFp) curve.getCurve().getField();
            BigInteger prime = field.getP();
            if (xCoordinate.compareTo(prime) >= 0 || yCoordinate.compareTo(prime) >= 0) {
                throw new IllegalArgumentException("P-256 coordinate is outside the curve");
            }
            BigInteger left = yCoordinate.modPow(BigInteger.TWO, prime);
            BigInteger right = xCoordinate.modPow(BigInteger.valueOf(3), prime)
                    .add(curve.getCurve().getA().multiply(xCoordinate))
                    .add(curve.getCurve().getB()).mod(prime);
            if (!left.equals(right)) {
                throw new IllegalArgumentException("P-256 point is not on the curve");
            }
            var point = new java.security.spec.ECPoint(xCoordinate, yCoordinate);
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, curve));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid P-256 public key", exception);
        }
    }

    public PublicKey decodeJson(String jwkJson) {
        try {
            return decode(objectMapper.readValue(jwkJson, DeviceDtos.PublicKeyJwk.class));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid stored P-256 public key", exception);
        }
    }

    public boolean matches(String storedJwkJson, DeviceDtos.PublicKeyJwk requestedJwk) {
        try {
            return objectMapper.readValue(storedJwkJson, DeviceDtos.PublicKeyJwk.class).equals(requestedJwk);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid stored P-256 public key", exception);
        }
    }

}
