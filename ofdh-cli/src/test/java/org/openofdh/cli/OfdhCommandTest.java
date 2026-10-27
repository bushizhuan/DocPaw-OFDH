package org.openofdh.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.Ofdh;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.internal.JsonIO;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.Xml;
import org.openofdh.core.prepare.PreparedDocument;
import org.openofdh.core.validation.ClinicalSchemas;

import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

class OfdhCommandTest {
    @TempDir Path directory;

    @Test
    void rejectsAnOutputRootAsAnArgumentError() throws Exception {
        Invocation result =
                invoke(
                        "schema",
                        "export",
                        "--id",
                        ClinicalSchemas.ENVELOPE.getId(),
                        "--schema-version",
                        ClinicalSchemas.ENVELOPE.getVersion(),
                        "--out",
                        directory.toAbsolutePath().getRoot().toString());
        assertEquals(64, result.status);
        assertEquals(
                "OUTPUT_INVALID",
                JsonIO.parse(result.error.getBytes(StandardCharsets.UTF_8)).path("code").asText());
    }

    @Test
    void failsWhenTheResultStreamCannotBeWritten() throws Exception {
        StringWriter error = new StringWriter();
        Writer unavailable =
                new Writer() {
                    @Override
                    public void write(char[] characters, int offset, int length)
                            throws IOException {
                        throw new IOException("测试输出端已经关闭");
                    }

                    @Override
                    public void flush() throws IOException {
                        throw new IOException("测试输出端已经关闭");
                    }

                    @Override
                    public void close() {}
                };
        int status =
                OfdhCommand.commandLine()
                        .setOut(new PrintWriter(unavailable))
                        .setErr(new PrintWriter(error))
                        .execute("schema", "list");
        assertEquals(4, status);
        assertEquals(
                "IO_FAILURE",
                JsonIO.parse(error.toString().getBytes(StandardCharsets.UTF_8))
                        .path("code")
                        .asText());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void preservesPublishedStageWhenFinalInspectionLosesTheOriginalFile(boolean replaced)
            throws Exception {
        source();
        Path request = request();
        Ofdh sdk = Ofdh.create();
        PreparedDocument prepared =
                sdk.prepare(request, directory, directory.resolve("published.ofd"));
        byte[] current = null;
        if (replaced) {
            PreparedDocument replacement =
                    sdk.prepare(request, directory, directory.resolve("replacement.ofd"));
            current = Files.readAllBytes(replacement.getPath());
            Files.write(prepared.getPath(), current);
            assertNotEquals(prepared.getSha256(), FileSafety.sha256(prepared.getPath()));
        } else {
            Files.delete(prepared.getPath());
        }
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine command =
                OfdhCommand.commandLine()
                        .setOut(new PrintWriter(output))
                        .setErr(new PrintWriter(error));
        OfdhCommand.Prepare operation = command.getSubcommands().get("prepare").getCommand();
        operation.report = directory.resolve("inspection.json");
        assertEquals(4, operation.complete(prepared, ResourceLimits.defaults()));
        JsonNode summary = JsonIO.parse(output.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals("PREPARED", summary.path("stage").asText());
        assertEquals(prepared.getSha256(), summary.path("sha256").asText());
        assertEquals("NOT_WRITTEN", summary.path("reportStatus").asText());
        assertTrue(summary.path("assessment").isNull());
        assertEquals(
                "POST_PREPARE_CHECK_FAILED",
                JsonIO.parse(error.toString().getBytes(StandardCharsets.UTF_8))
                        .path("code")
                        .asText());
        assertFalse(Files.exists(operation.report));
        if (replaced) assertArrayEquals(current, Files.readAllBytes(prepared.getPath()));
        else assertFalse(Files.exists(prepared.getPath()));
    }

    @Test
    void preparesAnActualFileAndChecksBothLifecycleStages() throws Exception {
        Path request = request();
        Path source = source();
        String sourceHash = FileSafety.sha256(source);
        Path output = directory.resolve("prepared.ofd");
        Path report = directory.resolve("prepare-report.json");
        Invocation prepared =
                invoke(
                        "prepare",
                        "--request",
                        request.toString(),
                        "--input-root",
                        directory.toString(),
                        "--out",
                        output.toString(),
                        "--report",
                        report.toString());
        assertEquals(0, prepared.status, prepared.error);
        JsonNode result = JsonIO.parse(prepared.output.getBytes(StandardCharsets.UTF_8));
        assertEquals("PREPARED", result.path("stage").asText());
        assertEquals("WRITTEN", result.path("reportStatus").asText());
        assertEquals(FileSafety.sha256(output), result.path("sha256").asText());
        assertEquals(sourceHash, FileSafety.sha256(source));
        assertEquals(
                0,
                JsonIO.read(report, 1 << 20)
                        .path("coverage")
                        .path("resultCounts")
                        .path("FAIL")
                        .asInt());
        assertEquals(
                3,
                invoke(
                                "check",
                                "--input",
                                output.toString(),
                                "--profile",
                                "prepared",
                                "--report",
                                directory.resolve("prepared-check.json").toString())
                        .status);
        assertEquals(
                2,
                invoke(
                                "check",
                                "--input",
                                output.toString(),
                                "--report",
                                directory.resolve("final-check.json").toString())
                        .status);
        assertEquals(result.path("sha256").asText(), FileSafety.sha256(output));
        assertEquals(
                new HashSet<>(physicalEntries(source)), new HashSet<>(physicalEntries(output)));
    }

    @Test
    void optimizesDocumentPageOrderBeforeLargeClinicalAttachments() throws Exception {
        Path request = clinicalRequest();
        Path clinicalFile = directory.resolve("clinical.json");
        com.fasterxml.jackson.databind.node.ObjectNode data =
                (com.fasterxml.jackson.databind.node.ObjectNode) JsonIO.read(clinicalFile, 1 << 20);
        char[] narrative = new char[256 * 1024];
        java.util.Arrays.fill(narrative, '文');
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("content"))
                .put("text", new String(narrative));
        Files.write(clinicalFile, JsonIO.bytes(data));
        Path source = source("Page_0", "Page_2", "Page_10");
        String sourceHash = FileSafety.sha256(source);
        byte[] clinical = Files.readAllBytes(clinicalFile);
        Path output = directory.resolve("optimized.ofd");
        Path report = directory.resolve("optimized-report.json");
        Invocation result =
                invoke(
                        "prepare",
                        "--request",
                        request.toString(),
                        "--input-root",
                        directory.toString(),
                        "--out",
                        output.toString(),
                        "--report",
                        report.toString(),
                        "--optimize-page-order");

        assertEquals(0, result.status, result.error);
        List<String> entries = physicalEntries(output);
        assertEquals("OFD.xml", entries.get(0));
        assertTrue(
                entries.indexOf("Doc_0/Pages/Page_0/Content.xml")
                        < entries.indexOf("Doc_0/Pages/Page_2/Content.xml"));
        assertTrue(
                entries.indexOf("Doc_0/Pages/Page_2/Content.xml")
                        < entries.indexOf("Doc_0/Pages/Page_10/Content.xml"));
        assertTrue(
                entries.indexOf("Doc_0/Pages/Page_10/Content.xml")
                        < entries.indexOf("Doc_0/Attachs/Attach_0.json"));
        java.util.Set<String> expected = new HashSet<>(physicalEntries(source));
        expected.add("Doc_0/Attachs/Attachments.xml");
        expected.add("Doc_0/Attachs/Attach_0.json");
        assertEquals(expected, new HashSet<>(entries));
        JsonNode inspection = JsonIO.read(report, 1 << 20);
        Map<String, String> checks = new LinkedHashMap<>();
        inspection
                .path("results")
                .forEach(
                        item ->
                                checks.put(
                                        item.path("ruleId").asText(),
                                        item.path("result").asText()));
        assertEquals("NOT_CHECKED", checks.get("OFDH-015"));
        assertFalse(checks.containsValue("FAIL"));
        assertEquals(sourceHash, FileSafety.sha256(source));
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(output.toFile());
                java.io.InputStream input =
                        zip.getInputStream(zip.getEntry("Doc_0/Attachs/Attach_0.json"))) {
            assertArrayEquals(clinical, readBytes(input));
        }

        Path ordinary = directory.resolve("ordinary.ofd");
        Invocation defaultResult =
                invoke(
                        "prepare",
                        "--request",
                        request.toString(),
                        "--input-root",
                        directory.toString(),
                        "--out",
                        ordinary.toString(),
                        "--report",
                        directory.resolve("ordinary-report.json").toString());
        assertEquals(0, defaultResult.status, defaultResult.error);
        List<String> defaultOrder = physicalEntries(ordinary);
        assertEquals(expected, new HashSet<>(defaultOrder));
        assertTrue(
                defaultOrder.indexOf("Doc_0/Attachs/Attach_0.json")
                        < defaultOrder.indexOf("Doc_0/Pages/Page_0/Content.xml"));
        assertTrue(
                defaultOrder.indexOf("Doc_0/Pages/Page_10/Content.xml")
                        < defaultOrder.indexOf("Doc_0/Pages/Page_2/Content.xml"));
        assertTrue(invoke("prepare", "--help").output.contains("--optimize-page-order"));
        assertTrue(invoke("sign", "--help").output.contains("--optimize-page-order"));
    }

