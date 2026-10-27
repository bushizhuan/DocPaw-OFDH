package org.openofdh.core.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.internal.JsonIO;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.openofdh.core.prepare.PreparedDocument;
import org.openofdh.core.validation.ClinicalEnvelopeProfile;
import org.openofdh.core.validation.ClinicalSchemas;
import org.openofdh.core.validation.ValidationIssue;
import org.openofdh.core.validation.ValidationReport;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

class OfdhTest {
    @TempDir Path directory;

    @Test
    void preparesAndInspectsARealPackageThroughTheFacade() throws Exception {
        Path source = source();
        String originalHash = FileSafety.sha256(source);
        Ofdh sdk = Ofdh.create();
        DocumentRequest request = request().build();

        assertTrue(sdk.validate(request).isValid());
        PreparedDocument prepared = sdk.prepare(request, directory, directory.resolve("ready.ofd"));
        CheckReport report = sdk.check(prepared.getPath(), CheckProfile.PREPARED);

        assertEquals("INCOMPLETE", report.getAssessment());
        assertEquals(originalHash, FileSafety.sha256(source));
        assertEquals(originalHash, prepared.getSourceSha256());
        assertEquals(FileSafety.sha256(prepared.getPath()), prepared.getSha256());
        assertEquals(prepared.getSha256(), report.getArtifact().getSha256());
        assertEquals("ISSUES_FOUND", sdk.check(prepared.getPath()).getAssessment());
        try (SafePackage archive =
                SafePackage.open(prepared.getPath(), ResourceLimits.defaults())) {
            assertEquals(new HashSet<>(physicalEntries(source)), archive.entries());
            assertEquals(
                    "OFD-H",
                    archive.readXml("OFD.xml").getDocumentElement().getAttribute("DocType"));
        }
    }

    @Test
    void optimizesPhysicalPageOrderWithoutAddingMembers() throws Exception {
        Path source = source("Page_0", "Page_2", "Page_10");
        String originalHash = FileSafety.sha256(source);
        PreparedDocument ordinary =
                Ofdh.create()
                        .prepare(request().build(), directory, directory.resolve("ordinary.ofd"));
        List<String> defaultOrder = physicalEntries(ordinary.getPath());
        assertTrue(
                defaultOrder.indexOf("Doc_0/Pages/Page_10/Content.xml")
                        < defaultOrder.indexOf("Doc_0/Pages/Page_2/Content.xml"));

        Ofdh sdk = Ofdh.builder().optimizePageOrder(true).build();
        PreparedDocument prepared =
                sdk.prepare(request().build(), directory, directory.resolve("optimized.ofd"));
        List<String> entries = physicalEntries(prepared.getPath());

        assertEquals(
                Arrays.asList(
                        "OFD.xml",
                        "Doc_0/Document.xml",
                        "Doc_0/Pages/Page_0/Content.xml",
                        "Doc_0/Pages/Page_2/Content.xml",
                        "Doc_0/Pages/Page_10/Content.xml"),
                entries);
        assertEquals(new HashSet<>(defaultOrder), new HashSet<>(entries));
        assertEquals(new HashSet<>(physicalEntries(source)), new HashSet<>(entries));
        CheckReport report = sdk.check(prepared.getPath(), CheckProfile.PREPARED);
        assertRule(report, "OFDH-015", "NOT_CHECKED");
        assertFalse(report.getResults().stream().anyMatch(item -> "FAIL".equals(item.getResult())));
        assertEquals(originalHash, FileSafety.sha256(source));
        assertEquals(FileSafety.sha256(prepared.getPath()), prepared.getSha256());
        try (SafePackage original = SafePackage.open(source, ResourceLimits.defaults());
                SafePackage optimized =
                        SafePackage.open(prepared.getPath(), ResourceLimits.defaults())) {
            for (String entry : entries) {
                if (!"OFD.xml".equals(entry)) {
                    assertArrayEquals(original.read(entry), optimized.read(entry), entry);
                }
            }
        }
    }

