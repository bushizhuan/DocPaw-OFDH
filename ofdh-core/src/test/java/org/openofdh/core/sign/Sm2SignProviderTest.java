package org.openofdh.core.sign;

import static org.junit.jupiter.api.Assertions.*;

import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.digests.NullDigest;
import org.bouncycastle.crypto.params.ParametersWithID;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.ofdrw.gm.sm2strut.ContentInfo;
import org.ofdrw.gm.sm2strut.SignedData;
import org.ofdrw.gm.sm2strut.SignerInfo;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.Ofdh;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.w3c.dom.Element;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class Sm2SignProviderTest {
    private static final BouncyCastleProvider CRYPTO = new BouncyCastleProvider();
    private static final ResourceLimits LIMITS = ResourceLimits.defaults();
    private static final byte[] USER_ID = "1234567812345678".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void signsActualDocumentsWithMessageAndDigestEndpoints(boolean digestMode, boolean rawEncoding)
            throws Exception {
        Identity identity = identity();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Sm2SigningRequest> received = new AtomicReference<>();
        Sm2Signer signer =
                request -> {
                    calls.incrementAndGet();
                    received.set(request);
                    byte[] data = request.getData();
                    byte[] id = request.getUserId();
                    byte[] signature = sign(identity.keys, data, id, digestMode);
                    // 厂商 SDK 可能复用传入数组，修改副本不能影响封装和后续验签。
                    Arrays.fill(data, (byte) 0);
                    Arrays.fill(id, (byte) 0);
                    assertArrayEquals(USER_ID, request.getUserId());
                    return rawEncoding
                            ? Sm2Signature.raw(toRaw(signature))
                            : Sm2Signature.der(signature);
                };
        X509Certificate[] supplied = identity.chain.clone();
        Sm2SignProvider provider = provider(digestMode, supplied, signer);
        supplied[0] = identity.chain[1];
        assertEquals(0, calls.get(), "构造适配器不能调用有业务副作用的签署接口");

        Ofdh sdk = Ofdh.builder().optimizePageOrder(true).build();
        Path prepared = prepared(sdk);
        String original = FileSafety.sha256(prepared);
        Path output = directory.resolve("signed.ofd");
        SignedDocument result = sdk.sign(prepared, output, provider);

        assertEquals(1, calls.get(), "一份文档只能提交一次签名请求");
        assertEquals(original, FileSafety.sha256(prepared));
        assertEquals(FileSafety.sha256(output), result.getSha256());
        assertEquals(result.getSha256(), result.getReport().getArtifact().getSha256());
        assertEquals(
                "PASS",
                result.getReport()
                        .getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
        Sm2SigningRequest request = received.get();
        assertEquals(
                digestMode
                        ? Sm2SigningRequest.InputType.SM2_DIGEST
                        : Sm2SigningRequest.InputType.MESSAGE,
                request.getInputType());
        assertEquals(32, request.getData().length);
        assertEquals(identity.chain[0], request.getCertificate());
        assertArrayEquals(USER_ID, request.getUserId());

        try (SafePackage archive = SafePackage.open(output, LIMITS)) {
            Element body = Xml.child(archive.readXml("OFD.xml").getDocumentElement(), "DocBody");
            String list = SafePackage.resolve("OFD.xml", Xml.text(body, "Signatures"));
            Element registration =
                    Xml.child(archive.readXml(list).getDocumentElement(), "Signature");
            String descriptor = SafePackage.resolve(list, registration.getAttribute("BaseLoc"));
            byte[] message = sm3(archive.read(descriptor));
            if (!digestMode) assertArrayEquals(message, request.getData());
            String value =
                    SafePackage.resolve(
                            descriptor,
                            Xml.text(
                                    archive.readXml(descriptor).getDocumentElement(),
                                    "SignedValue"));
            SignedData signed =
                    SignedData.getInstance(
                            ContentInfo.getInstance(archive.read(value)).getContent());
            SignerInfo information = SignerInfo.getInstance(signed.getSignerInfos().getObjectAt(0));
            // 使用 BC 原语对文档重新计算的消息验签，避免只由封装器和自有检查器互证。
            SM2Signer verifier = new SM2Signer();
            verifier.init(
                    false,
                    new ParametersWithID(
                            PublicKeyFactory.createKey(identity.keys.getPublic().getEncoded()),
                            USER_ID));
            verifier.update(message, 0, message.length);
            assertTrue(verifier.verifySignature(information.getEncryptedDigest().getOctets()));
            Set<String> certificates = new HashSet<>();
            for (int index = 0; index < signed.getCertificates().size(); index++) {
                certificates.add(
                        Base64.getEncoder()
                                .encodeToString(
                                        signed.getCertificates()
                                                .getObjectAt(index)
                                                .toASN1Primitive()
                                                .getEncoded()));
            }
            assertEquals(2, certificates.size());
            for (X509Certificate certificate : identity.chain) {
                assertTrue(
                        certificates.contains(
                                Base64.getEncoder().encodeToString(certificate.getEncoded())));
            }
        }
        CheckReport trusted =
                sdk.check(output, CheckProfile.FINAL, Collections.singleton(identity.chain[1]));
        assertEquals(
                "TRUSTED",
                trusted.getSignatureVerification().getSignatures().get(0).getCertificateTrust());
        assertEquals("INCOMPLETE", trusted.getAssessment());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-key", "wrong-id", "extra-hash", "digest-as-message"})
    void rejectsCryptographicallyIncorrectResponsesWithoutPublishing(String error)
            throws Exception {
        Identity identity = identity();
        KeyPair otherKey = keys("sm2p256v1");
        AtomicInteger calls = new AtomicInteger();
        Sm2SignProvider provider =
                provider(
                        "digest-as-message".equals(error),
                        identity.chain,
                        request -> {
                            calls.incrementAndGet();
                            byte[] data = request.getData();
                            if ("extra-hash".equals(error)) data = sm3(data);
                            byte[] id =
                                    "wrong-id".equals(error)
                                            ? "incorrect-user-id"
                                                    .getBytes(StandardCharsets.US_ASCII)
                                            : request.getUserId();
                            return Sm2Signature.der(
                                    sign(
                                            "wrong-key".equals(error) ? otherKey : identity.keys,
                                            data,
                                            id,
                                            false));
                        });
        assertUnpublishedFailure(provider, "SIGNATURE_VERIFICATION_FAILED");
        assertEquals(1, calls.get(), "错误签名不能触发自动重试");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null-result", "invalid-encoding", "io-failure", "cancelled"})
    void preservesInputsWhenTheSigningEndpointCannotComplete(String failure) throws Exception {
        Identity identity = identity();
        AtomicInteger calls = new AtomicInteger();
        Sm2SignProvider provider =
                Sm2SignProvider.forMessage(
                        identity.chain,
                        request -> {
                            calls.incrementAndGet();
                            if ("null-result".equals(failure)) return null;
                            if ("invalid-encoding".equals(failure))
                                return Sm2Signature.der(new byte[] {1});
                            if ("io-failure".equals(failure)) throw new IOException("签署服务暂不可用");
                            Sm2Signature value =
                                    Sm2Signature.der(
                                            sign(
                                                    identity.keys,
                                                    request.getData(),
                                                    request.getUserId(),
                                                    false));
                            Thread.currentThread().interrupt();
                            return value;
                        });
        String expected =
                "io-failure".equals(failure)
                        ? null
                        : "cancelled".equals(failure)
                                ? "OPERATION_CANCELLED"
                                : "SIGNATURE_VALUE_INVALID";
        assertUnpublishedFailure(provider, expected);
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "wrong-curve", "wrong-usage", "wrong-issuer"})
    void rejectsUnusableCertificatesBeforeCallingTheEndpoint(String defect) throws Exception {
        Instant now = Instant.now();
        Identity identity =
                identity(
                        "wrong-curve".equals(defect) ? "secp256r1" : "sm2p256v1",
                        now.minusSeconds(3600),
                        "expired".equals(defect) ? now.minusSeconds(60) : now.plusSeconds(3600),
                        "wrong-usage".equals(defect)
                                ? KeyUsage.keyEncipherment
                                : KeyUsage.digitalSignature);
        if ("wrong-issuer".equals(defect)) identity.chain[1] = identity().chain[1];
        AtomicInteger calls = new AtomicInteger();
        OfdhException exception =
                assertThrows(
                        OfdhException.class,
                        () ->
                                Sm2SignProvider.forMessage(
                                        identity.chain,
                                        request -> {
                                            calls.incrementAndGet();
                                            return Sm2Signature.der(
                                                    sign(
                                                            identity.keys,
                                                            request.getData(),
                                                            request.getUserId(),
                                                            false));
                                        }));
        assertEquals(
                "wrong-curve".equals(defect)
                        ? "SIGNING_ALGORITHM_UNSUPPORTED"
                        : "SIGNING_CONFIGURATION",
                exception.getCode());
        assertEquals(0, calls.get());
    }

    @Test
    void appliesThePackageBudgetBeforePublishingFromTheDirectProvider() throws Exception {
        Path prepared = prepared(Ofdh.create());
        String original = FileSafety.sha256(prepared);
        int originalEntries;
        try (SafePackage archive = SafePackage.open(prepared, LIMITS)) {
            originalEntries = archive.entries().size();
        }
        ResourceLimits budget =
                new ResourceLimits(
                        LIMITS.getMaxInputBytes(),
                        LIMITS.getMaxExpandedBytes(),
                        originalEntries,
                        LIMITS.getMaxPages(),
                        LIMITS.getMaxClinicalBytes());
        Identity identity = identity();
        AtomicInteger calls = new AtomicInteger();
        Sm2SignProvider provider =
                Sm2SignProvider.forMessage(
                        identity.chain,
                        request -> {
                            calls.incrementAndGet();
                            return Sm2Signature.der(
                                    sign(
                                            identity.keys,
                                            request.getData(),
                                            request.getUserId(),
                                            false));
                        });
        Path output = directory.resolve("over-budget.ofd");
        OfdhException failure =
                assertThrows(OfdhException.class, () -> provider.sign(prepared, output, budget));
        assertEquals("LIMIT_EXCEEDED", failure.getCode());
        assertEquals(1, calls.get());
        assertEquals(original, FileSafety.sha256(prepared));
        assertFalse(Files.exists(output));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".ofdh-")));
        }
    }

    private void assertUnpublishedFailure(SignProvider provider, String expectedCode)
            throws Exception {
        Ofdh sdk = Ofdh.create();
        Path prepared = prepared(sdk);
        String before = FileSafety.sha256(prepared);
        Path output = directory.resolve("failed.ofd");
        IOException failure;
        try {
            failure = assertThrows(IOException.class, () -> sdk.sign(prepared, output, provider));
        } finally {
            // 清除本测试制造的中断，避免影响同一测试线程上的其他用例及临时目录清理。
            Thread.interrupted();
        }
        if (expectedCode != null) {
            assertTrue(failure instanceof OfdhException, "应返回可识别的签署错误码");
            assertEquals(expectedCode, ((OfdhException) failure).getCode());
        }
        assertFalse(Files.exists(output));
        assertEquals(before, FileSafety.sha256(prepared));
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".ofdh-")));
        }
    }

    private static Sm2SignProvider provider(
            boolean digestMode, X509Certificate[] chain, Sm2Signer signer) throws IOException {
        return digestMode
                ? Sm2SignProvider.forDigest(chain, signer)
                : Sm2SignProvider.forMessage(chain, signer);
    }

    private static byte[] sign(KeyPair keys, byte[] data, byte[] id, boolean prehashed)
            throws IOException {
        SM2Signer signer =
                prehashed
                        ? new SM2Signer(new NullDigest()) {
                            @Override
                            protected byte[] getZ(byte[] userId) {
                                // 摘要接口收到的 e 已包含 ZA，此处只进行 SM2 标量运算。
                                return new byte[0];
                            }
                        }
                        : new SM2Signer();
        signer.init(
                true,
                new ParametersWithID(
                        PrivateKeyFactory.createKey(keys.getPrivate().getEncoded()), id));
        signer.update(data, 0, data.length);
        try {
            return signer.generateSignature();
        } catch (CryptoException exception) {
            throw new IOException("临时身份签名失败", exception);
        }
    }

    private static byte[] sm3(byte[] bytes) throws IOException {
        try {
            return MessageDigest.getInstance("SM3", CRYPTO).digest(bytes);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IOException("测试运行时缺少 SM3", exception);
        }
    }

    private static byte[] toRaw(byte[] der) {
        ASN1Sequence values = ASN1Sequence.getInstance(der);
        byte[] raw = new byte[64];
        for (int index = 0; index < 2; index++) {
            byte[] integer =
                    ASN1Integer.getInstance(values.getObjectAt(index)).getValue().toByteArray();
            int length = Math.min(32, integer.length);
            System.arraycopy(
                    integer, integer.length - length, raw, index * 32 + 32 - length, length);
        }
        return raw;
    }

    private static Identity identity() throws Exception {
        Instant now = Instant.now();
        return identity(
                "sm2p256v1",
                now.minusSeconds(60),
                now.plusSeconds(3600),
                KeyUsage.digitalSignature);
    }

    private static Identity identity(String curve, Instant from, Instant until, int usage)
            throws Exception {
        KeyPair rootKeys = keys("sm2p256v1");
        X500Name rootName = new X500Name("CN=OFD-H adapter test issuer");
        Instant now = Instant.now();
        X509Certificate root =
                certificate(
                        rootName,
                        rootKeys,
                        rootName,
                        rootKeys,
                        now.minusSeconds(7200),
                        now.plusSeconds(7200),
                        true,
                        KeyUsage.keyCertSign);
        KeyPair signingKeys = keys(curve);
        X509Certificate leaf =
                certificate(
                        new X500Name("CN=OFD-H adapter test signer"),
                        signingKeys,
                        rootName,
                        rootKeys,
                        from,
                        until,
                        false,
                        usage);
        return new Identity(signingKeys, new X509Certificate[] {leaf, root});
    }

    private static KeyPair keys(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", CRYPTO);
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(
            X500Name subject,
            KeyPair subjectKeys,
            X500Name issuer,
            KeyPair issuerKeys,
            Instant from,
            Instant until,
            boolean ca,
            int usage)
            throws Exception {
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        issuer,
                        new BigInteger(128, new java.security.SecureRandom()).add(BigInteger.ONE),
                        Date.from(from),
                        Date.from(until),
                        subject,
                        subjectKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(usage));
        return new JcaX509CertificateConverter()
                .setProvider(CRYPTO)
                .getCertificate(
                        builder.build(
                                new JcaContentSignerBuilder("SM3withSM2")
                                        .setProvider(CRYPTO)
                                        .build(issuerKeys.getPrivate())));
    }

    private Path prepared(Ofdh sdk) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(
                "OFD.xml",
                "<ofd:OFD xmlns:ofd='"
                        + Xml.NS
                        + "' Version='1.0' DocType='OFD'><ofd:DocBody><ofd:DocInfo>"
                        + "<ofd:DocID>0123456789ABCDEF0123456789ABCDEF</ofd:DocID></ofd:DocInfo>"
                        + "<ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
        entries.put(
                "Doc_0/Document.xml",
                "<ofd:Document xmlns:ofd='"
                        + Xml.NS
                        + "'><ofd:CommonData><ofd:MaxUnitID>2</ofd:MaxUnitID><ofd:PageArea><ofd:PhysicalBox>0"
                        + " 0 210"
                        + " 297</ofd:PhysicalBox></ofd:PageArea></ofd:CommonData><ofd:Pages><ofd:Page"
                        + " ID='1'"
                        + " BaseLoc='Pages/Page_0/Content.xml'/></ofd:Pages></ofd:Document>");
        entries.put(
                "Doc_0/Pages/Page_0/Content.xml",
                "<ofd:Page xmlns:ofd='"
                        + Xml.NS
                        + "'><ofd:Area><ofd:PhysicalBox>0 0 210 297</ofd:PhysicalBox></ofd:Area>"
                        + "<ofd:Content><ofd:Layer ID='2'/></ofd:Content></ofd:Page>");
        Path source = directory.resolve("source.ofd");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(source))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        DocumentRequest request =
                new DocumentRequest(
                        "0.1.0",
                        "format-record",
                        "format-source",
                        "format-record",
                        "1",
                        new DocumentRequest.Code("format", "test"),
                        new DocumentRequest.Identity("subject", "test"),
                        null,
                        new DocumentRequest.ResponsibleParty("author", "签署格式验收", "test"),
                        "2026-01-01T00:00:00Z",
                        Collections.singletonList("签署格式验收"),
                        new DocumentRequest.Code("internal", "test"),
                        new DocumentRequest.Source("OFD", "source.ofd", null),
                        null);
        return sdk.prepare(request, directory, directory.resolve("prepared.ofd")).getPath();
    }

    private static final class Identity {
        private final KeyPair keys;
        private final X509Certificate[] chain;

        private Identity(KeyPair keys, X509Certificate[] chain) {
            this.keys = keys;
            this.chain = chain;
        }
    }
}