    @Test
    void validatesMetadataWithoutOpeningTheBusinessDocument() throws Exception {
        Invocation value = invoke("validate-request", "--request", request().toString());
        assertEquals(0, value.status, value.error);
        assertTrue(
                JsonIO.parse(value.output.getBytes(StandardCharsets.UTF_8))
                        .path("valid")
                        .asBoolean());
        assertFalse(Files.exists(directory.resolve("source.ofd")));
    }

    @Test
    void reportsEveryInvalidFieldWithoutEchoingInputValues() throws Exception {
        Path request = request();
        com.fasterxml.jackson.databind.node.ObjectNode value =
                (com.fasterxml.jackson.databind.node.ObjectNode) JsonIO.read(request, 1 << 20);
        value.put("createdAt", "不要泄露此内容");
        ((com.fasterxml.jackson.databind.node.ObjectNode) value.get("patient")).remove("id");
        value.put("extraField", "也不要泄露此内容");
        Files.write(request, JsonIO.bytes(value));
        Invocation result = invoke("validate-request", "--request", request.toString());
        assertEquals(64, result.status);
        assertEquals("", result.output);
        JsonNode error = JsonIO.parse(result.error.getBytes(StandardCharsets.UTF_8));
        assertEquals("REQUEST_INVALID", error.path("code").asText());
        assertFalse(error.path("truncated").asBoolean());
        java.util.Set<String> paths = new java.util.HashSet<>();
        error.path("issues")
                .forEach(
                        issue -> {
                            paths.add(issue.path("path").asText());
                            assertFalse(issue.path("message").asText().isEmpty());
                            assertFalse(issue.path("expected").asText().isEmpty());
                            assertFalse(issue.has("actual"));
                        });
        assertTrue(paths.contains("/createdAt"));
        assertTrue(paths.contains("/patient/id"));
        assertTrue(paths.contains("/extraField"));
        assertFalse(result.error.contains("不要泄露此内容"));
        assertFalse(result.error.contains("也不要泄露此内容"));
    }

