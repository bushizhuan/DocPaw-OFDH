package org.openofdh.core.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.internal.JsonIO;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class RequestReaderTest {
    @TempDir Path directory;

    private static final String REQUEST =
            "{\"contractVersion\":\"0.1.0\",\"documentId\":\"record-1\","
                + "\"sourceSystem\":\"archive\",\"sourceRecordId\":\"record-1\",\"sourceVersion\":\"1\","
                + "\"documentType\":{\"code\":\"record\",\"scheme\":\"local\"},"
                + "\"patient\":{\"id\":\"subject-1\",\"authority\":\"local\"},"
                + "\"responsibleParty\":{\"id\":\"author-1\",\"name\":\"Author\",\"authority\":\"local\"},"
                + "\"createdAt\":\"2026-01-01T10:00:00+08:00\",\"keywords\":[\"record\"],"
                + "\"confidentiality\":{\"code\":\"internal\",\"scheme\":\"local\"},"
                + "\"source\":{\"format\":\"OFD\",\"path\":\"record.ofd\"}}";

    @Test
    void readsAndValidatesTheSamePublicModel() throws Exception {
        DocumentRequest request = read(REQUEST);
        assertEquals("record-1", request.getDocumentId());
        assertNull(request.getClinicalData());
        RequestReader.validate(request);
        assertThrows(UnsupportedOperationException.class, () -> request.getKeywords().add("other"));
    }

    @Test
    void rejectsInvalidCalendarDate() {
        assertThrows(OfdhException.class, () -> read(REQUEST.replace("2026-01-01", "2026-02-30")));
    }

    @Test
    void returnsAllFieldFailuresWithoutExposingValues() throws Exception {
        String invalid =
                REQUEST.replace("\"documentId\":\"record-1\",", "")
                        .replace("2026-01-01T10:00:00+08:00", "不允许出现在错误报告中的原始值")
                        .replace("\"id\":\"subject-1\"", "\"id\":42")
                        .replace("\"keywords\":", "\"unknown\":true,\"keywords\":");
        Path path = directory.resolve("invalid-request.json");
        Files.write(path, invalid.getBytes(StandardCharsets.UTF_8));
        ValidationReport report = RequestReader.inspect(path);
        assertFalse(report.isValid());
        assertIssue(report, "/documentId", "required", "missing");
        assertIssue(report, "/patient/id", "type", "integer");
        assertIssue(report, "/unknown", "additionalProperties", "boolean");
        assertIssue(report, "/createdAt", "format", "string");
        assertFalse(JsonIO.string(report).contains("不允许出现在错误报告中的原始值"));
        OfdhException failure = assertThrows(OfdhException.class, () -> RequestReader.read(path));
        assertEquals(JsonIO.string(report.getIssues()), JsonIO.string(failure.getIssues()));
        assertFalse(failure.isTruncated());
        assertThrows(UnsupportedOperationException.class, () -> report.getIssues().clear());
    }

    @Test
    void identifiesMissingParentWithoutInventingNestedFields() throws Exception {
        Path path = directory.resolve("missing-patient.json");
        Files.write(
                path,
                REQUEST.replace("\"patient\":{\"id\":\"subject-1\",\"authority\":\"local\"},", "")
                        .getBytes(StandardCharsets.UTF_8));
        ValidationReport report = RequestReader.inspect(path);
        assertIssue(report, "/patient", "required", "missing");
        assertEquals(1, report.getIssues().size());
        assertFalse(RequestReader.inspect((DocumentRequest) null).isValid());
    }

    @Test
    void rejectsLeapSecondsBeforeConvertingToOffsetDateTime() throws Exception {
        Path path = directory.resolve("leap-second.json");
        Files.write(
                path,
                REQUEST.replace("2026-01-01T10:00:00+08:00", "2016-12-31T23:59:60Z")
                        .getBytes(StandardCharsets.UTF_8));
        ValidationReport report = RequestReader.inspect(path);
        assertIssue(report, "/createdAt", "format", "string");
        assertEquals(1, report.getIssues().size());
        OfdhException failure = assertThrows(OfdhException.class, () -> RequestReader.read(path));
        assertEquals("REQUEST_INVALID", failure.getCode());
        assertEquals("/createdAt", failure.getIssues().get(0).getPath());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\u0000", "\\u000B", "\\uFFFE", "\\uD800", "\\uDC00"})
    void identifiesJsonCharactersThatCannotBeWrittenToOfdMetadata(String escaped) throws Exception {
        Path file = directory.resolve("xml-character.json");
        Files.write(
                file,
                REQUEST.replace("Author", "private-value" + escaped)
                        .getBytes(StandardCharsets.UTF_8));
        ValidationReport report = RequestReader.inspect(file);
        assertIssue(report, "/responsibleParty/name", "xmlCharacter", "string");
        assertFalse(JsonIO.string(report).contains("private-value"));
        OfdhException failure = assertThrows(OfdhException.class, () -> RequestReader.read(file));
        assertEquals("REQUEST_INVALID", failure.getCode());
        assertEquals(report.getIssues().size(), failure.getIssues().size());
    }

    @Test
    void checksObjectAndArrayTextWithoutRejectingValidSupplementaryCharacters() throws Exception {
        DocumentRequest valid = read(REQUEST.replace("Author", "\\uD840\\uDC00\\t\\n\\r"));
        assertTrue(RequestReader.inspect(valid).isValid());
        DocumentRequest invalid =
                DocumentRequest.builder()
                        .documentId(valid.getDocumentId())
                        .sourceRecord(
                                valid.getSourceSystem(),
                                valid.getSourceRecordId(),
                                valid.getSourceVersion())
                        .documentType(valid.getDocumentType())
                        .patient(valid.getPatient())
                        .responsibleParty(valid.getResponsibleParty())
                        .createdAt(valid.getCreatedAt())
                        .keywords("private-keyword" + (char) 0)
                        .confidentiality(valid.getConfidentiality())
                        .source(valid.getSource())
                        .build();
        ValidationReport report = RequestReader.inspect(invalid);
        assertIssue(report, "/keywords/0", "xmlCharacter", "string");
        assertFalse(JsonIO.string(report).contains("private-keyword"));
    }

    @Test
    void truncatesXmlCharacterIssuesFromALargeArrayWithoutAcceptingTheRequest() throws Exception {
        ObjectNode request = (ObjectNode) JsonIO.parse(REQUEST.getBytes(StandardCharsets.UTF_8));
        ArrayNode keywords = request.putArray("keywords");
        for (int index = 0; index < 10_000; index++) {
            keywords.add("private-array-" + index + (char) 0);
        }
        byte[] bytes = JsonIO.bytes(request);
        assertTrue(bytes.length < (1 << 20));
        Path file = directory.resolve("large-keywords.json");
        Files.write(file, bytes);
        ValidationReport report = RequestReader.inspect(file);
        assertFalse(report.isValid());
        assertTrue(report.isTruncated());
        assertEquals(ValidationReport.MAX_ISSUES, report.getIssues().size());
        assertIssue(report, "/keywords/0", "xmlCharacter", "string");
        assertTrue(
                report.getIssues().stream()
                        .allMatch(issue -> "xmlCharacter".equals(issue.getKeyword())));
        assertFalse(JsonIO.string(report).contains("private-array-"));
        OfdhException failure = assertThrows(OfdhException.class, () -> RequestReader.read(file));
        assertEquals("REQUEST_INVALID", failure.getCode());
        assertTrue(failure.isTruncated());
        assertEquals(ValidationReport.MAX_ISSUES, failure.getIssues().size());
    }

    private static void assertIssue(
            ValidationReport report, String path, String keyword, String actualType) {
        ValidationIssue issue =
                report.getIssues().stream()
                        .filter(
                                item ->
                                        path.equals(item.getPath())
                                                && keyword.equals(item.getKeyword()))
                        .findFirst()
                        .orElseThrow(AssertionError::new);
        assertEquals(actualType, issue.getActualType());
        assertFalse(issue.getMessage().isEmpty());
        assertFalse(issue.getExpected().isEmpty());
    }

    @Test
    void rejectsTraversalUnknownFieldsAndDuplicateKeys() {
        assertThrows(
                OfdhException.class, () -> read(REQUEST.replace("record.ofd", "../record.ofd")));
        assertThrows(
                OfdhException.class, () -> read(REQUEST.replace("\"keywords\"", "\"keyword\"")));
        assertThrows(
                OfdhException.class,
                () ->
                        read(
                                REQUEST.replace(
                                        "\"documentId\":",
                                        "\"documentId\":\"duplicate\",\"documentId\":")));
    }

    @Test
    void rejectsTrailingObjectsAndMalformedUtf8() {
        assertThrows(OfdhException.class, () -> read(REQUEST + "{}"));
        assertThrows(OfdhException.class, () -> JsonIO.parse(new byte[] {(byte) 0xc3, 0x28}));
    }

    @Test
    void rejectsExternalSchemaReferencesWithoutResolvingThem() throws Exception {
        String schema =
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                        + "\"$ref\":\"http://127.0.0.1:9/private\"}";
        OfdhException failure =
                assertThrows(
                        OfdhException.class,
                        () ->
                                new LocalSchema(
                                        JsonIO.parse(schema.getBytes(StandardCharsets.UTF_8))));
        assertEquals("SCHEMA_EXTERNAL_REFERENCE", failure.getCode());
    }

    private DocumentRequest read(String input) throws Exception {
        Path file = directory.resolve("request.json");
        Files.write(file, input.getBytes(StandardCharsets.UTF_8));
        return RequestReader.read(file);
    }
}
