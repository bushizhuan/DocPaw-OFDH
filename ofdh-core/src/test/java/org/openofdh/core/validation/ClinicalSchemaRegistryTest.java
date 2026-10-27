package org.openofdh.core.validation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.OfdhException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

class ClinicalSchemaRegistryTest {
    @TempDir Path directory;

    @Test
    void doesNotInferThirdPartyFieldsThatResembleTheProjectEnvelope() throws Exception {
        ClinicalSchemaRegistry registry =
                registry(
                        "{\"type\":\"object\",\"required\":[\"head\"],\"properties\":{\"head\":{\"type\":\"object\",\"required\":[\"version\"],\"properties\":{\"version\":{\"type\":\"integer\"}}}}}");
        Path data =
                data(
                        "{\"head\":{\"version\":42},\"identifier\":{\"documentId\":\"a-different-domain-id\"},\"patient\":{\"id\":17}}");
        assertDoesNotThrow(() -> registry.validate(request(), data));
    }

    @Test
    void stillValidatesTheRegisteredThirdPartySchema() throws Exception {
        ClinicalSchemaRegistry registry =
                registry(
                        "{\"type\":\"object\",\"required\":[\"observation\"],\"properties\":{\"observation\":{\"type\":\"number\"}}}");
        Path data = data("{\"observation\":\"invalid\"}");
        assertEquals(
                "CLINICAL_DATA_INVALID",
                assertThrows(OfdhException.class, () -> registry.validate(request(), data))
                        .getCode());
    }

    @Test
    void validatesExplicitEnvelopeIdentityAndEquivalentTimezoneRepresentations() throws Exception {
        ClinicalSchemaRegistry registry = registry("{\"type\":\"object\"}");
        Path valid = data(envelope());
        assertDoesNotThrow(() -> registry.validate(request(), valid));
        Path altered =
                data(envelope().replace("\"documentVersion\":\"7\"", "\"documentVersion\":\"8\""));
        assertEquals(
                "SOURCE_VERSION_MISMATCH",
                assertThrows(OfdhException.class, () -> registry.validate(request(), altered))
                        .getCode());
    }

    @Test
    void rejectsIncompleteOrChronologicallyInvalidExplicitEnvelopes() throws Exception {
        ClinicalSchemaRegistry registry = registry("{\"type\":\"object\"}");
        Path missing = data("{\"head\":{\"profile\":\"" + ClinicalEnvelopeProfile.ID + "\"}}");
        assertEquals(
                "CLINICAL_ENVELOPE_INVALID",
                assertThrows(OfdhException.class, () -> registry.validate(request(), missing))
                        .getCode());
        Path reversed =
                data(
                        envelope()
                                .replace(
                                        "\"modifiedAt\":\"2026-10-23T02:01:00Z\"",
                                        "\"modifiedAt\":\"2026-10-22T02:01:00Z\""));
        assertEquals(
                "CLINICAL_ENVELOPE_INVALID",
                assertThrows(OfdhException.class, () -> registry.validate(request(), reversed))
                        .getCode());
    }

    @Test
    void rejectsAnUnsupportedProjectEnvelopeVersion() throws Exception {
        ClinicalSchemaRegistry registry = registry("{\"type\":\"object\"}");
        Path data =
                data(
                        envelope()
                                .replace(
                                        ClinicalEnvelopeProfile.ID,
                                        "urn:openofdh:clinical-envelope:99.0.0"));
        assertEquals(
                "CLINICAL_PROFILE_UNSUPPORTED",
                assertThrows(OfdhException.class, () -> registry.validate(request(), data))
                        .getCode());
    }