    @Test
    void listsAndExportsBundledSchemasWithoutOverwriting() throws Exception {
        Invocation listed = invoke("schema", "list");
        assertEquals(0, listed.status, listed.error);
        JsonNode catalog = JsonIO.parse(listed.output.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, catalog.path("schemas").size());
        Path output = directory.resolve("admission.schema.json");
        Invocation exported =
                invoke(
                        "schema",
                        "export",
                        "--id",
                        ClinicalSchemas.ADMISSION_RECORD.getId(),
                        "--schema-version",
                        ClinicalSchemas.ADMISSION_RECORD.getVersion(),
                        "--out",
                        output.toString());
        assertEquals(0, exported.status, exported.error);
        JsonNode schema = JsonIO.read(output, 1 << 20);
        assertEquals(
                "https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText());
        byte[] bytes = Files.readAllBytes(output);
        assertEquals(
                64,
                invoke(
                                "schema",
                                "export",
                                "--id",
                                ClinicalSchemas.ADMISSION_RECORD.getId(),
                                "--schema-version",
                                ClinicalSchemas.ADMISSION_RECORD.getVersion(),
                                "--out",
                                output.toString())
                        .status);
        assertArrayEquals(bytes, Files.readAllBytes(output));
    }

    @Test
    void providesChineseHelpAndUnknownSchemaDiagnostic() throws Exception {
        Invocation help = invoke("--help");
        assertEquals(0, help.status);
        assertTrue(help.output.contains("用法："));
        assertTrue(help.output.contains("选项："));
        assertTrue(help.output.contains("显示帮助信息"));
        Invocation result =
                invoke(
                        "schema",
                        "export",
                        "--id",
                        "urn:unknown",
                        "--schema-version",
                        "1",
                        "--out",
                        directory.resolve("unknown.json").toString());
        assertEquals(64, result.status);
        JsonNode error = JsonIO.parse(result.error.getBytes(StandardCharsets.UTF_8));
        assertEquals("SCHEMA_UNKNOWN", error.path("code").asText());
        assertTrue(error.path("message").asText().contains("未找到"));
        assertTrue(error.path("issues").isArray());
    }

    @Test
    void validatesAndPreparesClinicalDataWithTheBundledSchema() throws Exception {
        Path request = clinicalRequest();
        Invocation validated =
                invoke(
                        "validate-clinical",
                        "--request",
                        request.toString(),
                        "--input-root",
                        directory.toString());
        assertEquals(0, validated.status, validated.error);
        assertFalse(Files.exists(directory.resolve("source.ofd")));
        byte[] clinical = Files.readAllBytes(directory.resolve("clinical.json"));
        source();
        Path output = directory.resolve("clinical.ofd");
        Invocation prepared =
                invoke(
                        "prepare",
                        "--request",
                        request.toString(),
                        "--input-root",
                        directory.toString(),
                        "--out",
                        output.toString(),
                        "--report",
                        directory.resolve("clinical-report.json").toString());
        assertEquals(0, prepared.status, prepared.error);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(output.toFile())) {
            java.util.zip.ZipEntry attachment =
                    zip.stream()
                            .filter(
                                    entry ->
                                            entry.getName().startsWith("Doc_0/Attachs/")
                                                    && entry.getName().endsWith(".json"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("缺少临床附件"));
            try (java.io.InputStream input = zip.getInputStream(attachment)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
                assertArrayEquals(clinical, bytes.toByteArray());
            }
        }
    }

    @Test
    void reportsClinicalFieldErrorsWithoutConvertingTheDocument() throws Exception {
        Path request = clinicalRequest();
        Path clinical = directory.resolve("clinical.json");
        com.fasterxml.jackson.databind.node.ObjectNode data =
                (com.fasterxml.jackson.databind.node.ObjectNode) JsonIO.read(clinical, 1 << 20);
        data.putArray("content");
        ((com.fasterxml.jackson.databind.node.ObjectNode) data.path("head"))
                .put("modifiedAt", "2026-02-30T09:30:00+08:00");
        Files.write(clinical, JsonIO.bytes(data));
        Invocation result =
                invoke(
                        "validate-clinical",
                        "--request",
                        request.toString(),
                        "--input-root",
                        directory.toString());
        assertEquals(2, result.status, result.error);
        JsonNode error = JsonIO.parse(result.error.getBytes(StandardCharsets.UTF_8));
        java.util.Set<String> paths = new java.util.HashSet<>();
        error.path("issues").forEach(issue -> paths.add(issue.path("path").asText()));
        assertTrue(paths.contains("/content"));
        assertTrue(paths.contains("/head/modifiedAt"));
        assertFalse(Files.exists(directory.resolve("source.ofd")));
    }

    @Test
    void rejectsMissingArgumentsWithMachineReadableDiagnostics() throws Exception {
        Invocation value = invoke("prepare");
        assertEquals(64, value.status);
        assertEquals(
                "ARGUMENT_INVALID",
                JsonIO.parse(value.error.getBytes(StandardCharsets.UTF_8)).path("code").asText());
        assertEquals("", value.output);
    }

    @Test
    void refusesToReplaceAnExistingReportBeforeCreatingAnArtifact() throws Exception {
        Path report = directory.resolve("report.json");
        Files.write(report, new byte[] {7});
        Path output = directory.resolve("out.ofd");
        Invocation value =
                invoke(
                        "prepare",
                        "--request",
                        request().toString(),
                        "--input-root",
                        directory.toString(),
                        "--out",
                        output.toString(),
                        "--report",
                        report.toString());
        assertEquals(64, value.status);
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(report));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsAliasedDocumentAndReportPaths() throws Exception {
        Path output = directory.resolve("out.ofd");
        Invocation value =
                invoke(
                        "prepare",
                        "--request",
                        request().toString(),
                        "--input-root",
                        directory.toString(),
                        "--out",
                        output.toString(),
                        "--report",
                        directory.resolve("unused/../out.ofd").toString());
        assertEquals(64, value.status);
        assertEquals(
                "OUTPUT_INVALID",
                JsonIO.parse(value.error.getBytes(StandardCharsets.UTF_8)).path("code").asText());
        assertFalse(Files.exists(output));
    }

    @Test
    void reportsMalformedSourceWithoutPublishingOutput() throws Exception {
        Files.write(directory.resolve("source.ofd"), new byte[] {1, 2, 3});
        Path output = directory.resolve("out.ofd");
        Invocation value =
                invoke(
                        "prepare",
                        "--request",
                        request().toString(),
                        "--input-root",
                        directory.toString(),
                        "--out",
                        output.toString(),
                        "--report",
                        directory.resolve("report.json").toString());
        assertEquals(2, value.status, value.error);
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(directory.resolve("report.json")));
    }