    @Test
    @Timeout(60)
    void sharesCompiledSchemasAcrossConcurrentDocumentPreparations() throws Exception {
        Path source = source();
        String originalHash = FileSafety.sha256(source);
        Ofdh sdk = Ofdh.create();
        List<DocumentRequest> requests = new ArrayList<>();
        List<byte[]> clinicalInputs = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            DocumentRequest request =
                    request()
                            .documentId("format-document-" + index)
                            .sourceRecord("urn:test:source", "format-record-" + index, "1")
                            .patient("format-subject-" + index, "urn:test:identity")
                            .clinicalData("clinical-" + index + ".json", ClinicalSchemas.ENVELOPE)
                            .build();
            requests.add(request);
            clinicalInputs.add(Files.readAllBytes(writeEnvelope(request)));
        }

        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PreparedDocument>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < requests.size(); index++) {
                DocumentRequest request = requests.get(index);
                Path output = directory.resolve("parallel-" + index + ".ofd");
                futures.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    assertTrue(start.await(10, TimeUnit.SECONDS), "并发任务未及时开始");
                                    assertTrue(
                                            sdk.validateClinical(
                                                            request,
                                                            directory.resolve(
                                                                    request.getClinicalData()
                                                                            .getPath()))
                                                    .isValid());
                                    PreparedDocument prepared =
                                            sdk.prepare(request, directory, output);
                                    CheckReport report =
                                            sdk.check(prepared.getPath(), CheckProfile.PREPARED);
                                    assertFalse(
                                            report.getResults().stream()
                                                    .anyMatch(
                                                            item ->
                                                                    "FAIL"
                                                                            .equals(
                                                                                    item
                                                                                            .getResult())));
                                    assertEquals(
                                            prepared.getSha256(), report.getArtifact().getSha256());
                                    return prepared;
                                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发任务未全部就绪");
            start.countDown();

            Set<String> fileIds = new HashSet<>();
            Set<String> hashes = new HashSet<>();
            for (int index = 0; index < futures.size(); index++) {
                PreparedDocument prepared = futures.get(index).get(30, TimeUnit.SECONDS);
                DocumentRequest request = requests.get(index);
                assertTrue(
                        Files.isSameFile(
                                directory.resolve("parallel-" + index + ".ofd"),
                                prepared.getPath()));
                assertEquals(originalHash, prepared.getSourceSha256());
                assertEquals(FileSafety.sha256(prepared.getPath()), prepared.getSha256());
                assertTrue(fileIds.add(prepared.getFileId()), "并发产物文件标识重复");
                assertTrue(hashes.add(prepared.getSha256()), "不同业务请求的产物摘要重复");
                assertArrayEquals(
                        clinicalInputs.get(index),
                        Files.readAllBytes(directory.resolve(request.getClinicalData().getPath())));
                try (SafePackage archive =
                        SafePackage.open(prepared.getPath(), ResourceLimits.defaults())) {
                    Element info =
                            Xml.child(
                                    Xml.child(
                                            archive.readXml("OFD.xml").getDocumentElement(),
                                            "DocBody"),
                                    "DocInfo");
                    assertEquals(prepared.getFileId(), Xml.text(info, "DocID"));
                    Map<String, String> metadata = new LinkedHashMap<>();
                    for (Element item :
                            Xml.children(Xml.child(info, "CustomDatas"), "CustomData")) {
                        metadata.put(item.getAttribute("Name"), item.getTextContent());
                    }
                    assertEquals(request.getDocumentId(), metadata.get("openofdh.documentId"));
                    assertEquals(
                            request.getSourceRecordId(), metadata.get("openofdh.sourceRecordId"));
                    assertEquals(request.getPatient().getId(), metadata.get("openofdh.patient.id"));
                    assertArrayEquals(
                            clinicalInputs.get(index), archive.read("Doc_0/Attachs/Attach_0.json"));
                }
            }
            assertEquals(originalHash, FileSafety.sha256(source));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "并发任务未及时结束");
        }
    }

    @Test
    void reportsAllRequestProblemsBeforeReadingTheSource() throws Exception {
        Ofdh sdk = Ofdh.create();
        DocumentRequest request =
                request()
                        .patient(null, "urn:test:identity")
                        .createdAt("2026/10/26 09:30:00")
                        .keywords(" ")
                        .build();
        ValidationReport report = sdk.validate(request);

        assertFalse(report.isValid());
        List<String> paths =
                report.getIssues().stream()
                        .map(ValidationIssue::getPath)
                        .collect(Collectors.toList());
        assertTrue(paths.containsAll(Arrays.asList("/patient/id", "/createdAt", "/keywords/0")));
        Path output = directory.resolve("rejected.ofd");
        OfdhException failure =
                assertThrows(OfdhException.class, () -> sdk.prepare(request, directory, output));
        assertEquals("REQUEST_INVALID", failure.getCode());
        assertEquals(
                paths,
                failure.getIssues().stream()
                        .map(ValidationIssue::getPath)
                        .collect(Collectors.toList()));
        assertFalse(Files.exists(output));
    }

    @Test
    void registersACustomSchemaAndPreservesClinicalBytes() throws Exception {
        source();
        Path schema =
                write(
                        "measurement.schema.json",
                        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                                + "\"type\":\"object\",\"required\":[\"measurement\"],"
                                + "\"additionalProperties\":false,\"properties\":{"
                                + "\"measurement\":{\"type\":\"number\"}}}");
        Path clinical = write("clinical.json", "{\n  \"measurement\": 7.25\n}\n");
        DocumentRequest request =
                request().clinicalData("clinical.json", "urn:test:measurement", "1").build();
        Ofdh sdk = Ofdh.builder().clinicalSchema("urn:test:measurement", "1", schema).build();

        assertTrue(sdk.validateClinical(request, clinical).isValid());
        PreparedDocument prepared =
                sdk.prepare(request, directory, directory.resolve("clinical.ofd"));
        try (SafePackage archive =
                SafePackage.open(prepared.getPath(), ResourceLimits.defaults())) {
            assertArrayEquals(
                    Files.readAllBytes(clinical), archive.read("Doc_0/Attachs/Attach_0.json"));
        }
    }

    @Test
    void makesBuiltInSchemasAvailableWithoutRegistration() throws Exception {
        Ofdh sdk = Ofdh.create();
        Path empty = write("clinical.json", "{}");
        for (ClinicalSchemas schema : ClinicalSchemas.values()) {
            DocumentRequest request = request().clinicalData("clinical.json", schema).build();
            ValidationReport report = sdk.validateClinical(request, empty);
            assertFalse(report.isValid());
            assertFalse(report.getIssues().isEmpty());
            assertEquals(schema.getId(), request.getClinicalData().getSchemaId());
            assertEquals(schema.getVersion(), request.getClinicalData().getSchemaVersion());
        }
    }

    @Test
    void keepsBuilderInputsIndependentAndFormatsOffsetTimestamps() throws Exception {
        String[] keywords = {"格式验证"};
        DocumentRequest.Builder builder =
                request()
                        .keywords(keywords)
                        .createdAt(OffsetDateTime.parse("2026-10-26T09:30:00+08:00"));
        keywords[0] = "外部修改";
        DocumentRequest first = builder.build();
        builder.keywords("另一个请求");

        assertEquals("2026-10-26T09:30:00+08:00", first.getCreatedAt());
        assertEquals(Arrays.asList("格式验证"), first.getKeywords());
        assertThrows(UnsupportedOperationException.class, () -> first.getKeywords().add("修改"));
        assertTrue(Ofdh.create().validate(first).isValid());

        DocumentRequest empty = DocumentRequest.builder().build();
        assertEquals("0.1.0", empty.getContractVersion());
        assertNull(empty.getCreatedAt());
        assertNull(empty.getPatient());
        assertNull(empty.getResponsibleParty());
    }

    private DocumentRequest.Builder request() {
        return DocumentRequest.builder()
                .documentId("format-document")
                .sourceRecord("urn:test:source", "format-record", "1")
                .documentType("record", "urn:test:document-type")
                .patient("format-subject", "urn:test:identity")
                .responsibleParty("format-author", "格式测试作者", "urn:test:identity")
                .createdAt("2026-10-26T09:30:00+08:00")
                .keywords("格式验证")
                .confidentiality("test", "urn:test:confidentiality")
                .ofd("source.ofd");
    }

    private Path writeEnvelope(DocumentRequest request) throws Exception {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        ObjectNode head = data.putObject("head");
        head.put("profile", ClinicalEnvelopeProfile.ID);
        head.put("schemaId", request.getClinicalData().getSchemaId());
        head.put("version", request.getClinicalData().getSchemaVersion());
        head.put("documentVersion", request.getSourceVersion());
        head.put("createdAt", request.getCreatedAt());
        head.put("modifiedAt", request.getCreatedAt());
        ObjectNode meta = data.putObject("meta");
        meta.set("creator", JsonIO.tree(request.getResponsibleParty()));
        meta.set("keywords", JsonIO.tree(request.getKeywords()));
        ObjectNode properties = meta.putObject("properties");
        properties.put("sourceSystem", request.getSourceSystem());
        properties.put("sourceRecordId", request.getSourceRecordId());
        ObjectNode identifier = data.putObject("identifier");
        identifier.put("documentId", request.getDocumentId());
        identifier.set("documentType", JsonIO.tree(request.getDocumentType()));
        identifier.set("confidentiality", JsonIO.tree(request.getConfidentiality()));
        data.set("patient", JsonIO.tree(request.getPatient()));
        data.putObject("content").put("recordId", request.getSourceRecordId());
        Path file = directory.resolve(request.getClinicalData().getPath());
        Files.write(file, JsonIO.bytes(data));
        return file;
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

    private static void assertRule(CheckReport report, String ruleId, String expected) {
        CheckReport.Result result =
                report.getResults().stream()
                        .filter(item -> ruleId.equals(item.getRuleId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("缺少检查规则：" + ruleId));
        assertEquals(expected, result.getResult());
    }

    private Path write(String filename, String content) throws Exception {
        Path path = directory.resolve(filename);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private Path source(String... pageNames) throws Exception {
        if (pageNames.length == 0) pageNames = new String[] {"Page_0"};
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(
                "OFD.xml",
                "<ofd:OFD xmlns:ofd='"
                        + Xml.NS
                        + "' Version='1.0' DocType='OFD'><ofd:DocBody><ofd:DocInfo>"
                        + "<ofd:DocID>0123456789ABCDEF0123456789ABCDEF</ofd:DocID></ofd:DocInfo>"
                        + "<ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>");
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
                        + "</ofd:Pages>"
                        + "</ofd:Document>");
        for (int index = 0; index < pageNames.length; index++) {
            entries.put(
                    "Doc_0/Pages/" + pageNames[index] + "/Content.xml",
                    "<ofd:Page xmlns:ofd='"
                            + Xml.NS
                            + "'><ofd:Area><ofd:PhysicalBox>0 0 210"
                            + " 297</ofd:PhysicalBox></ofd:Area><ofd:Content><ofd:Layer ID='"
                            + (index * 2 + 2)
                            + "'/></ofd:Content></ofd:Page>");
        }
        Path path = directory.resolve("source.ofd");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }
}
