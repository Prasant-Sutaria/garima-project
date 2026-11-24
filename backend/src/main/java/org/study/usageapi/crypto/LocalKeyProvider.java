package org.study.usageapi.crypto;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.List;

@Component
@Profile("local")
public class LocalKeyProvider implements KeyProvider {

    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String SIGNING_ALIAS = "usage-api-signing";
    private static final String WRAPPING_ALIAS = "usage-api-wrapping";
    private static final String CONTACT_INDEX_ALIAS = "usage-api-contact-index";
    private static final String KEY_ID = "local-v1";
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BITS = 256;

    private final Path keystorePath;
    private final char[] password;
    private final SecureRandom secureRandom = new SecureRandom();
    private KeyStore keyStore;

    public LocalKeyProvider(
            @Value("${backend.crypto.keystore-path}") String keystorePath,
            @Value("${backend.crypto.keystore-password}") String password) {
        this.keystorePath = Path.of(keystorePath).toAbsolutePath().normalize();
        this.password = password.toCharArray();
    }

    @PostConstruct
    void initialize() {
        if (password.length < 20) {
            throw new IllegalStateException("LOCAL_KEYSTORE_PASSWORD must contain at least 20 characters");
        }
        try {
            keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            if (Files.exists(keystorePath)) {
                try (var input = Files.newInputStream(keystorePath)) {
                    keyStore.load(input, password);
                }
                if (!keyStore.containsAlias(SIGNING_ALIAS) || !keyStore.containsAlias(WRAPPING_ALIAS)) {
                    throw new IllegalStateException("The local keystore is missing required keys");
                }
                if (!keyStore.containsAlias(CONTACT_INDEX_ALIAS)) {
                    createContactIndexKey();
                    persist();
                }
            } else {
                keyStore.load(null, password);
                createKeys();
                persist();
            }
        } catch (GeneralSecurityException | IOException exception) {
            throw new IllegalStateException("Unable to initialize the local development keystore", exception);
        }
    }

    @Override
    public SigningKeyMaterial activeSigningKey() {
        try {
            var privateKey = (java.security.PrivateKey) keyStore.getKey(SIGNING_ALIAS, password);
            var certificate = keyStore.getCertificate(SIGNING_ALIAS);
            return new SigningKeyMaterial(KEY_ID, privateKey, certificate.getPublicKey());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to load local signing key", exception);
        }
    }

    @Override
    public List<SigningKeyMaterial> publishedSigningKeys() {
        return List.of(activeSigningKey());
    }

    @Override
    public EncryptedEnvelope encrypt(byte[] plaintext) {
        try {
            var dek = KeyGenerator.getInstance("AES");
            dek.init(AES_KEY_BITS, secureRandom);
            SecretKey dataKey = dek.generateKey();
            byte[] wrappedDek = encryptWithKey(wrappingKey(), dataKey.getEncoded());
            return new EncryptedEnvelope(encryptWithKey(dataKey, plaintext), wrappedDek, KEY_ID);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt local data", exception);
        }
    }

    @Override
    public EncryptedFields encryptFields(byte[] firstPlaintext, byte[] secondPlaintext) {
        try {
            var dek = KeyGenerator.getInstance("AES");
            dek.init(AES_KEY_BITS, secureRandom);
            SecretKey dataKey = dek.generateKey();
            return new EncryptedFields(
                    encryptWithKey(dataKey, firstPlaintext),
                    encryptWithKey(dataKey, secondPlaintext),
                    encryptWithKey(wrappingKey(), dataKey.getEncoded()),
                    KEY_ID);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt local data", exception);
        }
    }

    @Override
    public byte[] contactFingerprint(String type, String normalizedValue) {
        try {
            SecretKey key = (SecretKey) keyStore.getKey(CONTACT_INDEX_ALIAS, password);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(type.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(normalizedValue.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to index local contact information", exception);
        }
    }

    @Override
    public byte[] decrypt(EncryptedEnvelope envelope) {
        try {
            byte[] keyBytes = decryptWithKey(wrappingKey(), envelope.wrappedDek());
            var dataKey = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            return decryptWithKey(dataKey, envelope.ciphertext());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decrypt local data", exception);
        }
    }

    @Override
    public boolean isAvailable() {
        return keyStore != null;
    }

    @Override
    public java.security.PublicKey signingPublicKey(String keyId) {
        if (!KEY_ID.equals(keyId)) {
            return null;
        }
        return activeSigningKey().publicKey();
    }

    private void createKeys() throws GeneralSecurityException, IOException {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"), secureRandom);
        KeyPair signingPair = generator.generateKeyPair();
        var certificate = SelfSignedCertificate.create(signingPair);
        keyStore.setKeyEntry(SIGNING_ALIAS, signingPair.getPrivate(), password,
                new java.security.cert.Certificate[]{certificate});

        var aesGenerator = KeyGenerator.getInstance("AES");
        aesGenerator.init(AES_KEY_BITS, secureRandom);
        keyStore.setEntry(WRAPPING_ALIAS, new KeyStore.SecretKeyEntry(aesGenerator.generateKey()),
                new KeyStore.PasswordProtection(password));
        createContactIndexKey();
    }

    private void createContactIndexKey() throws GeneralSecurityException {
        var generator = KeyGenerator.getInstance("HmacSHA256");
        generator.init(256, secureRandom);
        keyStore.setEntry(CONTACT_INDEX_ALIAS, new KeyStore.SecretKeyEntry(generator.generateKey()),
                new KeyStore.PasswordProtection(password));
    }

    private SecretKey wrappingKey() throws GeneralSecurityException {
        return (SecretKey) keyStore.getKey(WRAPPING_ALIAS, password);
    }

    private byte[] encryptWithKey(SecretKey key, byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    private byte[] decryptWithKey(SecretKey key, byte[] data) throws GeneralSecurityException {
        if (data.length <= GCM_NONCE_BYTES) {
            throw new GeneralSecurityException("Invalid encrypted value");
        }
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        byte[] ciphertext = new byte[data.length - GCM_NONCE_BYTES];
        ByteBuffer.wrap(data).get(nonce).get(ciphertext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(ciphertext);
    }

    private void persist() throws GeneralSecurityException, IOException {
        Files.createDirectories(keystorePath.getParent());
        Path temporaryPath = Files.createTempFile(keystorePath.getParent(), "usage-api-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporaryPath)) {
                keyStore.store(output, password);
            }
            try {
                Files.move(temporaryPath, keystorePath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporaryPath, keystorePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryPath);
        }
    }
}
