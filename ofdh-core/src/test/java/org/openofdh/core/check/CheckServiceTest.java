package org.openofdh.core.check;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.internal.JsonIO;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.openofdh.core.layout.PageDependencyPlanner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class CheckServiceTest {
    @TempDir Path directory;
    private int sequence;

    @Test
    void reportsPreparedAndFinalStagesDifferentlyWithoutChangingBytes() throws Exception {
        Path file = write(entries());
        String before = FileSafety.sha256(file);
        CheckReport prepared = check(file, CheckProfile.PREPARED);
        assertEquals("INCOMPLETE", prepared.getAssessment());
        assertEquals(0, prepared.getCoverage().getResultCounts().get("FAIL"));
        assertEquals("NOT_CHECKED", result(prepared, "OFDH-012"));
        assertEquals("ISSUES_FOUND", check(file, CheckProfile.FINAL).getAssessment());
        assertEquals(before, FileSafety.sha256(file));
        assertEquals(before, prepared.getArtifact().getSha256());
        assertFalse(prepared.getCoverage().isScopeComplete());
        assertEquals(prepared.getResults().size(), prepared.getCoverage().getCatalogRuleCount());
    }

    @Test
    void distinguishesTheTextCompressionProfileFromStandardLinearization() throws Exception {
        CheckReport deflated = check(write(entries()), CheckProfile.PREPARED);
        assertEquals("PASS", result(deflated, "PKG-002"));
        assertEquals("NOT_CHECKED", result(deflated, "OFDH-015"));

        CheckReport stored =
                check(write(entries(), Collections.singleton("OFD.xml")), CheckProfile.PREPARED);
        assertEquals("PASS", result(stored, "PKG-001"));
        assertEquals("FAIL", result(stored, "PKG-002"));
        CheckReport.Result compression =
                stored.getResults().stream()
                        .filter(value -> "PKG-002".equals(value.getRuleId()))
                        .findFirst()
                        .get();
        assertEquals("PROJECT", compression.getRuleSource().getKind());
        assertNull(compression.getClause());
        assertEquals("OFD.xml", compression.getLocation());
    }

    @Test
    void checksJsonAndTextMembersWithoutClassifyingBinaryMembersAsText() throws Exception {
        Map<String, byte[]> content = withAttachment("{}".getBytes(StandardCharsets.UTF_8));
        String attachment = "Doc_0/Attachs/Attach_0.json";
        assertEquals(
                "FAIL",
                result(
                        check(
                                write(content, Collections.singleton(attachment)),
                                CheckProfile.PREPARED),
                        "PKG-002"));
        content = entries();
        content.put("Doc_0/Attachs/notes.TXT", "archive metadata".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                "FAIL",
                result(
                        check(
                                write(content, Collections.singleton("Doc_0/Attachs/notes.TXT")),
                                CheckProfile.PREPARED),
                        "PKG-002"));
        content.remove("Doc_0/Attachs/notes.TXT");
        byte[] png;
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(
                    new java.awt.image.BufferedImage(
                            1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB),
                    "png",
                    output);
            png = output.toByteArray();
        }
        content.put("Doc_0/Res/Image_0.png", png);
        content.put("Doc_0/Attachs/empty.txt", new byte[0]);
        Set<String> stored =
                new java.util.HashSet<>(
                        java.util.Arrays.asList(
                                "Doc_0/Res/Image_0.png", "Doc_0/Attachs/empty.txt"));
        assertEquals(
                "PASS", result(check(write(content, stored), CheckProfile.PREPARED), "PKG-002"));
    }

    @Test
    void serializedReportsMeetThePublishedContractIncludingNullFields() throws Exception {
        CheckReport report = check(write(entries()), CheckProfile.PREPARED);
        try (InputStream input =
                getClass()
                        .getResourceAsStream("/org/openofdh/contracts/check-report.schema.json")) {
            assertNotNull(input);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode schema = mapper.readTree(input);
            JsonNode value = mapper.readTree(JsonIO.bytes(report));
            assertTrue(
                    SchemaRegistry.withDialect(Dialects.getDraft202012())
                            .getSchema(schema)
                            .validate(value)
                            .isEmpty());
            assertTrue(value.get("results").get(0).has("clause"));
        }
    }

    @Test
    void retainsARealHashForAnInvalidContainer() throws Exception {
        Path path = directory.resolve("broken.ofd");
        Files.write(path, new byte[] {1, 2, 3});
        CheckReport report = check(path, CheckProfile.FINAL);
        assertEquals("FAIL", result(report, "PKG-001"));
        assertEquals(FileSafety.sha256(path), report.getArtifact().getSha256());
        assertNull(report.getSignatureVerification().getSignatureCount());
        assertTrue(
                new ObjectMapper()
                        .readTree(JsonIO.bytes(report))
                        .get("signatureVerification")
                        .has("signatureCount"));
    }

    @Test
    void distinguishesThirdPartyMetadataFromBrokenDeclaredMetadata() throws Exception {
        Map<String, byte[]> foreign = entries();
        replace(foreign, "OFD.xml", "openofdh.metadataProfile", "foreign.profile");
        CheckReport foreignReport = check(write(foreign), CheckProfile.PREPARED);
        assertEquals("NOT_CHECKED", result(foreignReport, "META-001"));
        assertEquals("NOT_CHECKED", result(foreignReport, "OFDH-005"));
        Map<String, byte[]> incomplete = entries();
        replace(incomplete, "OFD.xml", "openofdh.patient.id", "discarded.patient.id");
        assertEquals("FAIL", result(check(write(incomplete), CheckProfile.PREPARED), "META-001"));
    }

    @Test
    void acceptsEquivalentNamespacePrefixes() throws Exception {
        Map<String, byte[]> content = entries();
        for (String name : content.keySet()) {
            replace(content, name, "ofd:", "x:");
            replace(content, name, "xmlns:ofd", "xmlns:x");
        }
        assertEquals("PASS", result(check(write(content), CheckProfile.PREPARED), "OFDH-002"));
    }

    @Test
    void rejectsMissingPagesAndResourceReferences() throws Exception {
        Map<String, byte[]> content = entries();
        content.remove("Doc_0/Pages/Page_0/Content.xml");
        assertEquals("FAIL", result(check(write(content), CheckProfile.PREPARED), "OFDH-006"));
        content = entries();
        replace(
                content,
                "Doc_0/Pages/Page_0/Content.xml",
                "<ofd:Layer ID='2'/>",
                "<ofd:Layer ID='2'><ofd:TextObject ID='3' Font='99' Size='3'><ofd:TextCode X='0'"
                        + " Y='3'>A</ofd:TextCode></ofd:TextObject></ofd:Layer>");
        assertEquals("FAIL", result(check(write(content), CheckProfile.PREPARED), "OFDH-007"));
    }

    @Test
    void rejectsAResourceIdentifierUsedAsTheWrongCategoryWithoutReordering() throws Exception {
        Map<String, byte[]> content =
                withResources("<ofd:DrawParams><ofd:DrawParam ID='3'/></ofd:DrawParams>");
        replace(
                content,
                "Doc_0/Pages/Page_0/Content.xml",
                "<ofd:Layer ID='2'/>",
                "<ofd:Layer ID='2'><ofd:TextObject ID='4' Font='3' Size='3'><ofd:TextCode X='0'"
                        + " Y='3'>A</ofd:TextCode></ofd:TextObject></ofd:Layer>");
        assertInvalidDependencies(content);
    }

    @Test
    void rejectsMissingAndCircularReferencesBetweenResources() throws Exception {
        assertInvalidDependencies(
                withResources(
                        "<ofd:DrawParams><ofd:DrawParam ID='3' Relative='4'/></ofd:DrawParams>"));
        assertInvalidDependencies(
                withResources(
                        "<ofd:DrawParams><ofd:DrawParam ID='3' Relative='4'/>"
                                + "<ofd:DrawParam ID='4' Relative='3'/></ofd:DrawParams>"));
    }

    @Test
    void rejectsInvalidTemplateRootsAndMissingAnnotationDirectories() throws Exception {
        Map<String, byte[]> content = entries();
        replace(
                content,
                "Doc_0/Document.xml",
                "</ofd:CommonData>",
                "<ofd:TemplatePage ID='3' BaseLoc='Template.xml'/></ofd:CommonData>");
        content.put("Doc_0/Template.xml", xml("Document", ""));
        assertInvalidDependencies(content);
        content = entries();
        replace(
                content,
                "Doc_0/Document.xml",
                "</ofd:Document>",
                "<ofd:Annotations>missing-annotations.xml</ofd:Annotations></ofd:Document>");
        assertInvalidDependencies(content);
    }

    @Test
    void reportsUnknownDrawingExtensionsWithoutClaimingCompleteReferenceChecks() throws Exception {
        Map<String, byte[]> content = entries();
        replace(
                content,
                "Doc_0/Pages/Page_0/Content.xml",
                "<ofd:Layer ID='2'/>",
                "<ofd:Layer ID='2'><vendor:Vector xmlns:vendor='urn:test:drawing'/></ofd:Layer>");
        Path file = write(content);
        String original = FileSafety.sha256(file);
        CheckReport report = check(file, CheckProfile.PREPARED);
        assertEquals("NOT_CHECKED", result(report, "OFDH-007"));
        assertEquals("INCOMPLETE", report.getAssessment());
        assertEquals(original, FileSafety.sha256(file));
    }

    @Test
    void leavesExternalNonRenderingAttachmentsOutsideTheDependencyGraph() throws Exception {
        Map<String, byte[]> content = withAttachment("{}".getBytes(StandardCharsets.UTF_8));
        replace(
                content,
                "Doc_0/Attachs/Attachments.xml",
                "Attach_0.json",
                "https://example.invalid/clinical.json");
        CheckReport report = check(write(content), CheckProfile.PREPARED);
        assertEquals("PASS", result(report, "OFDH-007"));
        assertEquals("NOT_CHECKED", result(report, "OFDH-010"));
        assertEquals("INCOMPLETE", report.getAssessment());
    }

    @Test
    void checksTheEncodingOfExternalLayersWithoutAnXmlExtension() throws Exception {
        Map<String, byte[]> content = entries();
        replace(
                content,
                "Doc_0/Pages/Page_0/Content.xml",
                "<ofd:Layer ID='2'/>",
                "<ofd:Layer ID='2' BaseLoc='layer.data'/>");
        String layer = "Doc_0/Pages/Page_0/layer.data";
        byte[] valid =
                xml(
                        "Layer",
                        "<ofd:PathObject ID='3' Boundary='0 0 10 10'><ofd:AbbreviatedData>M 0 0 L"
                            + " 10 10</ofd:AbbreviatedData></ofd:PathObject>");
        content.put(layer, valid);
        Path file = write(content);
        assertEquals("PASS", result(check(file, CheckProfile.PREPARED), "OFDH-011"));
        try (SafePackage archive = SafePackage.open(file, ResourceLimits.defaults())) {
            java.util.List<String> parsed =
                    PageDependencyPlanner.plan(archive, ResourceLimits.defaults()).getXmlEntries();
            assertTrue(parsed.contains(layer));
            assertThrows(UnsupportedOperationException.class, () -> parsed.add("unparsed.xml"));
        }
        String xml =
                "<?xml version='1.0' encoding='UTF-16'?>"
                        + new String(valid, StandardCharsets.UTF_8);
        content.put(layer, xml.getBytes(StandardCharsets.UTF_16));
        Path invalid = write(content);
        String original = FileSafety.sha256(invalid);
        CheckReport report = check(invalid, CheckProfile.PREPARED);
        assertEquals("PASS", result(report, "OFDH-007"));
        assertEquals("FAIL", result(report, "OFDH-011"));
        assertEquals("ISSUES_FOUND", report.getAssessment());
        assertEquals(original, FileSafety.sha256(invalid));
    }

    @Test
    void rejectsExternalEntitiesWithoutReadingTheirTarget() throws Exception {
        Map<String, byte[]> content = entries();
        String original = new String(content.get("OFD.xml"), StandardCharsets.UTF_8);
        content.put(
                "OFD.xml",
                ("<!DOCTYPE OFD [<!ENTITY patient SYSTEM 'file:///nonexistent-sensitive-file'>]>"
                                + original)
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("FAIL", result(check(write(content), CheckProfile.PREPARED), "OFDH-001"));
    }

    @Test
    void rejectsInvalidUtf8AndDuplicateJsonKeysInRegisteredAttachments() throws Exception {
        Map<String, byte[]> invalidUtf8 =
                withAttachment(new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, '"', '}'});
        assertEquals("FAIL", result(check(write(invalidUtf8), CheckProfile.PREPARED), "OFDH-011"));
        Map<String, byte[]> duplicate =
                withAttachment("{\"value\":1,\"value\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals("FAIL", result(check(write(duplicate), CheckProfile.PREPARED), "OFDH-011"));
    }

    @Test
    void detectsAChangedClinicalAttachment() throws Exception {
        byte[] content = "{\"observation\":1}".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = withAttachment(content);
        addMetadata(entries, "clinical.sha256", Inspection.sha256(content));
        addMetadata(entries, "clinical.schemaId", "urn:test:observation");
        addMetadata(entries, "clinical.schemaVersion", "1");
        assertEquals("PASS", result(check(write(entries), CheckProfile.PREPARED), "CLINICAL-001"));
        entries.put(
                "Doc_0/Attachs/Attach_0.json",
                "{\"observation\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals("FAIL", result(check(write(entries), CheckProfile.PREPARED), "CLINICAL-001"));
    }

    @Test
    void onlyChecksEnvelopeSemanticsAfterAnExplicitProfileDeclaration() throws Exception {
        byte[] thirdParty =
                "{\"head\":{\"version\":42},\"meta\":{},\"identifier\":{\"documentId\":\"another-domain\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> entries = withAttachment(thirdParty);
        addMetadata(entries, "clinical.sha256", Inspection.sha256(thirdParty));
        addMetadata(entries, "clinical.schemaId", "urn:test:third-party");
        addMetadata(entries, "clinical.schemaVersion", "1");
        assertEquals(
                "NOT_CHECKED",
                result(check(write(entries), CheckProfile.PREPARED), "CLINICAL-002"));
        byte[] declared =
                "{\"head\":{\"profile\":\"urn:openofdh:clinical-envelope:0.1.0\"}}"
                        .getBytes(StandardCharsets.UTF_8);
        entries = withAttachment(declared);
        addMetadata(entries, "clinical.sha256", Inspection.sha256(declared));
        addMetadata(entries, "clinical.schemaId", "urn:test:declared");
        addMetadata(entries, "clinical.schemaVersion", "1");
        assertEquals("FAIL", result(check(write(entries), CheckProfile.PREPARED), "CLINICAL-002"));
    }

    @Test
    void doesNotTreatMissingFontExemptionEvidenceAsSuccess() throws Exception {
        Map<String, byte[]> content = entries();
        replace(
                content,
                "Doc_0/Document.xml",
                "</ofd:CommonData>",
                "<ofd:PublicRes>PublicRes.xml</ofd:PublicRes></ofd:CommonData>");
        content.put(
                "Doc_0/PublicRes.xml",
                xml("Res", "<ofd:Fonts><ofd:Font ID='3' FontName='unknown-font'/></ofd:Fonts>"));
        assertEquals(
                "NOT_CHECKED", result(check(write(content), CheckProfile.PREPARED), "OFDH-008"));
    }

    @Test
    void rejectsSignatureListsWithDuplicateIdentifiers() throws Exception {
        Map<String, byte[]> content = entries();
        replace(
                content,
                "OFD.xml",
                "</ofd:DocBody>",
                "<ofd:Signatures>Doc_0/Signs/Signatures.xml</ofd:Signatures></ofd:DocBody>");
        content.put(
                "Doc_0/Signs/Signatures.xml",
                xml(
                        "Signatures",
                        "<ofd:Signature ID='1' BaseLoc='Sign_0/Signature.xml'/><ofd:Signature"
                                + " ID='1' BaseLoc='Sign_1/Signature.xml'/>"));
        CheckReport report = check(write(content), CheckProfile.FINAL);
        assertEquals("FAILED", report.getSignatureVerification().getEnumeration());
        assertEquals("FAIL", result(report, "OFDH-012"));
    }

    private CheckReport check(Path path, CheckProfile profile) throws Exception {
        return new CheckService(ResourceLimits.defaults()).check(path, profile);
    }

    private void assertInvalidDependencies(Map<String, byte[]> content) throws Exception {
        Path file = write(content);
        String original = FileSafety.sha256(file);
        CheckReport report = check(file, CheckProfile.PREPARED);
        assertEquals("FAIL", result(report, "OFDH-007"));
        assertEquals("ISSUES_FOUND", report.getAssessment());
        try (SafePackage archive = SafePackage.open(file, ResourceLimits.defaults())) {
            OfdhException error =
                    assertThrows(
                            OfdhException.class,
                            () -> PageDependencyPlanner.plan(archive, ResourceLimits.defaults()));
            assertEquals("PAGE_LAYOUT_INVALID", error.getCode());
        }
        assertEquals(original, FileSafety.sha256(file));
    }

    private static Map<String, byte[]> withResources(String definitions) {
        Map<String, byte[]> content = entries();
        replace(
                content,
                "Doc_0/Document.xml",
                "</ofd:CommonData>",
                "<ofd:PublicRes>PublicRes.xml</ofd:PublicRes></ofd:CommonData>");
        content.put("Doc_0/PublicRes.xml", xml("Res", definitions));
        return content;
    }

    static String result(CheckReport report, String id) {
        return report.getResults().stream()
                .filter(value -> value.getRuleId().equals(id))
                .findFirst()
                .get()
                .getResult();
    }

    Path write(Map<String, byte[]> content) throws Exception {
        return write(content, Collections.<String>emptySet());
    }

    private Path write(Map<String, byte[]> content, Set<String> stored) throws Exception {
        Path path = directory.resolve("document-" + sequence++ + ".ofd");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : content.entrySet()) {
                ZipEntry member = new ZipEntry(entry.getKey());
                if (stored.contains(entry.getKey())) {
                    CRC32 crc = new CRC32();
                    crc.update(entry.getValue());
                    member.setMethod(ZipEntry.STORED);
                    member.setSize(entry.getValue().length);
                    member.setCompressedSize(entry.getValue().length);
                    member.setCrc(crc.getValue());
                }
                output.putNextEntry(member);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    static Map<String, byte[]> entries() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        StringBuilder metadata = new StringBuilder();
        Map<String, String> values = new LinkedHashMap<>();
        for (String key :
                new String[] {
                    "documentId",
                    "sourceSystem",
                    "sourceRecordId",
                    "sourceVersion",
                    "patient.id",
                    "patient.authority",
                    "responsibleParty.id",
                    "responsibleParty.authority",
                    "documentType.code",
                    "documentType.scheme",
                    "confidentiality.code",
                    "confidentiality.scheme"
                }) values.put(key, "test");
        values.put("metadataProfile", "0.1.0");
        values.put("sourceCreatedAt", "2026-10-23T08:00:00Z");
        values.put("generatedAt", "2026-10-23T08:30:00Z");
        values.put("sourceSha256", String.join("", Collections.nCopies(64, "0")));
        for (Map.Entry<String, String> value : values.entrySet())
            metadata.append("<ofd:CustomData Name='openofdh.")
                    .append(value.getKey())
                    .append("'>")
                    .append(value.getValue())
                    .append("</ofd:CustomData>");
        files.put(
                "OFD.xml",
                ("<ofd:OFD xmlns:ofd='"
                                + Xml.NS
                                + "' Version='1.0'"
                                + " DocType='OFD-H'><ofd:DocBody><ofd:DocInfo><ofd:DocID>0123456789ABCDEF0123456789ABCDEF</ofd:DocID><ofd:Author>Test</ofd:Author><ofd:CreationDate>2026-10-23</ofd:CreationDate><ofd:Keywords><ofd:Keyword>format</ofd:Keyword></ofd:Keywords><ofd:CustomDatas>"
                                + metadata
                                + "</ofd:CustomDatas></ofd:DocInfo><ofd:DocRoot>Doc_0/Document.xml</ofd:DocRoot></ofd:DocBody></ofd:OFD>")
                        .getBytes(StandardCharsets.UTF_8));
        files.put(
                "Doc_0/Document.xml",
                xml(
                        "Document",
                        "<ofd:CommonData><ofd:MaxUnitID>2</ofd:MaxUnitID><ofd:PageArea><ofd:PhysicalBox>0"
                            + " 0 210"
                            + " 297</ofd:PhysicalBox></ofd:PageArea></ofd:CommonData><ofd:Pages><ofd:Page"
                            + " ID='1' BaseLoc='Pages/Page_0/Content.xml'/></ofd:Pages>"));
        files.put(
                "Doc_0/Pages/Page_0/Content.xml",
                xml(
                        "Page",
                        "<ofd:Area><ofd:PhysicalBox>0 0 210"
                                + " 297</ofd:PhysicalBox></ofd:Area><ofd:Content><ofd:Layer"
                                + " ID='2'/></ofd:Content>"));
        return files;
    }

    private static Map<String, byte[]> withAttachment(byte[] content) {
        Map<String, byte[]> files = entries();
        replace(
                files,
                "Doc_0/Document.xml",
                "</ofd:Document>",
                "<ofd:Attachments>Attachs/Attachments.xml</ofd:Attachments></ofd:Document>");
        files.put(
                "Doc_0/Attachs/Attachments.xml",
                xml(
                        "Attachments",
                        "<ofd:Attachment ID='clinical-attachment'"
                            + " Name='clinical.json'><ofd:FileLoc>Attach_0.json</ofd:FileLoc></ofd:Attachment>"));
        files.put("Doc_0/Attachs/Attach_0.json", content);
        return files;
    }

    private static void addMetadata(Map<String, byte[]> files, String key, String value) {
        replace(
                files,
                "OFD.xml",
                "</ofd:CustomDatas>",
                "<ofd:CustomData Name='openofdh."
                        + key
                        + "'>"
                        + value
                        + "</ofd:CustomData></ofd:CustomDatas>");
    }

    static void replace(Map<String, byte[]> files, String name, String before, String after) {
        files.put(
                name,
                new String(files.get(name), StandardCharsets.UTF_8)
                        .replace(before, after)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] xml(String root, String content) {
        return ("<ofd:" + root + " xmlns:ofd='" + Xml.NS + "'>" + content + "</ofd:" + root + ">")
                .getBytes(StandardCharsets.UTF_8);
    }
}
