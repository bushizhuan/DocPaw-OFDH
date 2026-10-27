package org.openofdh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.internal.JsonIO;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.Xml;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class CliSigningProcessTest {
    private static final String PASSWORD_VARIABLE = "OFDH_PROCESS_TEST_PASSWORD";
    @TempDir Path directory;

    @Test
    void preparesSignsAndChecksAcrossIndependentJavaProcesses() throws Exception {
        Path source = source();
        Path request = request();
        String sourceDigest = FileSafety.sha256(source);
        Path prepared = directory.resolve("prepared.ofd");
        Path preparedReport = directory.resolve("prepared-report.json");
        Invocation preparation =
                invoke(
                        Collections.emptyMap(),
                        "prepare",
                        "--request",
                        request.toString(),
                        "--input-root",
                        directory.toString(),
                        "--out",
                        prepared.toString(),
                        "--report",
                        preparedReport.toString());
        assertEquals(0, preparation.exitCode, preparation.stderr);
        JsonNode preparationResult = preparation.result();
        assertEquals("prepare", preparationResult.path("operation").asText());
        assertEquals("PREPARED", preparationResult.path("stage").asText());
        assertEquals("WRITTEN", preparationResult.path("reportStatus").asText());
        assertEquals(sourceDigest, preparationResult.path("sourceSha256").asText());
        String preparedDigest = FileSafety.sha256(prepared);
        assertEquals(preparedDigest, preparationResult.path("sha256").asText());
        assertReportDigest(preparedReport, preparedDigest);

        String password = "ephemeral-" + UUID.randomUUID();
        Path keystore = directory.resolve("identity.p12");
        Path trust = directory.resolve("trust.der");
        createIdentity(keystore, trust, password.toCharArray());
        String keystoreDigest = FileSafety.sha256(keystore);
        Path signed = directory.resolve("signed.ofd");
        Path signingReport = directory.resolve("signing-report.json");
        Invocation signing =
                invoke(
                        Collections.singletonMap(PASSWORD_VARIABLE, password),
                        "sign",
                        "--input",
                        prepared.toString(),
                        "--out",
                        signed.toString(),
                        "--report",
                        signingReport.toString(),
                        "--keystore",
                        keystore.toString(),
                        "--alias",
                        "signer",
                        "--password-env",
                        PASSWORD_VARIABLE);
        assertEquals(0, signing.exitCode, signing.stderr);
        assertFalse(signing.stdout.contains(password));
        assertFalse(signing.stderr.contains(password));
        JsonNode signingResult = signing.result();
        assertEquals("sign", signingResult.path("operation").asText());
        assertEquals("SIGNED", signingResult.path("stage").asText());
        assertEquals("WRITTEN", signingResult.path("reportStatus").asText());
        String signedDigest = FileSafety.sha256(signed);
        assertEquals(signedDigest, signingResult.path("sha256").asText());
        JsonNode signedReport = assertReportDigest(signingReport, signedDigest);
        JsonNode unsignedTrust =
                signedReport.path("signatureVerification").path("signatures").get(0);
        assertEquals("PASS", unsignedTrust.path("cryptographicVerification").asText());
        assertEquals("UNKNOWN", unsignedTrust.path("certificateTrust").asText());

        Path finalReport = directory.resolve("trusted-report.json");
        Invocation checking =
                invoke(
                        Collections.emptyMap(),
                        "check",
                        "--input",
                        signed.toString(),
                        "--trust",
                        trust.toString(),
                        "--report",
                        finalReport.toString());
        assertEquals(3, checking.exitCode, checking.stderr);
        JsonNode checkingResult = checking.result();
        assertEquals("check", checkingResult.path("operation").asText());
        assertEquals("INCOMPLETE", checkingResult.path("assessment").asText());
        assertEquals(signedDigest, checkingResult.path("sha256").asText());
        JsonNode report = assertReportDigest(finalReport, signedDigest);
        assertEquals(0, report.path("coverage").path("resultCounts").path("FAIL").asInt());
        assertEquals(1, report.path("signatureVerification").path("signatureCount").asInt());
        JsonNode signature = report.path("signatureVerification").path("signatures").get(0);
        assertEquals("PASS", signature.path("cryptographicVerification").asText());
        assertEquals("TRUSTED", signature.path("certificateTrust").asText());
        assertEquals("UNKNOWN", signature.path("revocation").asText());
        assertEquals(sourceDigest, FileSafety.sha256(source));
        assertEquals(preparedDigest, FileSafety.sha256(prepared));
        assertEquals(signedDigest, FileSafety.sha256(signed));
        assertEquals(keystoreDigest, FileSafety.sha256(keystore));
    }

    private JsonNode assertReportDigest(Path report, String digest) throws Exception {
        JsonNode value = JsonIO.read(report, 1L << 20);
        assertEquals(digest, value.path("artifact").path("sha256").asText());
        return value;
    }

    private Invocation invoke(Map<String, String> environment, String... arguments)
            throws Exception {
        String executable =
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Paths.get(System.getProperty("java.home"), "bin", executable);
        String classpath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(classpath);
        command.add(OfdhCommand.class.getName());
        command.addAll(Arrays.asList(arguments));
        Path stdout = directory.resolve("stdout-" + UUID.randomUUID() + ".txt");
        Path stderr = directory.resolve("stderr-" + UUID.randomUUID() + ".txt");
        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectOutput(stdout.toFile())
                        .redirectError(stderr.toFile());
        builder.environment().remove(PASSWORD_VARIABLE);
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                fail("CLI child process exceeded 60 seconds");
            }
            return new Invocation(
                    process.exitValue(),
                    new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8),
                    new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void createIdentity(Path keystore, Path trust, char[] password) throws Exception {
        BouncyCastleProvider provider = new BouncyCastleProvider();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", provider);
        generator.initialize(new ECGenParameterSpec("sm2p256v1"));
        KeyPair keys = generator.generateKeyPair();
        X500Name name = new X500Name("CN=OFD-H CLI process integration test");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder certificateBuilder =
                new JcaX509v3CertificateBuilder(
                        name,
                        new BigInteger(128, new SecureRandom()).add(BigInteger.ONE),
                        Date.from(now.minusSeconds(60)),
                        Date.from(now.plusSeconds(3600)),
                        name,
                        keys.getPublic());
        certificateBuilder.addExtension(
                Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        X509Certificate certificate =
                new JcaX509CertificateConverter()
                        .setProvider(provider)
                        .getCertificate(
                                certificateBuilder.build(
                                        new JcaContentSignerBuilder("SM3withSM2")
                                                .setProvider(provider)
                                                .build(keys.getPrivate())));
        KeyStore store = KeyStore.getInstance("PKCS12", provider);
        try {
            store.load(null, password);
            store.setKeyEntry(
                    "signer",
                    keys.getPrivate(),
                    password,
                    new java.security.cert.Certificate[] {certificate});
            try (OutputStream output = Files.newOutputStream(keystore)) {
                store.store(output, password);
            }
            Files.write(trust, certificate.getEncoded());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private Path request() throws Exception {
        DocumentRequest value =
                new DocumentRequest(
                        "0.1.0",
                        "process-record",
                        "urn:test:process",
                        "record",
                        "1",
                        new DocumentRequest.Code("format", "urn:test:type"),
                        new DocumentRequest.Identity("subject", "urn:test:identity"),
                        null,
                        new DocumentRequest.ResponsibleParty(
                                "author", "Process test author", "urn:test:identity"),
                        "2026-10-23T10:00:00+08:00",
                        Collections.singletonList("process integration test"),
                        new DocumentRequest.Code("test", "urn:test:classification"),
                        new DocumentRequest.Source("OFD", "source.ofd", null),
                        null);
        Path request = directory.resolve("request.json");
        Files.write(request, JsonIO.bytes(JsonIO.tree(value)));
        return request;
    }

    private Path source() throws Exception {
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
        Path source = directory.resolve("source.ofd");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(source))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return source;
    }

    private static final class Invocation {
        final int exitCode;
        final String stdout;
        final String stderr;

        Invocation(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        JsonNode result() throws Exception {
            return JsonIO.parse(stdout.getBytes(StandardCharsets.UTF_8));
        }
    }
}
