package org.openofdh.core.prepare;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.ofdrw.reader.OFDReader;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.PageSourceAdapter;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.openofdh.core.validation.ClinicalSchemaRegistry;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class PrepareServiceTest {
    @TempDir Path temporary;

    @Test
    void preparesARealOfdAndKeepsSourceBytesIntact() throws Exception {
        Path source = source(Collections.emptyMap());
        String sourceDigest = FileSafety.sha256(source);
        PreparedDocument result =
                service(new ClinicalSchemaRegistry())
                        .prepare(
                                request(null, sourceDigest),
                                temporary,
                                temporary.resolve("result.ofd"));
        assertEquals(sourceDigest, FileSafety.sha256(source));
        assertEquals(sourceDigest, result.getSourceSha256());
        assertEquals(FileSafety.sha256(result.getPath()), result.getSha256());
        assertTrue(result.getFileId().matches("[0-9A-F]{32}"));
        try (SafePackage archive = SafePackage.open(result.getPath(), ResourceLimits.defaults())) {
            Element entry = archive.readXml("OFD.xml").getDocumentElement();
            assertEquals("OFD-H", entry.getAttribute("DocType"));
            Element info = Xml.child(Xml.child(entry, "DocBody"), "DocInfo");
            assertEquals(result.getFileId(), Xml.text(info, "DocID"));
            assertEquals("Fixture author", Xml.text(info, "Author"));
            assertEquals("Fixture title", Xml.text(info, "Title"));
            assertEquals("0.1.0", metadata(info, "openofdh.metadataProfile"));
        }
        try (OFDReader reader = new OFDReader(result.getPath())) {
            assertEquals(1, reader.getNumberOfPages());
        }
    }

    @Test
    void registersClinicalBytesAndPreservesTheirDigest() throws Exception {
        source(Collections.emptyMap());
        Path clinical = temporary.resolve("clinical.json");
        Files.write(clinical, "{\"result\":7}".getBytes(StandardCharsets.UTF_8));
        Path definition = temporary.resolve("clinical.schema.json");
        Files.write(
                definition,
                ("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\","
                     + "\"required\":[\"result\"],\"additionalProperties\":false,\"properties\":{\"result\":{\"type\":\"integer\"}}}")
                        .getBytes(StandardCharsets.UTF_8));
        ClinicalSchemaRegistry registry =
                new ClinicalSchemaRegistry().register("urn:test:result", "1", definition);
        PreparedDocument result =
                service(registry)
                        .prepare(
                                request(
                                        new DocumentRequest.ClinicalData(
                                                "clinical.json", "urn:test:result", "1"),
                                        null),
                                temporary,
                                temporary.resolve("clinical.ofd"));
        try (SafePackage archive = SafePackage.open(result.getPath(), ResourceLimits.defaults())) {
            assertArrayEquals(
                    Files.readAllBytes(clinical), archive.read("Doc_0/Attachs/Attach_0.json"));
            Element attachments =
                    archive.readXml("Doc_0/Attachs/Attachments.xml").getDocumentElement();
            Element attachment = Xml.child(attachments, "Attachment");
            assertEquals("clinical.json", attachment.getAttribute("Name"));
            assertEquals("Attach_0.json", Xml.text(attachment, "FileLoc"));
            Element document = archive.readXml("Doc_0/Document.xml").getDocumentElement();
            assertEquals("Attachs/Attachments.xml", Xml.text(document, "Attachments"));
            Element info =
                    Xml.child(
                            Xml.child(archive.readXml("OFD.xml").getDocumentElement(), "DocBody"),
                            "DocInfo");
            assertEquals(FileSafety.sha256(clinical), metadata(info, "openofdh.clinical.sha256"));
        }
    }

    @Test
    void rejectsSignedSourcesWithoutPublishingOrChangingThem() throws Exception {
        Path input =
                source(
                        Collections.singletonMap(
                                "Doc_0/Signs/Signatures.xml", xml("Signatures", "")));
        String hash = FileSafety.sha256(input);
        Path output = temporary.resolve("result.ofd");
        OfdhException error =
                assertThrows(
                        OfdhException.class,
                        () ->
                                service(new ClinicalSchemaRegistry())
                                        .prepare(request(null, null), temporary, output));
        assertEquals("SIGNED_INPUT_REQUIRES_DERIVATION", error.getCode());
        assertFalse(Files.exists(output));
        assertEquals(hash, FileSafety.sha256(input));
    }

    @Test
    void rejectsAMissingPageBeforePublishing() throws Exception {
        source(Collections.singletonMap("Doc_0/Document.xml", document("Pages/missing.xml")));
        Path output = temporary.resolve("result.ofd");
        assertEquals(
                "RESOURCE_MISSING",
                assertThrows(
                                OfdhException.class,
                                () ->
                                        service(new ClinicalSchemaRegistry())
                                                .prepare(request(null, null), temporary, output))
                        .getCode());
        assertFalse(Files.exists(output));
    }

    @Test
    void failsAnExpectedDigestMismatchBeforeConversion() throws Exception {
        source(Collections.emptyMap());
        Path output = temporary.resolve("result.ofd");
        assertEquals(
                "SOURCE_HASH_MISMATCH",
                assertThrows(
                                OfdhException.class,
                                () ->
                                        service(new ClinicalSchemaRegistry())
                                                .prepare(
                                                        request(
                                                                null,
                                                                String.join(
                                                                        "",
                                                                        Collections.nCopies(
                                                                                64, "0"))),
                                                        temporary,
                                                        output))
                        .getCode());
        assertFalse(Files.exists(output));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnAdapterThatChangesOrDeletesItsReadOnlySnapshot(boolean deletes) throws Exception {
        Path original = source(Collections.emptyMap());
        byte[] originalBytes = Files.readAllBytes(original);
        DocumentRequest request = request(null, null);
        DocumentRequest convertedRequest =
                DocumentRequest.builder()
                        .documentId(request.getDocumentId())
                        .sourceRecord(
                                request.getSourceSystem(),
                                request.getSourceRecordId(),
                                request.getSourceVersion())
                        .documentType(request.getDocumentType())
                        .patient(request.getPatient())
                        .responsibleParty(request.getResponsibleParty())
                        .createdAt(request.getCreatedAt())
                        .keywords(request.getKeywords())
                        .confidentiality(request.getConfidentiality())
                        .pdf(request.getSource().getPath())
                        .build();
        Path output = temporary.resolve("adapter-result.ofd");
        PageSourceAdapter adapter =
                new PageSourceAdapter() {
                    @Override
                    public String format() {
                        return "PDF";
                    }

                    @Override
                    public void convert(Path snapshot, Path target, ResourceLimits limits)
                            throws IOException {
                        // 产物本身保持可解析，单独验证只读快照契约，避免由格式错误掩盖本次失败。
                        Files.copy(snapshot, target);
                        if (deletes) Files.delete(snapshot);
                        else Files.write(snapshot, new byte[] {1, 2, 3});
                    }
                };
        PrepareService preparation =
                new PrepareService(
                        Collections.singletonList(adapter),
                        new ClinicalSchemaRegistry(),
                        ResourceLimits.defaults());
        OfdhException failure =
                assertThrows(
                        OfdhException.class,
                        () -> preparation.prepare(convertedRequest, temporary, output));
        assertEquals("INPUT_CHANGED", failure.getCode());
        assertFalse(Files.exists(output));
        assertArrayEquals(originalBytes, Files.readAllBytes(original));
        try (java.util.stream.Stream<Path> remaining = Files.list(temporary)) {
            assertFalse(
                    remaining.anyMatch(path -> path.getFileName().toString().startsWith(".ofdh-")));
        }
    }

    @Test
    void rejectsAnUnregisteredClinicalSchema() throws Exception {
        source(Collections.emptyMap());
        Files.write(temporary.resolve("clinical.json"), "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                "CLINICAL_SCHEMA_UNKNOWN",
                assertThrows(
                                OfdhException.class,
                                () ->
                                        service(new ClinicalSchemaRegistry())
                                                .prepare(
                                                        request(
                                                                new DocumentRequest.ClinicalData(
                                                                        "clinical.json",
                                                                        "urn:test:unknown",
                                                                        "1"),
                                                                null),
                                                        temporary,
                                                        temporary.resolve("result.ofd")))
                        .getCode());
    }

    @Test
    void rejectsAnUnresolvedCover() throws Exception {
        source(
                Collections.singletonMap(
                        "OFD.xml", entry("<ofd:Cover>Doc_0/missing.png</ofd:Cover>")));
        Path output = temporary.resolve("result.ofd");
        assertEquals(
                "RESOURCE_MISSING",
                assertThrows(
                                OfdhException.class,
                                () ->
                                        service(new ClinicalSchemaRegistry())
                                                .prepare(request(null, null), temporary, output))
                        .getCode());
        assertFalse(Files.exists(output));
    }

    @Test
    void inspectsAnnotationsIndependentlyOfTheirDirectoryName() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(
                "Doc_0/Document.xml",
                document("Pages/Page_0/Content.xml")
                        .replace(
                                "</ofd:Document>",
                                "<ofd:Annotations>Notes/index.xml</ofd:Annotations></ofd:Document>"));
        entries.put(
                "Doc_0/Notes/index.xml",
                xml(
                        "Annotations",
                        "<ofd:Page PageID='1'><ofd:FileLoc>notes.xml</ofd:FileLoc></ofd:Page>"));
        entries.put(
                "Doc_0/Notes/notes.xml",
                "<!DOCTYPE PageAnnot [<!ENTITY external SYSTEM 'file:///private'>]>"
                        + xml("PageAnnot", "<ofd:Annot ID='3'>&external;</ofd:Annot>"));
        source(entries);
        Path output = temporary.resolve("result.ofd");
        assertEquals(
                "XML_INVALID",
                assertThrows(
                                OfdhException.class,
                                () ->
                                        service(new ClinicalSchemaRegistry())
                                                .prepare(request(null, null), temporary, output))
                        .getCode());
        assertFalse(Files.exists(output));
    }

    private PrepareService service(ClinicalSchemaRegistry registry) {
        return new PrepareService(Collections.emptyList(), registry, ResourceLimits.defaults());
    }

    private DocumentRequest request(DocumentRequest.ClinicalData clinical, String hash) {
        return new DocumentRequest(
                "0.1.0",
                "fixture-document",
                "urn:test:source",
                "fixture-record",
                "1",
                new DocumentRequest.Code("fixture", "urn:test:document-type"),
                new DocumentRequest.Identity("fixture-subject", "urn:test:identity"),
                null,
                new DocumentRequest.ResponsibleParty(
                        "fixture-author", "Fixture author", "urn:test:identity"),
                "2026-10-23T10:00:00+08:00",
                Collections.singletonList("format integration test"),
                new DocumentRequest.Code("test", "urn:test:classification"),
                new DocumentRequest.Source("OFD", "source.ofd", hash),
                clinical);
    }

    private Path source(Map<String, String> overrides) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("OFD.xml", entry(""));
        entries.put("Doc_0/Document.xml", document("Pages/Page_0/Content.xml"));
        entries.put(
                "Doc_0/Pages/Page_0/Content.xml",
                xml(
                        "Page",
                        "<ofd:Area><ofd:PhysicalBox>0 0 210"
                                + " 297</ofd:PhysicalBox></ofd:Area><ofd:Content><ofd:Layer"
                                + " ID='2'/></ofd:Content>"));
        entries.putAll(overrides);
        Path path = temporary.resolve("source.ofd");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }

    private String document(String page) {
        return xml(
                "Document",
                "<ofd:CommonData><ofd:MaxUnitID>2</ofd:MaxUnitID><ofd:PageArea><ofd:PhysicalBox>0 0"
                    + " 210 297</ofd:PhysicalBox></ofd:PageArea></ofd:CommonData><ofd:Pages><ofd:Page"
                    + " ID='1' BaseLoc='"
                        + page
                        + "'/></ofd:Pages>");
    }

    private String xml(String root, String content) {
        return "<ofd:" + root + " xmlns:ofd='" + Xml.NS + "'>" + content + "</ofd:" + root + ">";
    }

    private String entry(String metadata) {
        return "<ofd:OFD xmlns:ofd='"
                + Xml.NS
                + "' Version='1.0'"
                + " DocType='OFD'><ofd:DocBody><ofd:DocInfo><ofd:DocID>0123456789ABCDEF0123456789ABCDEF</ofd:DocID><ofd:Title>Fixture"
                + " title</ofd:Title>"
                + metadata
                + "</ofd:DocInfo><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>";
    }

    private String metadata(Element info, String name) {
        for (Element item : Xml.children(Xml.child(info, "CustomDatas"), "CustomData")) {
            if (name.equals(item.getAttribute("Name"))) return item.getTextContent();
        }
        return null;
    }
}
