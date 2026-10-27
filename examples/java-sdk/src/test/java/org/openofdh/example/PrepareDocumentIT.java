package org.openofdh.example;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.Ofdh;
import org.openofdh.core.validation.ClinicalSchemas;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

class PrepareDocumentIT {
    private static final ObjectMapper JSON =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    @TempDir static Path installation;
    @TempDir Path directory;
    private static Path application;

    @BeforeAll
    static void copyRuntimeOutsideTheSourceTree() throws Exception {
        String artifact = System.getProperty("consumer.jar");
        assertNotNull(artifact, "请使用 mvn verify 执行打包后的接入测试");
        Path built = Paths.get(artifact);
        assertTrue(Files.isRegularFile(built), "接入工程必须先完成打包");
        String installationName = "SDK 接入工程";
        String nativeEncoding =
                System.getProperty(
                        "native.encoding",
                        System.getProperty("sun.jnu.encoding", Charset.defaultCharset().name()));
        // Windows 启动器按本机编码处理路径；file.encoding 不改变这个原生入口。
        if (System.getProperty("os.name").startsWith("Windows")
                && !Charset.forName(nativeEncoding).newEncoder().canEncode(installationName)) {
            installationName = "SDK integration";
        }
        Path installed = Files.createDirectory(installation.resolve(installationName));
        application = Files.copy(built, installed.resolve(built.getFileName()));
        Path dependencies = Files.createDirectory(installed.resolve("dependency"));
        try (Stream<Path> jars = Files.list(built.getParent().resolve("dependency"))) {
            for (Path jar : (Iterable<Path>) jars::iterator) {
                assertTrue(Files.isRegularFile(jar), "运行依赖目录只能包含文件");
                Files.copy(jar, dependencies.resolve(jar.getFileName()));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"OFD,false,false", "OFD,true,true", "PDF,false,true", "PDF,true,false"})
    void preparesActualDocumentsUsingInstalledSdk(
            String format, boolean optimize, boolean withClinicalData) throws Exception {
        Path sdk =
                Paths.get(Ofdh.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isRegularFile(sdk), "接入测试必须从已安装的 SDK JAR 加载类");
        assertTrue(sdk.getFileName().toString().startsWith("ofdh-core-0.2.0"));
        Path source = "PDF".equals(format) ? pdf() : ofd();
        byte[] original = Files.readAllBytes(source);
        Path request = request(format, source);
        byte[] clinical = withClinicalData ? attachClinicalData(request) : null;
        Path output = directory.resolve("prepared.ofd");
        Path report = directory.resolve("report.json");
        Run run = execute(arguments(request, output, report, optimize));

        assertEquals(0, run.status, run.stderr);
        assertTrue(Files.isRegularFile(output));
        assertArrayEquals(original, Files.readAllBytes(source));
        JsonNode summary = lastJson(run.stdout);
        JsonNode saved = JSON.readTree(report.toFile());
        assertEquals("PREPARED", summary.path("code").asText());
        assertTrue(summary.path("artifactCreated").asBoolean());
        assertEquals("WRITTEN", saved.path("reportStatus").asText());
        assertEquals("openofdh-prepared", saved.at("/inspection/ruleSet/id").asText());
        assertEquals(
                summary.path("sha256").asText(), saved.at("/inspection/artifact/sha256").asText());
        assertTrue(saved.at("/validation/valid").asBoolean());
        for (JsonNode rule : saved.at("/inspection/results")) {
            assertNotEquals("FAIL", rule.path("result").asText(), rule.toString());
        }
        if (withClinicalData) {
            assertArrayEquals(clinical, Files.readAllBytes(directory.resolve("clinical.json")));
            try (ZipFile archive = new ZipFile(output.toFile())) {
                ZipEntry attachment = archive.getEntry("Doc_0/Attachs/Attach_0.json");
                assertNotNull(attachment, "生成文档必须包含机读 JSON 附件");
                byte[] embedded = new byte[clinical.length];
                try (DataInputStream input =
                        new DataInputStream(archive.getInputStream(attachment))) {
                    input.readFully(embedded);
                    assertEquals(-1, input.read(), "附件不应附加额外字节");
                }
                assertArrayEquals(clinical, embedded);
            }
        }
    }

    @Test
    void reportsFieldErrorsWithoutEchoingValues() throws Exception {
        Path request = request("OFD", ofd());
        ObjectNode invalid = (ObjectNode) JSON.readTree(request.toFile());
        invalid.remove("patient");
        invalid.put("createdAt", "private-document-value");
        JSON.writeValue(request.toFile(), invalid);
        Path output = directory.resolve("invalid.ofd");
        Path report = directory.resolve("invalid.json");
        Run run = execute(arguments(request, output, report, false));

        assertEquals(2, run.status);
        assertFalse(Files.exists(output));
        JsonNode saved = JSON.readTree(report.toFile());
        assertEquals("REQUEST_INVALID", saved.path("code").asText());
        assertFalse(saved.path("artifactCreated").asBoolean());
        List<String> paths = new ArrayList<>();
        saved.path("issues").forEach(issue -> paths.add(issue.path("path").asText()));
        assertTrue(paths.contains("/patient"));
        assertTrue(paths.contains("/createdAt"));
        assertFalse(saved.toString().contains("private-document-value"));
        assertFalse(run.stdout.contains("private-document-value"));
        assertFalse(run.stderr.contains("private-document-value"));
    }

    @Test
    void rejectsMalformedJsonWithoutEchoingParserDetails() throws Exception {
        Path request = directory.resolve("invalid-request.json");
        Files.write(
                request, "{\"patient\":\"private-parser-value".getBytes(StandardCharsets.UTF_8));
        Path output = directory.resolve("invalid.ofd");
        Path report = directory.resolve("invalid-report.json");
        Run run = execute(arguments(request, output, report, false));
        assertEquals(2, run.status);
        assertEquals("JSON_INVALID", lastJson(run.stderr).path("code").asText());
        assertFalse(Files.exists(output));
        assertFalse(run.stderr.contains("private-parser-value"));
        assertFalse(
                new String(Files.readAllBytes(report), StandardCharsets.UTF_8)
                        .contains("private-parser-value"));
    }

    @Test
    void doesNotOverwriteAnExistingArtifact() throws Exception {
        Path request = request("OFD", ofd());
        Path output = directory.resolve("existing.ofd");
        byte[] original = "已存在的归档文件".getBytes(StandardCharsets.UTF_8);
        Files.write(output, original);
        Path report = directory.resolve("failure.json");
        Run run = execute(arguments(request, output, report, true));
        assertEquals(2, run.status);
        assertArrayEquals(original, Files.readAllBytes(output));
        JsonNode saved = JSON.readTree(report.toFile());
        assertEquals("OUTPUT_EXISTS", saved.path("code").asText());
        assertFalse(saved.path("artifactCreated").asBoolean());
    }

    @Test
    void doesNotCreateAnArtifactWhenTheReportAlreadyExists() throws Exception {
        Path request = request("OFD", ofd());
        Path output = directory.resolve("new.ofd");
        Path report = directory.resolve("existing.json");
        byte[] original = "{\"kept\":true}".getBytes(StandardCharsets.UTF_8);
        Files.write(report, original);
        Run run = execute(arguments(request, output, report, false));
        assertEquals(2, run.status);
        assertEquals("OUTPUT_EXISTS", lastJson(run.stderr).path("code").asText());
        assertEquals("NOT_WRITTEN", lastJson(run.stderr).path("reportStatus").asText());
        assertFalse(Files.exists(output));
        assertArrayEquals(original, Files.readAllBytes(report));
    }

    @Test
    void rejectsIncompleteArguments() throws Exception {
        Run run = execute(new String[0]);
        assertEquals(64, run.status);
        assertEquals("ARGUMENT_INVALID", lastJson(run.stderr).path("code").asText());
    }

    @Test
    void printsHelpWithoutReadingOrCreatingBusinessFiles() throws Exception {
        Run run = execute(new String[] {"--help"});
        assertEquals(0, run.status, run.stderr);
        JsonNode summary = lastJson(run.stdout);
        assertEquals("HELP", summary.path("code").asText());
        assertTrue(summary.path("usage").asText().contains("java -jar"));
        assertTrue(summary.path("message").asText().contains("请求 JSON"));
        assertFalse(summary.path("artifactCreated").asBoolean());
        assertEquals("NOT_WRITTEN", summary.path("reportStatus").asText());
        assertEquals("", run.stderr);
    }

    private Path request(String format, Path source) throws Exception {
        DocumentRequest request =
                DocumentRequest.builder()
                        .documentId("consumer-verification")
                        .sourceRecord("integration", "record", "1")
                        .documentType("record", "integration")
                        .patient("subject", "integration")
                        .responsibleParty("author", "接入校验", "integration")
                        .createdAt("2026-01-01T00:00:00Z")
                        .keywords("接入校验")
                        .confidentiality("internal", "integration")
                        .source(format, source.getFileName().toString(), null)
                        .build();
        Path path = directory.resolve("request.json");
        JSON.writeValue(path.toFile(), request);
        return path;
    }

    private byte[] attachClinicalData(Path requestFile) throws Exception {
        ObjectNode request = (ObjectNode) JSON.readTree(requestFile.toFile());
        ObjectNode reference = request.putObject("clinicalData");
        reference.put("path", "clinical.json");
        reference.put("schemaId", ClinicalSchemas.ENVELOPE.getId());
        reference.put("schemaVersion", ClinicalSchemas.ENVELOPE.getVersion());
        JSON.writeValue(requestFile.toFile(), request);

        ObjectNode clinical = JSON.createObjectNode();
        ObjectNode head = clinical.putObject("head");
        head.put("profile", "urn:openofdh:clinical-envelope:0.1.0");
        head.set("schemaId", reference.get("schemaId"));
        head.set("version", reference.get("schemaVersion"));
        head.set("documentVersion", request.get("sourceVersion"));
        head.set("createdAt", request.get("createdAt"));
        head.set("modifiedAt", request.get("createdAt"));
        ObjectNode meta = clinical.putObject("meta");
        meta.set("creator", request.get("responsibleParty"));
        meta.set("keywords", request.get("keywords"));
        ObjectNode properties = meta.putObject("properties");
        properties.set("sourceSystem", request.get("sourceSystem"));
        properties.set("sourceRecordId", request.get("sourceRecordId"));
        ObjectNode identifier = clinical.putObject("identifier");
        identifier.set("documentId", request.get("documentId"));
        identifier.set("documentType", request.get("documentType"));
        identifier.set("confidentiality", request.get("confidentiality"));
        clinical.set("patient", request.get("patient"));
        clinical.putObject("content").put("text", "接入机读附件校验");
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(clinical);
        Files.write(directory.resolve("clinical.json"), bytes);
        return bytes;
    }

    private Path ofd() throws Exception {
        String namespace = "http://www.ofdspec.org/2016";
        Path source = directory.resolve("source.ofd");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            member(
                    zip,
                    "OFD.xml",
                    "<ofd:OFD xmlns:ofd='"
                            + namespace
                            + "' Version='1.0' DocType='OFD'><ofd:DocBody><ofd:DocInfo>"
                            + "<ofd:DocID>0123456789ABCDEF0123456789ABCDEF</ofd:DocID>"
                            + "</ofd:DocInfo><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot>"
                            + "</ofd:DocBody></ofd:OFD>");
            member(
                    zip,
                    "Doc_0/Document.xml",
                    "<ofd:Document xmlns:ofd='"
                            + namespace
                            + "'><ofd:CommonData><ofd:MaxUnitID>3</ofd:MaxUnitID><ofd:PageArea>"
                            + "<ofd:PhysicalBox>0 0 210 297</ofd:PhysicalBox></ofd:PageArea>"
                            + "</ofd:CommonData><ofd:Pages><ofd:Page ID='1'"
                            + " BaseLoc='Pages/Page_0/Content.xml'/></ofd:Pages></ofd:Document>");
            member(
                    zip,
                    "Doc_0/Pages/Page_0/Content.xml",
                    "<ofd:Page xmlns:ofd='"
                            + namespace
                            + "'><ofd:Content><ofd:Layer ID='2'><ofd:PathObject ID='3'"
                            + " Boundary='10 10 50 30' Stroke='true'><ofd:AbbreviatedData>"
                            + "M 0 0 L 50 0 L 50 30 L 0 30 C</ofd:AbbreviatedData>"
                            + "</ofd:PathObject></ofd:Layer></ofd:Content></ofd:Page>");
        }
        return source;
    }

