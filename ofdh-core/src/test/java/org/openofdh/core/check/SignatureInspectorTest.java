package org.openofdh.core.check;

import static org.junit.jupiter.api.Assertions.*;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ofdrw.gm.sm2strut.ContentInfo;
import org.ofdrw.gm.sm2strut.SignedData;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.openofdh.core.sign.Pkcs12SignProvider;
import org.openofdh.core.sign.SigningService;
import org.w3c.dom.Element;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class SignatureInspectorTest {
    @TempDir Path directory;

    @Test
    void verifiesRealSm2SignaturesAndRejectsContentAndSignerSetTampering() throws Exception {
        Path prepared = write("prepared.ofd", CheckServiceTest.entries());
        Identity identity = identity();
        Path signed = directory.toRealPath().resolve("signed.ofd");
        new SigningService(ResourceLimits.defaults())
                .sign(
                        prepared,
                        signed,
                        Pkcs12SignProvider.load(identity.file, identity.password, "signer"));
        CheckService checker = new CheckService(ResourceLimits.defaults());
        CheckReport report = checker.check(signed, CheckProfile.FINAL);
        assertEquals("INCOMPLETE", report.getAssessment());
        assertEquals("PASS", CheckServiceTest.result(report, "PKG-002"));
        assertEquals(1, report.getSignatureVerification().getSignatureCount());
        assertEquals(
                "PASS",
                report.getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
        assertEquals(
                "UNKNOWN",
                report.getSignatureVerification().getSignatures().get(0).getCertificateTrust());
        assertEquals("PASS", CheckServiceTest.result(report, "SIGN-001"));
        CheckReport trusted =
                checker.check(
                        signed, CheckProfile.FINAL, Collections.singleton(identity.certificate));
        assertEquals(
                "TRUSTED",
                trusted.getSignatureVerification().getSignatures().get(0).getCertificateTrust());
        assertEquals(
                "UNKNOWN",
                trusted.getSignatureVerification().getSignatures().get(0).getRevocation());

        Map<String, byte[]> altered = read(signed);
        byte[] page = altered.get("Doc_0/Pages/Page_0/Content.xml");
        byte[] changedPage = java.util.Arrays.copyOf(page, page.length + 1);
        changedPage[page.length] = 32;
        altered.put("Doc_0/Pages/Page_0/Content.xml", changedPage);
        CheckReport tampered =
                checker.check(write("changed-page.ofd", altered), CheckProfile.FINAL);
        assertEquals(
                "FAIL",
                tampered.getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
        assertEquals("ISSUES_FOUND", tampered.getAssessment());

        Map<String, byte[]> appended = read(signed);
        appended.put("Doc_0/Attachs/unprotected.bin", new byte[] {1, 2, 3});
        CheckReport uncovered =
                checker.check(write("unprotected-member.ofd", appended), CheckProfile.FINAL);
        assertEquals(
                "PASS",
                uncovered
                        .getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
        assertEquals("FAIL", CheckServiceTest.result(uncovered, "SIGN-001"));

        Map<String, byte[]> repeated = read(signed);
        String listPath;
        try (SafePackage archive = SafePackage.open(signed, ResourceLimits.defaults())) {
            Element body = Xml.child(archive.readXml("OFD.xml").getDocumentElement(), "DocBody");
            listPath = SafePackage.resolve("OFD.xml", Xml.text(body, "Signatures"));
        }
        org.w3c.dom.Document list = Xml.parse(repeated.get(listPath));
        Element registration = Xml.child(list.getDocumentElement(), "Signature");
        Element duplicate = (Element) registration.cloneNode(true);
        duplicate.setAttribute("ID", "different-registration");
        duplicate.setAttribute(
                "BaseLoc",
                "/" + SafePackage.resolve(listPath, registration.getAttribute("BaseLoc")));
        list.getDocumentElement().appendChild(duplicate);
        repeated.put(listPath, Xml.serialize(list));
        CheckReport repeatedReport =
                checker.check(write("repeated-signature.ofd", repeated), CheckProfile.FINAL);
        assertEquals("FAILED", repeatedReport.getSignatureVerification().getEnumeration());
        assertNull(repeatedReport.getSignatureVerification().getSignatureCount());
        assertEquals("FAIL", CheckServiceTest.result(repeatedReport, "OFDH-012"));

        Map<String, byte[]> emptySigner = read(signed);
        String signedValuePath = signedValuePath(signed);
        ContentInfo content = ContentInfo.getInstance(emptySigner.get(signedValuePath));
        SignedData signedData = SignedData.getInstance(content.getContent());
        signedData.setSignerInfos(new DERSet());
        emptySigner.put(
                signedValuePath,
                new ContentInfo(content.getContentType(), signedData).getEncoded());
        CheckReport empty =
                checker.check(write("empty-signer.ofd", emptySigner), CheckProfile.FINAL);
        assertEquals(
                "FAIL",
                empty.getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
    }

    @Test
    void carriesTheIntermediateChainForOfflinePkixValidation() throws Exception {
        BouncyCastleProvider provider = new BouncyCastleProvider();
        KeyPair rootKey = keyPair(provider);
        KeyPair intermediateKey = keyPair(provider);
        KeyPair signerKey = keyPair(provider);
        X500Name rootName = new X500Name("CN=Regression root CA");
        X500Name intermediateName = new X500Name("CN=Regression intermediate CA");
        X500Name signerName = new X500Name("CN=Regression document signer");
        X509Certificate root =
                issue(provider, rootName, rootKey.getPrivate(), rootName, rootKey, 1, 1);
        X509Certificate intermediate =
                issue(
                        provider,
                        rootName,
                        rootKey.getPrivate(),
                        intermediateName,
                        intermediateKey,
                        2,
                        0);
        X509Certificate signer =
                issue(
                        provider,
                        intermediateName,
                        intermediateKey.getPrivate(),
                        signerName,
                        signerKey,
                        3,
                        -1);
        char[] password = "temporary-chain-key".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12", provider);
        store.load(null, password);
        store.setKeyEntry(
                "signer",
                signerKey.getPrivate(),
                password,
                new java.security.cert.Certificate[] {signer, intermediate, root});
        Path keyFile = directory.resolve("chain.p12");
        try (OutputStream output = Files.newOutputStream(keyFile)) {
            store.store(output, password);
        }
        Path signed = directory.toRealPath().resolve("chain.ofd");
        new SigningService(ResourceLimits.defaults())
                .sign(
                        write("chain-input.ofd", CheckServiceTest.entries()),
                        signed,
                        Pkcs12SignProvider.load(keyFile, password, "signer"));
        CheckService checker = new CheckService(ResourceLimits.defaults());
        CheckReport trusted =
                checker.check(signed, CheckProfile.FINAL, Collections.singleton(root));
        assertEquals(
                "PASS",
                trusted.getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
        assertEquals(
                "TRUSTED",
                trusted.getSignatureVerification().getSignatures().get(0).getCertificateTrust());

        Map<String, byte[]> withoutIntermediate = read(signed);
        String valuePath = signedValuePath(signed);
        ContentInfo content = ContentInfo.getInstance(withoutIntermediate.get(valuePath));
        SignedData signedData = SignedData.getInstance(content.getContent());
        assertEquals(3, signedData.getCertificates().size());
        ASN1EncodableVector incompleteChain = new ASN1EncodableVector();
        incompleteChain.add(
                org.bouncycastle.asn1.x509.Certificate.getInstance(signer.getEncoded()));
        incompleteChain.add(org.bouncycastle.asn1.x509.Certificate.getInstance(root.getEncoded()));
        signedData.setCertificates(new DERSet(incompleteChain));
        withoutIntermediate.put(
                valuePath, new ContentInfo(content.getContentType(), signedData).getEncoded());
        CheckReport incomplete =
                checker.check(
                        write("missing-intermediate.ofd", withoutIntermediate),
                        CheckProfile.FINAL,
                        Collections.singleton(root));
        assertEquals(
                "PASS",
                incomplete
                        .getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
        assertEquals(
                "UNTRUSTED",
                incomplete.getSignatureVerification().getSignatures().get(0).getCertificateTrust());
        assertEquals("ISSUES_FOUND", incomplete.getAssessment());
    }

    private static KeyPair keyPair(BouncyCastleProvider provider) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", provider);
        generator.initialize(new ECGenParameterSpec("sm2p256v1"));
        return generator.generateKeyPair();
    }

    private static X509Certificate issue(
            BouncyCastleProvider provider,
            X500Name issuer,
            PrivateKey issuerKey,
            X500Name subject,
            KeyPair subjectKey,
            int serial,
            int caDepth)
            throws Exception {
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        issuer,
                        BigInteger.valueOf(serial),
                        new Date(now - 60_000),
                        new Date(now + 86_400_000),
                        subject,
                        subjectKey.getPublic());
        builder.addExtension(
                Extension.basicConstraints,
                true,
                caDepth < 0 ? new BasicConstraints(false) : new BasicConstraints(caDepth));
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(
                        caDepth < 0
                                ? KeyUsage.digitalSignature
                                : KeyUsage.keyCertSign | KeyUsage.cRLSign));
        return new JcaX509CertificateConverter()
                .setProvider(provider)
                .getCertificate(
                        builder.build(
                                new JcaContentSignerBuilder("SM3withSM2")
                                        .setProvider(provider)
                                        .build(issuerKey)));
    }

    private Identity identity() throws Exception {
        BouncyCastleProvider provider = new BouncyCastleProvider();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", provider);
        generator.initialize(new ECGenParameterSpec("sm2p256v1"));
        KeyPair pair = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name subject = new X500Name("CN=Signature regression test");
        X509Certificate certificate =
                new JcaX509CertificateConverter()
                        .setProvider(provider)
                        .getCertificate(
                                new JcaX509v3CertificateBuilder(
                                                subject,
                                                BigInteger.ONE,
                                                new Date(now - 60_000),
                                                new Date(now + 86_400_000),
                                                subject,
                                                pair.getPublic())
                                        .build(
                                                new JcaContentSignerBuilder("SM3withSM2")
                                                        .setProvider(provider)
                                                        .build(pair.getPrivate())));
        char[] password = "temporary-test-key".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12", provider);
        store.load(null, password);
        store.setKeyEntry(
                "signer",
                pair.getPrivate(),
                password,
                new java.security.cert.Certificate[] {certificate});
        Path file = directory.resolve("signer.p12");
        try (OutputStream output = Files.newOutputStream(file)) {
            store.store(output, password);
        }
        return new Identity(file, password, certificate);
    }

    private Path write(String name, Map<String, byte[]> entries) throws Exception {
        Path file = directory.resolve(name);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return file;
    }

    private static Map<String, byte[]> read(Path file) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (SafePackage pkg = SafePackage.open(file, ResourceLimits.defaults())) {
            for (String entry : pkg.entries()) entries.put(entry, pkg.read(entry));
        }
        return entries;
    }

    private static String signedValuePath(Path file) throws Exception {
        try (SafePackage pkg = SafePackage.open(file, ResourceLimits.defaults())) {
            Element body = Xml.child(pkg.readXml("OFD.xml").getDocumentElement(), "DocBody");
            String list = SafePackage.resolve("OFD.xml", Xml.text(body, "Signatures"));
            Element signature = Xml.child(pkg.readXml(list).getDocumentElement(), "Signature");
            String descriptor = SafePackage.resolve(list, signature.getAttribute("BaseLoc"));
            return SafePackage.resolve(
                    descriptor,
                    Xml.text(pkg.readXml(descriptor).getDocumentElement(), "SignedValue"));
        }
    }

    private static final class Identity {
        final Path file;
        final char[] password;
        final X509Certificate certificate;

        Identity(Path file, char[] password, X509Certificate certificate) {
            this.file = file;
            this.password = password;
            this.certificate = certificate;
        }
    }
}