    private Path clinicalRequest() throws Exception {
        Path request = request();
        com.fasterxml.jackson.databind.node.ObjectNode document =
                (com.fasterxml.jackson.databind.node.ObjectNode) JsonIO.read(request, 1 << 20);
        com.fasterxml.jackson.databind.node.ObjectNode reference =
                document.putObject("clinicalData");
        reference.put("path", "clinical.json");
        reference.put("schemaId", ClinicalSchemas.ENVELOPE.getId());
        reference.put("schemaVersion", ClinicalSchemas.ENVELOPE.getVersion());
        Files.write(request, JsonIO.bytes(document));
        com.fasterxml.jackson.databind.node.ObjectNode clinical =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        com.fasterxml.jackson.databind.node.ObjectNode head = clinical.putObject("head");
        head.put("profile", "urn:openofdh:clinical-envelope:0.1.0");
        head.put("schemaId", ClinicalSchemas.ENVELOPE.getId());
        head.put("version", ClinicalSchemas.ENVELOPE.getVersion());
        head.set("documentVersion", document.get("sourceVersion"));
        head.set("createdAt", document.get("createdAt"));
        head.set("modifiedAt", document.get("createdAt"));
        com.fasterxml.jackson.databind.node.ObjectNode meta = clinical.putObject("meta");
        meta.set("creator", document.get("responsibleParty"));
        meta.set("keywords", document.get("keywords"));
        com.fasterxml.jackson.databind.node.ObjectNode properties = meta.putObject("properties");
        properties.set("sourceSystem", document.get("sourceSystem"));
        properties.set("sourceRecordId", document.get("sourceRecordId"));
        com.fasterxml.jackson.databind.node.ObjectNode identifier =
                clinical.putObject("identifier");
        identifier.set("documentId", document.get("documentId"));
        identifier.set("documentType", document.get("documentType"));
        identifier.set("confidentiality", document.get("confidentiality"));
        clinical.set("patient", document.get("patient"));
        clinical.putObject("content").put("text", "格式校验内容");
        Files.write(directory.resolve("clinical.json"), JsonIO.bytes(clinical));
        return request;
    }

