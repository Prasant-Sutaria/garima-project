package org.study.usageapi.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LocalKeyProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsKeysAndEncryptsValuesWithWrappedDataKeys() {
        Path keystore = temporaryDirectory.resolve("local.p12");
        String password = "local-development-password";
        var firstRun = new LocalKeyProvider(keystore.toString(), password);
        firstRun.initialize();

        byte[] value = "sensitive test value".getBytes(StandardCharsets.UTF_8);
        EncryptedEnvelope envelope = firstRun.encrypt(value);
        assertArrayEquals(value, firstRun.decrypt(envelope));
        assertNotNull(firstRun.activeSigningKey().privateKey());

        var secondRun = new LocalKeyProvider(keystore.toString(), password);
        secondRun.initialize();
        assertArrayEquals(value, secondRun.decrypt(envelope));
        assertEquals(firstRun.activeSigningKey().publicKey(), secondRun.activeSigningKey().publicKey());
    }

    @Test
    void encryptsRegistryFieldsWithOneWrappedDataKey() {
        var provider = new LocalKeyProvider(temporaryDirectory.resolve("fields.p12").toString(),
                "local-development-password");
        provider.initialize();

        EncryptedFields encrypted = provider.encryptFields(
                "28".getBytes(StandardCharsets.UTF_8),
                "prefer not to say".getBytes(StandardCharsets.UTF_8));

        assertNotEquals(Arrays.toString(encrypted.firstCiphertext()),
                Arrays.toString(encrypted.secondCiphertext()));
        assertNotEquals(0, encrypted.wrappedDek().length);
        assertEquals("local-v1", encrypted.keyId());
    }

    @Test
    void upgradesExistingKeystoreAndPersistsContactIndexKey() throws Exception {
        Path keystore = temporaryDirectory.resolve("existing.p12");
        String password = "local-development-password";
        var firstRun = new LocalKeyProvider(keystore.toString(), password);
        firstRun.initialize();
        EncryptedEnvelope existingData = firstRun.encrypt("existing".getBytes(StandardCharsets.UTF_8));

        KeyStore oldStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keystore)) {
            oldStore.load(input, password.toCharArray());
        }
        oldStore.deleteEntry("usage-api-contact-index");
        try (var output = Files.newOutputStream(keystore)) {
            oldStore.store(output, password.toCharArray());
        }

        var upgraded = new LocalKeyProvider(keystore.toString(), password);
        upgraded.initialize();
        byte[] fingerprint = upgraded.contactFingerprint("email", "person@example.test");
        var restarted = new LocalKeyProvider(keystore.toString(), password);
        restarted.initialize();
        assertArrayEquals(fingerprint, restarted.contactFingerprint("email", "person@example.test"));
        assertNotEquals(Arrays.toString(fingerprint),
                Arrays.toString(restarted.contactFingerprint("mobile", "person@example.test")));
        assertArrayEquals("existing".getBytes(StandardCharsets.UTF_8), restarted.decrypt(existingData));
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
