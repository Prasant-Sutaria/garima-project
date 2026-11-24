package org.study.usageapi.crypto;

public record EncryptedFields(byte[] firstCiphertext, byte[] secondCiphertext, byte[] wrappedDek, String keyId) {
}
