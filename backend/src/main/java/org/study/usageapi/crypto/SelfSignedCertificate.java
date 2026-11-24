package org.study.usageapi.crypto;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.Date;

final class SelfSignedCertificate {

    private SelfSignedCertificate() {
    }

    static Certificate create(KeyPair keyPair) throws CertificateException {
        try {
            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=usage-api-local");
            var builder = new JcaX509v3CertificateBuilder(
                    subject,
                    new BigInteger(160, new java.security.SecureRandom()),
                    Date.from(now.minusSeconds(60)),
                    Date.from(now.plusSeconds(365L * 24 * 60 * 60)),
                    subject,
                    keyPair.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                    .build(keyPair.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter().getCertificate(holder);
        } catch (Exception exception) {
            throw new CertificateException("Unable to create local development certificate", exception);
        }
    }
}
