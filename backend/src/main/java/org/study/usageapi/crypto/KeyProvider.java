package org.study.usageapi.crypto;

import java.security.PublicKey;
import java.util.List;

public interface KeyProvider {

    SigningKeyMaterial activeSigningKey();

    List<SigningKeyMaterial> publishedSigningKeys();

    EncryptedEnvelope encrypt(byte[] plaintext);

    EncryptedFields encryptFields(byte[] firstPlaintext, byte[] secondPlaintext);

    byte[] contactFingerprint(String type, String normalizedValue);

    byte[] decrypt(EncryptedEnvelope envelope);

    boolean isAvailable();

    PublicKey signingPublicKey(String keyId);
}
