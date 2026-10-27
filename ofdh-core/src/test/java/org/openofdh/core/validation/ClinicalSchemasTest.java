package org.openofdh.core.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.internal.JsonIO;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

class ClinicalSchemasTest {
    @TempDir Path directory;

    @Test
    void catalogResourcesAreSelfContainedAndUseIdenticalCommonDefinitions() throws Exception {
        JsonNode common = schema(ClinicalSchemas.ENVELOPE).path("$defs");
        String[] definitions = {
            "nonBlank",
            "timestamp",
            "identity",
            "person",
            "codedConcept",
            "patient",
            "meta",
            "identifier",
            "extensions"
        };
        ClinicalSchemaRegistry registry = new ClinicalSchemaRegistry();
        for (ClinicalSchemas entry : ClinicalSchemas.values()) {
            JsonNode definition = schema(entry);
            assertEquals(entry.getId() + ":" + entry.getVersion(), definition.path("$id").asText());
            assertEquals(entry.getTitle(), definition.path("title").asText());
            assertLocalReferences(definition);
            for (String name : definitions) {
                assertEquals(common.path(name), definition.path("$defs").path(name), name);
            }
            entry.register(registry);
            registry.validate(request(entry), save(payload(entry)));
            assertEquals(entry, ClinicalSchemas.find(entry.getId(), entry.getVersion()).get());
        }
        assertFalse(ClinicalSchemas.find(ClinicalSchemas.ENVELOPE.getId(), "99").isPresent());
        assertFalse(ClinicalSchemas.find(null, null).isPresent());
    }