    private Path pdf() throws Exception {
        Path source = directory.resolve("source.pdf");
        try (PDDocument pdf = new PDDocument();
                InputStream bytes =
                        PDDocument.class.getResourceAsStream(
                                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(bytes);
            PDType0Font font = PDType0Font.load(pdf, bytes, true);
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(72, 700);
                content.showText("SDK integration verification");
                content.endText();
            }
            pdf.save(source.toFile());
        }
        return source;
    }

    private static void member(ZipOutputStream zip, String path, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String[] arguments(Path request, Path output, Path report, boolean optimize) {
        List<String> arguments =
                new ArrayList<>(
                        Arrays.asList(
                                request.toString(),
                                directory.toString(),
                                output.toString(),
                                report.toString()));
        if (optimize) arguments.add("--optimize-page-order");
        return arguments.toArray(new String[0]);
    }

    private Run execute(String[] arguments) throws Exception {
        String java =
                Paths.get(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        List<String> command =
                new ArrayList<>(
                        Arrays.asList(
                                java,
                                "-Dfile.encoding=UTF-8",
                                "-Djava.awt.headless=true",
                                "-jar",
                                application.toString()));
        Collections.addAll(command, arguments);
        Path stdout = Files.createTempFile(directory, "stdout-", ".txt");
        Path stderr = Files.createTempFile(directory, "stderr-", ".txt");
        Process process =
                new ProcessBuilder(command)
                        .directory(directory.toFile())
                        .redirectOutput(stdout.toFile())
                        .redirectError(stderr.toFile())
                        .start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("接入进程超时");
        }
        return new Run(
                process.exitValue(),
                new String(Files.readAllBytes(stdout), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(stderr), StandardCharsets.UTF_8));
    }

    private static JsonNode lastJson(String output) throws Exception {
        String[] lines = output.split("\\r?\\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            if (lines[index].startsWith("{")) return JSON.readTree(lines[index]);
        }
        fail("进程未输出 JSON 结果：" + output);
        return null;
    }

    private static final class Run {
        final int status;
        final String stdout;
        final String stderr;

        Run(int status, String stdout, String stderr) {
            this.status = status;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