    private Path request() throws Exception {
        DocumentRequest request =
                new DocumentRequest(
                        "0.1.0",
                        "format-test",
                        "urn:test:source",
                        "record",
                        "1",
                        new DocumentRequest.Code("test", "urn:test:type"),
                        new DocumentRequest.Identity("subject", "urn:test:identity"),
                        null,
                        new DocumentRequest.ResponsibleParty(
                                "author", "Format test", "urn:test:identity"),
                        "2026-10-23T10:00:00+08:00",
                        Collections.singletonList("format test"),
                        new DocumentRequest.Code("test", "urn:test:classification"),
                        new DocumentRequest.Source("OFD", "source.ofd", null),
                        null);
        Path path = directory.resolve("request.json");
        Files.write(path, JsonIO.bytes(JsonIO.tree(request)));
        return path;
    }

    private Path source(String... pageNames) throws Exception {
        if (pageNames.length == 0) pageNames = new String[] {"Page_0"};
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(
                "OFD.xml",
                "<ofd:OFD xmlns:ofd='"
                        + Xml.NS
                        + "' Version='1.0'"
                        + " DocType='OFD'><ofd:DocBody><ofd:DocInfo><ofd:DocID>0123456789ABCDEF0123456789ABCDEF</ofd:DocID></ofd:DocInfo><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
        StringBuilder pages = new StringBuilder();
        for (int index = 0; index < pageNames.length; index++) {
            pages.append("<ofd:Page ID='")
                    .append(index * 2 + 1)
                    .append("' BaseLoc='Pages/")
                    .append(pageNames[index])
                    .append("/Content.xml'/>");
        }
        entries.put(
                "Doc_0/Document.xml",
                "<ofd:Document xmlns:ofd='"
                        + Xml.NS
                        + "'><ofd:CommonData><ofd:MaxUnitID>"
                        + pageNames.length * 2
                        + "</ofd:MaxUnitID><ofd:PageArea><ofd:PhysicalBox>0"
                        + " 0 210"
                        + " 297</ofd:PhysicalBox></ofd:PageArea></ofd:CommonData><ofd:Pages>"
                        + pages
                        + "</ofd:Pages></ofd:Document>");
        for (int index = 0; index < pageNames.length; index++) {
            entries.put(
                    "Doc_0/Pages/" + pageNames[index] + "/Content.xml",
                    "<ofd:Page xmlns:ofd='"
                            + Xml.NS
                            + "'><ofd:Content><ofd:Layer ID='"
                            + (index * 2 + 2)
                            + "'/></ofd:Content></ofd:Page>");
        }
        Path source = directory.resolve("source.ofd");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(source))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return source;
    }

    private Invocation invoke(String... arguments) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        CommandLine command =
                OfdhCommand.commandLine()
                        .setOut(new PrintWriter(output))
                        .setErr(new PrintWriter(error));
        int status = command.execute(arguments);
        return new Invocation(status, output.toString(), error.toString());
    }

    private static List<String> physicalEntries(Path path) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(path))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static byte[] readBytes(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }

    private static final class Invocation {
        final int status;
        final String output;
        final String error;

        Invocation(int status, String output, String error) {
            this.status = status;
            this.output = output;
            this.error = error;
        }
    }
}