    @Test
    void collectsIndependentEnvelopeAndSourceFailures() throws Exception {
        ClinicalSchemaRegistry registry = registry("{\"type\":\"object\"}");
        Path input =
                data(
                        envelope()
                                .replace("\"documentVersion\":\"7\"", "\"documentVersion\":\"8\"")
                                .replace("\"id\":\"subject\"", "\"id\":\"another-subject\"")
                                .replace("2026-10-23T02:01:00Z", "2026-10-22T02:01:00Z"));
        ValidationReport report = registry.inspect(request(), input);
        assertEquals(3, report.getIssues().size());
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/head/modifiedAt")
                                                && issue.getKeyword().equals("chronology")));
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(issue -> issue.getPath().equals("/head/documentVersion")));
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(issue -> issue.getPath().equals("/patient/id")));
        assertFalse(org.openofdh.core.internal.JsonIO.string(report).contains("another-subject"));
    }

    @Test
    void reportsMissingEnvelopeParentsOnceAndDoesNotParseMalformedTypes() throws Exception {
        ClinicalSchemaRegistry registry = registry("{\"type\":\"object\"}");
        Path input =
                data(
                        "{\"head\":{\"profile\":\""
                                + ClinicalEnvelopeProfile.ID
                                + "\",\"createdAt\":42,\"modifiedAt\":false}}");
        ValidationReport report = registry.inspect(request(), input);
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/identifier")
                                                && issue.getKeyword().equals("required")));
        assertFalse(
                report.getIssues().stream()
                        .anyMatch(issue -> issue.getPath().startsWith("/identifier/")));
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/head/createdAt")
                                                && issue.getKeyword().equals("type")));
        assertFalse(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/head/createdAt")
                                                && issue.getKeyword().equals("format")));
    }

    @Test
    void registersCallerOwnedStreamsWithoutClosingThem() throws Exception {
        final boolean[] closed = {false};
        ByteArrayInputStream input =
                new ByteArrayInputStream(
                        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"}"
                                .getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public void close() {
                        closed[0] = true;
                    }
                };
        ClinicalSchemaRegistry registry =
                new ClinicalSchemaRegistry().register("urn:test:clinical", "1", input);
        assertFalse(closed[0]);
        assertTrue(registry.inspect(request(), data("{}")).isValid());
        OfdhException unknown =
                assertThrows(
                        OfdhException.class,
                        () -> new ClinicalSchemaRegistry().validate(request(), data("{}")));
        assertEquals("CLINICAL_SCHEMA_UNKNOWN", unknown.getCode());
        assertEquals("/clinicalData/schemaId", unknown.getIssues().get(0).getPath());
    }

    @Test
    void rejectsMalformedOptionalEncounterIdentityWhenProvided() throws Exception {
        DocumentRequest original = request();
        DocumentRequest withEncounter =
                new DocumentRequest(
                        original.getContractVersion(),
                        original.getDocumentId(),
                        original.getSourceSystem(),
                        original.getSourceRecordId(),
                        original.getSourceVersion(),
                        original.getDocumentType(),
                        original.getPatient(),
                        new DocumentRequest.Identity("encounter", "urn:test:encounter"),
                        original.getResponsibleParty(),
                        original.getCreatedAt(),
                        original.getKeywords(),
                        original.getConfidentiality(),
                        original.getSource(),
                        original.getClinicalData());
        Path input =
                data(envelope().replace("\"patient\":", "\"encounter\":{\"id\":42},\"patient\":"));
        ValidationReport report = registry("{\"type\":\"object\"}").inspect(withEncounter, input);
        assertFalse(report.isValid());
        assertEquals("/encounter/id", report.getIssues().get(0).getPath());
        assertEquals("type", report.getIssues().get(0).getKeyword());
    }

    private ClinicalSchemaRegistry registry(String definition) throws Exception {
        String schema =
                "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                        + definition.substring(1);
        Path path = Files.createTempFile(directory, "schema-", ".json");
        Files.write(path, schema.getBytes(StandardCharsets.UTF_8));
        return new ClinicalSchemaRegistry().register("urn:test:clinical", "1", path);
    }

    private Path data(String content) throws Exception {
        Path path = Files.createTempFile(directory, "clinical-", ".json");
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private DocumentRequest request() {
        return new DocumentRequest(
                "0.1.0",
                "document",
                "source",
                "record",
                "7",
                new DocumentRequest.Code("record", "urn:test:type"),
                new DocumentRequest.Identity("subject", "urn:test:subject"),
                null,
                new DocumentRequest.ResponsibleParty("author", "Author", "urn:test:author"),
                "2026-10-23T10:00:00+08:00",
                Collections.singletonList("record"),
                new DocumentRequest.Code("internal", "urn:test:confidentiality"),
                new DocumentRequest.Source("OFD", "source.ofd", null),
                new DocumentRequest.ClinicalData("clinical.json", "urn:test:clinical", "1"));
    }

    private String envelope() {
        return "{\"head\":{\"profile\":\""
                + ClinicalEnvelopeProfile.ID
                + "\",\"schemaId\":\"urn:test:clinical\",\"version\":\"1\",\"documentVersion\":\"7\",\"createdAt\":\"2026-10-23T02:00:00Z\",\"modifiedAt\":\"2026-10-23T02:01:00Z\"},"
                + "\"identifier\":{\"documentId\":\"document\",\"documentType\":{\"code\":\"record\",\"scheme\":\"urn:test:type\"},\"confidentiality\":{\"code\":\"internal\",\"scheme\":\"urn:test:confidentiality\"}},"
                + "\"patient\":{\"id\":\"subject\",\"authority\":\"urn:test:subject\"},\"meta\":{\"creator\":{\"id\":\"author\",\"name\":\"Author\",\"authority\":\"urn:test:author\"},"
                + "\"keywords\":[\"record\"],\"properties\":{\"sourceSystem\":\"source\",\"sourceRecordId\":\"record\"}}}";
    }
}
