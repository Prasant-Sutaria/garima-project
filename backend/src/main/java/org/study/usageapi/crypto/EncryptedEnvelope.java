package org.study.usageapi.crypto;

public record EncryptedEnvelope(byte[] ciphertext, byte[] wrappedDek, String keyId) {
}
