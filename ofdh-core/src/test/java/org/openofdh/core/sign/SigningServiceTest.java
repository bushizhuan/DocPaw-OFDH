package org.openofdh.core.sign;

import static org.junit.jupiter.api.Assertions.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.check.CheckService;
import org.openofdh.core.internal.JsonIO;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.openofdh.core.prepare.PrepareService;
import org.openofdh.core.validation.ClinicalSchemaRegistry;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

class SigningServiceTest {
    @TempDir Path directory;
    private X509Certificate certificate;
    private static final ResourceLimits LIMITS = ResourceLimits.defaults();

    @Test
    void signsVerifiesAndDetectsTampering() throws Exception {
        Path prepared = prepared();
        String original = FileSafety.sha256(prepared);
        Path target = directory.resolve("signed.ofd");
        SignedDocument result = new SigningService(LIMITS).sign(prepared, target, identity());
        assertEquals(original, FileSafety.sha256(prepared));
        assertEquals(FileSafety.sha256(target), result.getSha256());
        assertEquals(result.getSha256(), result.getReport().getArtifact().getSha256());
        assertEquals(
                "PASS",
                result.getReport()
                        .getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
        assertEquals(
                "UNKNOWN",
                result.getReport()
                        .getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCertificateTrust());
        CheckReport trusted =
                new CheckService(LIMITS)
                        .check(target, CheckProfile.FINAL, Collections.singleton(certificate));
        assertEquals(
                "TRUSTED",
                trusted.getSignatureVerification().getSignatures().get(0).getCertificateTrust());
        assertEquals("INCOMPLETE", trusted.getAssessment());
        Path report = directory.resolve("report.json");
        Files.write(report, JsonIO.bytes(trusted));
        assertTrue(JsonIO.read(report, 1L << 20).get("results").size() > 0);

        Path tampered = directory.resolve("tampered.ofd");
        try (SafePackage archive = SafePackage.open(target, LIMITS)) {
            byte[] page = archive.read("Doc_0/Pages/Page_0/Content.xml");
            archive.write(
                    "Doc_0/Pages/Page_0/Content.xml",
                    new String(page, StandardCharsets.UTF_8)
                            .replace("210 297", "211 297")
                            .getBytes(StandardCharsets.UTF_8));
            archive.publish(tampered);
        }
        CheckReport damaged = new CheckService(LIMITS).check(tampered, CheckProfile.FINAL);
        assertEquals("ISSUES_FOUND", damaged.getAssessment());
        assertEquals(
                "FAIL",
                damaged.getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesExistingOutputAndRejectsAlreadySignedInput(boolean optimizePageOrder)
            throws Exception {
        Path prepared = prepared();
        Pkcs12SignProvider provider = identity();
        SigningService service = new SigningService(LIMITS, optimizePageOrder);
        Path output = directory.resolve("signed.ofd");
        service.sign(prepared, output, provider);
        String hash = FileSafety.sha256(output);
        assertEquals(
                "OUTPUT_EXISTS",
                assertThrows(OfdhException.class, () -> service.sign(prepared, output, provider))
                        .getCode());
        assertEquals(hash, FileSafety.sha256(output));
        assertThrows(
                OfdhException.class,
                () -> service.sign(output, directory.resolve("signed-again.ofd"), provider));
        assertFalse(Files.exists(directory.resolve("signed-again.ofd")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAProviderThatSignsChangedContent(boolean optimizePageOrder) throws Exception {
        Path prepared = prepared();
        Pkcs12SignProvider provider = identity();
        Path changed = directory.resolve("changed.ofd");
        try (SafePackage archive = SafePackage.open(prepared, LIMITS)) {
            org.w3c.dom.Document entry = archive.readXml("OFD.xml");
            Xml.child(
                            Xml.child(Xml.child(entry.getDocumentElement(), "DocBody"), "DocInfo"),
                            "Author")
                    .setTextContent("Changed author");
            archive.writeXml("OFD.xml", entry);
            archive.publish(changed);
        }
        Path output = directory.resolve("changed-signed.ofd");
        OfdhException failure =
                assertThrows(
                        OfdhException.class,
                        () ->
                                new SigningService(LIMITS, optimizePageOrder)
                                        .sign(
                                                prepared,
                                                output,
                                                (source, target, limits) ->
                                                        provider.sign(changed, target, limits)));
        assertEquals("SIGN_PROVIDER_CHANGED_CONTENT", failure.getCode());
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesSignedMembersAndOptimizesPageOrderOnlyWhenRequested(boolean optimizePageOrder)
            throws Exception {
        Path prepared = prepared(true);
        String preparedHash = FileSafety.sha256(prepared);
        Pkcs12SignProvider provider = identity();
        AtomicReference<String> providerHash = new AtomicReference<>();
        AtomicReference<Map<String, String>> signedMembers = new AtomicReference<>();
        AtomicReference<List<String>> providerOrder = new AtomicReference<>();
        Path target = directory.resolve("page-ordered.ofd");
        SignedDocument result =
                new SigningService(LIMITS, optimizePageOrder)
                        .sign(
                                prepared,
                                target,
                                (input, output, limits) -> {
                                    Path signed = output.resolveSibling("before-reorder.ofd");
                                    provider.sign(input, signed, limits);
                                    repackInReverseOrder(signed, output);
                                    providerHash.set(FileSafety.sha256(output));
                                    signedMembers.set(memberHashes(output));
                                    providerOrder.set(entryOrder(output));
                                });

        assertEquals(preparedHash, FileSafety.sha256(prepared));
        assertEquals(signedMembers.get(), memberHashes(target));
        assertEquals(FileSafety.sha256(target), result.getSha256());
        assertEquals(result.getSha256(), result.getReport().getArtifact().getSha256());
        assertEquals("page-ordered.ofd", result.getReport().getArtifact().getName());
        assertEquals(1, result.getReport().getSignatureVerification().getSignatureCount());
        assertEquals(
                "PASS",
                result.getReport()
                        .getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());

        List<String> actualOrder = entryOrder(target);
        if (optimizePageOrder) {
            assertNotEquals(providerHash.get(), result.getSha256());
            assertEquals("OFD.xml", actualOrder.get(0));
            assertEquals("Doc_0/Document.xml", actualOrder.get(1));
            String firstPage = "Doc_0/Pages/Page_0/Content.xml";
            String secondPage = "Doc_0/Pages/Page_2/Content.xml";
            String thirdPage = "Doc_0/Pages/Page_10/Content.xml";
            assertBefore(actualOrder, firstPage, secondPage);
            assertBefore(actualOrder, secondPage, thirdPage);
            assertBefore(actualOrder, thirdPage, "Doc_0/Attachs/archive-note.txt");
        } else {
            assertEquals(providerHash.get(), result.getSha256());
            assertEquals(providerOrder.get(), actualOrder);
        }
    }

    @Test
    void allowsPreparedAsTheFinalOutputFilename() throws Exception {
        Path prepared = prepared();
        Path target = Files.createDirectory(directory.resolve("published")).resolve("prepared.ofd");
        SignedDocument result = new SigningService(LIMITS).sign(prepared, target, identity());
        assertEquals(FileSafety.sha256(target), result.getReport().getArtifact().getSha256());
        assertEquals("prepared.ofd", result.getReport().getArtifact().getName());
        assertEquals(
                "PASS",
                result.getReport()
                        .getSignatureVerification()
                        .getSignatures()
                        .get(0)
                        .getCryptographicVerification());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Signatures.xml", "Doc_0/Security/catalog.xml"})
    void rejectsSignatureReferencesBeforeCallingTheProvider(String reference) throws Exception {
        Path prepared = prepared();
        Path source = directory.resolve("signature-reference.ofd");
        try (SafePackage archive = SafePackage.open(prepared, LIMITS)) {
            org.w3c.dom.Document entry = archive.readXml("OFD.xml");
            Xml.append(Xml.child(entry.getDocumentElement(), "DocBody"), "Signatures", reference);
            archive.writeXml("OFD.xml", entry);
            archive.publish(source);
        }
        Path output = directory.resolve("signed.ofd");
        OfdhException failure =
                assertThrows(
                        OfdhException.class,
                        () ->
                                new SigningService(LIMITS)
                                        .sign(
                                                source,
                                                output,
                                                (input, target, limits) ->
                                                        fail(
                                                                "An existing signature reference"
                                                                    + " must prevent signing")));
        assertEquals("SIGNED_INPUT_REQUIRES_DERIVATION", failure.getCode());
        assertFalse(Files.exists(output));
    }

    @Test
    void doesNotPublishWhenPrivateWorkCannotBeCleaned() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name").startsWith("Windows"),
                "Windows read-only attributes prevent deletion; POSIX directory permissions have"
                        + " different semantics");
        Path prepared = prepared();
        Pkcs12SignProvider identity = identity();
        AtomicReference<Path> guard = new AtomicReference<>();
        Path target = directory.resolve("signed.ofd");
        try {
            assertThrows(
                    java.io.IOException.class,
                    () ->
                            new SigningService(LIMITS)
                                    .sign(
                                            prepared,
                                            target,
                                            (input, output, limits) -> {
                                                identity.sign(input, output, limits);
                                                Path locked =
                                                        input.getParent()
                                                                .resolve("cleanup-blocker");
                                                Files.write(locked, new byte[] {1});
                                                guard.set(locked);
                                                Files.setAttribute(locked, "dos:readonly", true);
                                            }));
            assertFalse(Files.exists(target));
        } finally {
            if (guard.get() != null) {
                Files.setAttribute(guard.get(), "dos:readonly", false);
                FileSafety.deleteTree(guard.get().getParent());
            }
        }
    }

    private Pkcs12SignProvider identity() throws Exception {
        BouncyCastleProvider provider = new BouncyCastleProvider();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", provider);
        generator.initialize(new ECGenParameterSpec("sm2p256v1"));
        KeyPair keys = generator.generateKeyPair();
        X500Name name = new X500Name("CN=OFD-H format integration test");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        name,
                        new BigInteger(128, new java.security.SecureRandom()),
                        Date.from(now.minusSeconds(60)),
                        Date.from(now.plusSeconds(3600)),
                        name,
                        keys.getPublic());
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        certificate =
                new JcaX509CertificateConverter()
                        .setProvider(provider)
                        .getCertificate(
                                builder.build(
                                        new JcaContentSignerBuilder("SM3withSM2")
                                                .setProvider(provider)
                                                .build(keys.getPrivate())));
        KeyStore store = KeyStore.getInstance("PKCS12", provider);
        char[] password = "test-only-ephemeral".toCharArray();
        store.load(null, password);
        store.setKeyEntry(
                "signer",
                keys.getPrivate(),
                password,
                new java.security.cert.Certificate[] {certificate});
        Path keyFile = directory.resolve("ephemeral.p12");
        try (OutputStream stream = Files.newOutputStream(keyFile)) {
            store.store(stream, password);
        }
        return Pkcs12SignProvider.load(keyFile, password, "signer");
    }

    private Path prepared() throws Exception {
        return prepared(false);
    }

    private Path prepared(boolean includeExtraPagesAndAttachment) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(
                "OFD.xml",
                "<ofd:OFD xmlns:ofd='"
                        + Xml.NS
                        + "' Version='1.0'"
                        + " DocType='OFD'><ofd:DocBody><ofd:DocInfo><ofd:DocID>0123456789ABCDEF0123456789ABCDEF</ofd:DocID></ofd:DocInfo><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
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
                        + "'><ofd:Area><ofd:PhysicalBox>0 0 210"
                        + " 297</ofd:PhysicalBox></ofd:Area><ofd:Content><ofd:Layer"
                        + " ID='2'/></ofd:Content></ofd:Page>");
        if (includeExtraPagesAndAttachment) {
            entries.put(
                    "Doc_0/Document.xml",
                    entries.get("Doc_0/Document.xml")
                            .replace(
                                    "<ofd:MaxUnitID>2</ofd:MaxUnitID>",
                                    "<ofd:MaxUnitID>7</ofd:MaxUnitID>")
                            .replace(
                                    "</ofd:Pages>",
                                    "<ofd:Page ID='3' BaseLoc='Pages/Page_2/Content.xml'/><ofd:Page"
                                        + " ID='5' BaseLoc='Pages/Page_10/Content.xml'/>"
                                        + "</ofd:Pages>")
                            .replace(
                                    "</ofd:Document>",
                                    "<ofd:Attachments>Attachs/Attachments.xml</ofd:Attachments>"
                                            + "</ofd:Document>"));
            entries.put(
                    "Doc_0/Pages/Page_2/Content.xml",
                    entries.get("Doc_0/Pages/Page_0/Content.xml").replace("ID='2'", "ID='4'"));
            entries.put(
                    "Doc_0/Pages/Page_10/Content.xml",
                    entries.get("Doc_0/Pages/Page_0/Content.xml").replace("ID='2'", "ID='6'"));
            entries.put(
                    "Doc_0/Attachs/Attachments.xml",
                    "<ofd:Attachments xmlns:ofd='"
                            + Xml.NS
                            + "'><ofd:Attachment ID='7' Name='归档说明' Format='txt'>"
                            + "<ofd:FileLoc>archive-note.txt</ofd:FileLoc>"
                            + "</ofd:Attachment></ofd:Attachments>");
            entries.put("Doc_0/Attachs/archive-note.txt", "签署与页面顺序回归测试附件。");
        }
        Path source = directory.resolve("source.ofd");
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(source))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                stream.putNextEntry(new ZipEntry(entry.getKey()));
                stream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                stream.closeEntry();
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
                        new DocumentRequest.ResponsibleParty("author", "Format author", "test"),
                        "2026-01-01T00:00:00Z",
                        Collections.singletonList("format verification"),
                        new DocumentRequest.Code("internal", "test"),
                        new DocumentRequest.Source("OFD", "source.ofd", null),
                        null);
        return new PrepareService(Collections.emptyList(), new ClinicalSchemaRegistry(), LIMITS)
                .prepare(request, directory, directory.resolve("prepared.ofd"))
                .getPath();
    }

    private static void repackInReverseOrder(Path source, Path target) throws java.io.IOException {
        try (SafePackage archive = SafePackage.open(source, LIMITS);
                ZipOutputStream output =
                        new ZipOutputStream(
                                Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            List<String> entries = new ArrayList<>(archive.entries());
            entries.sort(Collections.reverseOrder());
            output.setLevel(1);
            for (String entry : entries) {
                output.putNextEntry(new ZipEntry(entry));
                Files.copy(archive.path(entry), output);
                output.closeEntry();
            }
        }
    }

    private static Map<String, String> memberHashes(Path source) throws java.io.IOException {
        Map<String, String> hashes = new LinkedHashMap<>();
        try (SafePackage archive = SafePackage.open(source, LIMITS)) {
            for (String entry : archive.entries()) {
                hashes.put(entry, FileSafety.sha256(archive.path(entry)));
            }
        }
        return hashes;
    }

    private static List<String> entryOrder(Path source) throws java.io.IOException {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream input =
                new ZipInputStream(Files.newInputStream(source), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) entries.add(entry.getName());
                input.closeEntry();
            }
        }
        return entries;
    }

    private static void assertBefore(List<String> entries, String earlier, String later) {
        assertTrue(entries.contains(earlier), "输出缺少预期成员：" + earlier);
        assertTrue(entries.contains(later), "输出缺少预期成员：" + later);
        assertTrue(entries.indexOf(earlier) < entries.indexOf(later), "包内成员未遵循页面依赖顺序");
    }
}