    @Test
    void genericEnvelopeAllowsCallerContentAndExplicitExtensions() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.ENVELOPE);
        data.withObject("/content").putObject("localObservation").put("value", 37.2);
        data.putObject("extensions").putObject("urn:test:extension").put("revision", 2);
        assertValid(ClinicalSchemas.ENVELOPE, data);
        data.withObject("/content").removeAll();
        assertInvalid(ClinicalSchemas.ENVELOPE, data, "CLINICAL_DATA_INVALID");
    }

    @Test
    void admissionRequiresEncounterNarrativeAndDiagnosisText() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.ADMISSION_RECORD);
        data.withObject("/content").remove("chiefComplaint");
        data.withObject("/content/diagnoses/0").put("text", "   ");
        data.remove("encounter");
        ValidationReport result = inspect(ClinicalSchemas.ADMISSION_RECORD, data);
        assertFalse(result.isValid());
        assertTrue(
                result.getIssues().stream()
                        .anyMatch(issue -> issue.getPath().equals("/encounter")));
        assertTrue(
                result.getIssues().stream()
                        .anyMatch(issue -> issue.getPath().equals("/content/chiefComplaint")));
        assertTrue(
                result.getIssues().stream()
                        .anyMatch(issue -> issue.getPath().equals("/content/diagnoses/0/text")));
    }

    @Test
    void admissionAcceptsUncodedDiagnosisButRejectsHalfSpecifiedCoding() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.ADMISSION_RECORD);
        assertValid(ClinicalSchemas.ADMISSION_RECORD, data);
        data.withObject("/content/diagnoses/0").putObject("coding").put("code", "local-diagnosis");
        assertInvalid(ClinicalSchemas.ADMISSION_RECORD, data, "CLINICAL_DATA_INVALID");
        data.withObject("/content/diagnoses/0/coding").put("scheme", "urn:test:diagnosis");
        assertValid(ClinicalSchemas.ADMISSION_RECORD, data);
    }

    @Test
    void laboratoryResultsPreserveNumericTextAndCodedTypes() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.LAB_REPORT);
        ArrayNode items = (ArrayNode) data.at("/content/items");
        ObjectNode textItem = items.addObject();
        textItem.set("test", code("qualitative"));
        textItem.putObject("result").put("kind", "TEXT").put("value", "阴性");
        ObjectNode codedItem = items.addObject();
        codedItem.set("test", code("coded"));
        ObjectNode codedResult = codedItem.putObject("result").put("kind", "CODED");
        codedResult.set("value", code("negative"));
        assertValid(ClinicalSchemas.LAB_REPORT, data);

        ObjectNode numericResult = data.withObject("/content/items/0/result");
        numericResult.put("value", "12.5");
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "CLINICAL_DATA_INVALID");
        ValidationReport numericFailure = inspect(ClinicalSchemas.LAB_REPORT, data);
        assertEquals(1, numericFailure.getIssues().size());
        assertEquals("/content/items/0/result/value", numericFailure.getIssues().get(0).getPath());
        assertEquals("type", numericFailure.getIssues().get(0).getKeyword());
        numericResult.put("value", 12.5);
        textItem.withObject("/result").put("value", 12);
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "CLINICAL_DATA_INVALID");
        textItem.withObject("/result").put("value", "阴性");
        codedResult.withObject("/value").remove("scheme");
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "CLINICAL_DATA_INVALID");
    }

    @Test
    void resultFieldsRemainSpecificToTheirDeclaredKind() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.LAB_REPORT);
        ObjectNode result = data.withObject("/content/items/0/result");
        result.put("kind", "TEXT").put("value", "阴性");
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "CLINICAL_DATA_INVALID");
        result.remove("unit");
        assertValid(ClinicalSchemas.LAB_REPORT, data);
        result.put("comparator", "<");
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "CLINICAL_DATA_INVALID");
        result.remove("comparator");
        result.put("kind", "CODED");
        result.set("value", code("negative"));
        assertValid(ClinicalSchemas.LAB_REPORT, data);
        result.put("unit", "g/L");
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "CLINICAL_DATA_INVALID");
        result.remove("unit");
        result.remove("kind");
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "CLINICAL_DATA_INVALID");
    }

    @Test
    void laboratoryRejectsUnknownResultKindAndFlag() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.LAB_REPORT);
        data.withObject("/content/items/0/result").put("kind", "UNDECLARED");
        data.withObject("/content/items/0").put("abnormalFlag", "UNDECLARED");
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "CLINICAL_DATA_INVALID");
        data.withObject("/content/items/0/result").put("kind", "NUMBER");
        data.withObject("/content/items/0").put("abnormalFlag", "HIGH");
        assertValid(ClinicalSchemas.LAB_REPORT, data);
    }

    @Test
    void datesRequireRealCalendarValuesAndExplicitTimezone() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.ADMISSION_RECORD);
        data.withObject("/content").put("admittedAt", "2026-10-26 09:00:00");
        assertInvalid(ClinicalSchemas.ADMISSION_RECORD, data, "CLINICAL_DATA_INVALID");
        data.withObject("/content").put("admittedAt", "2026-02-30T09:00:00+08:00");
        assertInvalid(ClinicalSchemas.ADMISSION_RECORD, data, "CLINICAL_DATA_INVALID");
        data.withObject("/content").put("admittedAt", "2026-10-26T01:00:00Z");
        data.withObject("/patient").put("birthDate", "2001-02-29");
        assertInvalid(ClinicalSchemas.ADMISSION_RECORD, data, "CLINICAL_DATA_INVALID");
        data.withObject("/patient").put("birthDate", "2000-02-29");
        assertValid(ClinicalSchemas.ADMISSION_RECORD, data);
    }

    @Test
    void builtinIdentityCannotSilentlySelectAnotherProfileOrVersion() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.ENVELOPE);
        data.withObject("/head").put("schemaId", ClinicalSchemas.ADMISSION_RECORD.getId());
        assertInvalid(ClinicalSchemas.ENVELOPE, data, "CLINICAL_DATA_INVALID");
        data.withObject("/head").put("schemaId", ClinicalSchemas.ENVELOPE.getId());
        data.withObject("/head").put("version", "99");
        assertInvalid(ClinicalSchemas.ENVELOPE, data, "CLINICAL_DATA_INVALID");
        data.withObject("/head").put("version", "0.1.0");
        data.withObject("/head").remove("profile");
        assertInvalid(ClinicalSchemas.ENVELOPE, data, "CLINICAL_DATA_INVALID");
    }

    @Test
    void builtinPayloadIsCheckedAgainstItsRequestIdentity() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.LAB_REPORT);
        data.withObject("/patient").put("id", "other-subject");
        data.withObject("/head").put("documentVersion", "8");
        ValidationReport report = inspect(ClinicalSchemas.LAB_REPORT, data);
        assertFalse(report.isValid());
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(issue -> issue.getPath().equals("/patient/id")));
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(issue -> issue.getPath().equals("/head/documentVersion")));
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "SOURCE_VERSION_MISMATCH");
    }

    @Test
    void misspelledFieldsFailWhileExplicitExtensionsRemainAvailable() throws Exception {
        ObjectNode data = payload(ClinicalSchemas.LAB_REPORT);
        data.withObject("/content").put("reportID", "report");
        assertInvalid(ClinicalSchemas.LAB_REPORT, data, "CLINICAL_DATA_INVALID");
        data.withObject("/content").remove("reportID");
        data.withObject("/content").putObject("extensions").put("localReportStatus", "final");
        assertValid(ClinicalSchemas.LAB_REPORT, data);
    }

    @Test
    void exportedSchemaCanBeRegisteredWithoutOtherResources() throws Exception {
        for (ClinicalSchemas entry : ClinicalSchemas.values()) {
            Path exported = directory.resolve(entry.name() + ".schema.json");
            try (InputStream input = entry.openSchema()) {
                Files.copy(input, exported);
            }
            ClinicalSchemaRegistry registry = new ClinicalSchemaRegistry();
            registry.register(entry.getId(), entry.getVersion(), exported);
            registry.validate(request(entry), save(payload(entry)));
        }
    }

    private ValidationReport inspect(ClinicalSchemas entry, ObjectNode data) throws Exception {
        ClinicalSchemaRegistry registry =
                ClinicalSchemas.registerDefaults(new ClinicalSchemaRegistry());
        return registry.inspect(request(entry), save(data));
    }

    private void assertValid(ClinicalSchemas entry, ObjectNode data) throws Exception {
        ClinicalSchemaRegistry registry =
                ClinicalSchemas.registerDefaults(new ClinicalSchemaRegistry());
        registry.validate(request(entry), save(data));
    }

    private void assertInvalid(ClinicalSchemas entry, ObjectNode data, String code)
            throws Exception {
        ClinicalSchemaRegistry registry =
                ClinicalSchemas.registerDefaults(new ClinicalSchemaRegistry());
        Path file = save(data);
        OfdhException failure =
                assertThrows(OfdhException.class, () -> registry.validate(request(entry), file));
        assertEquals(code, failure.getCode());
        assertFalse(failure.getIssues().isEmpty());
    }

    private Path save(ObjectNode data) throws Exception {
        Path file = Files.createTempFile(directory, "clinical-", ".json");
        Files.write(file, JsonIO.bytes(data));
        return file;
    }

    private JsonNode schema(ClinicalSchemas entry) throws Exception {
        try (InputStream input = entry.openSchema()) {
            return JsonIO.read(input, 1L << 20);
        }
    }

    private void assertLocalReferences(JsonNode node) {
        if (node.isObject()) {
            node.properties()
                    .forEach(
                            field -> {
                                if (field.getKey().equals("$ref")
                                        || field.getKey().equals("$dynamicRef")) {
                                    assertTrue(field.getValue().asText().startsWith("#"));
                                }
                                assertLocalReferences(field.getValue());
                            });
        } else if (node.isArray()) {
            node.forEach(this::assertLocalReferences);
        }
    }

    private DocumentRequest request(ClinicalSchemas entry) {
        return new DocumentRequest(
                "0.1.0",
                "document",
                "source",
                "record",
                "7",
                new DocumentRequest.Code("record", "urn:test:type"),
                new DocumentRequest.Identity("subject", "urn:test:subject"),
                new DocumentRequest.Identity("visit", "urn:test:visit"),
                new DocumentRequest.ResponsibleParty("author", "测试责任者", "urn:test:author"),
                "2026-10-26T10:00:00+08:00",
                Collections.singletonList("文书"),
                new DocumentRequest.Code("internal", "urn:test:confidentiality"),
                new DocumentRequest.Source("OFD", "source.ofd", null),
                new DocumentRequest.ClinicalData(
                        "clinical.json", entry.getId(), entry.getVersion()));
    }

    private ObjectNode payload(ClinicalSchemas entry) {
        ObjectNode data = object();
        data.putObject("head")
                .put("profile", ClinicalEnvelopeProfile.ID)
                .put("schemaId", entry.getId())
                .put("version", entry.getVersion())
                .put("documentVersion", "7")
                .put("createdAt", "2026-10-26T02:00:00Z")
                .put("modifiedAt", "2026-10-26T02:01:00Z");
        ObjectNode meta = data.putObject("meta");
        meta.putObject("creator")
                .put("id", "author")
                .put("name", "测试责任者")
                .put("authority", "urn:test:author");
        meta.putArray("keywords").add("文书");
        meta.putObject("properties").put("sourceSystem", "source").put("sourceRecordId", "record");
        ObjectNode identifier = data.putObject("identifier").put("documentId", "document");
        identifier.putObject("documentType").put("code", "record").put("scheme", "urn:test:type");
        identifier
                .putObject("confidentiality")
                .put("code", "internal")
                .put("scheme", "urn:test:confidentiality");
        data.putObject("patient").put("id", "subject").put("authority", "urn:test:subject");
        data.putObject("encounter").put("id", "visit").put("authority", "urn:test:visit");
        ObjectNode content = data.putObject("content");
        if (entry == ClinicalSchemas.ENVELOPE) {
            content.put("note", "用于验证封装约束的文本");
        } else if (entry == ClinicalSchemas.ADMISSION_RECORD) {
            content.put("admittedAt", "2026-10-26T09:00:00+08:00")
                    .put("chiefComplaint", "用于验证主诉字段的文本")
                    .put("presentIllness", "用于验证现病史字段的文本");
            content.putArray("diagnoses").addObject().put("text", "用于验证诊断结构的文本");
        } else {
            content.put("reportId", "report").put("reportedAt", "2026-10-26T10:00:00+08:00");
            content.putObject("specimen").put("id", "specimen");
            ObjectNode item = content.putArray("items").addObject();
            item.set("test", code("quantitative"));
            item.putObject("result").put("kind", "NUMBER").put("value", 12.5).put("unit", "g/L");
        }
        return data;
    }

    private ObjectNode code(String value) {
        return object().put("code", value).put("scheme", "urn:test:code");
    }

    private ObjectNode object() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }
}
