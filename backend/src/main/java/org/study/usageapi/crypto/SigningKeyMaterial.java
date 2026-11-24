package org.study.usageapi.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;

public record SigningKeyMaterial(String keyId, PrivateKey privateKey, PublicKey publicKey) {
}
