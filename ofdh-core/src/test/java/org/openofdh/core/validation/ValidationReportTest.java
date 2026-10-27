package org.openofdh.core.validation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.internal.JsonIO;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class ValidationReportTest {
    @Test
    void escapesPropertyNamesAndLocatesArrayElements() throws Exception {
        LocalSchema schema =
                schema(
                        "{\"type\":\"object\",\"required\":[\"a/b~c\"],"
                            + "\"additionalProperties\":false,\"properties\":{\"items\":{\"type\":\"array\","
                            + "\"items\":{\"type\":\"integer\"}}}}");
        ValidationReport report =
                schema.inspect(json("{\"items\":[\"hidden\"],\"x/y~z\":null}"), "INVALID");
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/a~1b~0c")
                                                && issue.getKeyword().equals("required")
                                                && issue.getActualType().equals("missing")));
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/x~1y~0z")
                                                && issue.getKeyword().equals("additionalProperties")
                                                && issue.getActualType().equals("null")));
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/items/0")
                                                && issue.getKeyword().equals("type")
                                                && issue.getExpected().equals("整数")));
    }

    @Test
    void boundsAndSortsUniqueIssuesIndependentlyOfInputOrder() {
        List<ValidationIssue> issues = new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            issues.add(new ValidationIssue("/field" + index, "type", "类型错误", "字符串", "integer"));
        }
        issues.add(issues.get(0));
        ValidationReport first = new ValidationReport(issues, false);
        Collections.reverse(issues);
        ValidationReport second = new ValidationReport(issues, false);
        assertEquals(100, first.getIssues().size());
        assertTrue(first.isTruncated());
        assertFalse(first.isValid());
        for (int index = 0; index < first.getIssues().size(); index++) {
            assertEquals(
                    first.getIssues().get(index).getPath(),
                    second.getIssues().get(index).getPath());
        }
        ValidationReport duplicate =
                new ValidationReport(Collections.nCopies(110, issues.get(0)), false);
        assertEquals(1, duplicate.getIssues().size());
        assertFalse(duplicate.isTruncated());
    }

    @Test
    void capsRealSchemaFailuresAndKeepsInputAndSchemaValuesOutOfMessages() throws Exception {
        LocalSchema schema =
                schema("{\"type\":\"array\",\"items\":{\"const\":\"schema-private-value\"}}");
        com.fasterxml.jackson.databind.node.ArrayNode input =
                JsonIO.tree(Collections.emptyList()).deepCopy();
        for (int index = 0; index < 125; index++) input.add("private-input-value");
        ValidationReport report = schema.inspect(input, "INVALID");
        assertEquals(100, report.getIssues().size());
        assertTrue(report.isTruncated());
        assertFalse(JsonIO.string(report).contains("private-value"));
        assertFalse(JsonIO.string(report).contains("private-input-value"));
        OfdhException failure =
                assertThrows(OfdhException.class, () -> schema.validate(input, "INVALID"));
        assertTrue(failure.isTruncated());
        assertEquals(100, failure.getIssues().size());
    }

    @Test
    void validatesRealDatesAndReturnsChineseExpectedFormat() throws Exception {
        LocalSchema schema = schema("{\"type\":\"string\",\"format\":\"date-time\"}");
        ValidationReport report = schema.inspect(json("\"2026-02-30T09:30:00+08:00\""), "INVALID");
        assertFalse(report.isValid());
        assertEquals("", report.getIssues().get(0).getPath());
        assertTrue(report.getIssues().get(0).getExpected().contains("时区"));
        assertTrue(schema.inspect(json("\"2026-10-26T09:30:00+08:00\""), "INVALID").isValid());
    }

    @Test
    void locatesDependentRequiredAndPropertyNameFailures() throws Exception {
        LocalSchema schema =
                schema(
                        "{\"type\":\"object\",\"dependentRequired\":{\"a\":[\"b\",\"c/d\"]},"
                                + "\"propertyNames\":{\"pattern\":\"^[a-z/]+$\"}}");
        ValidationReport report = schema.inspect(json("{\"a\":1,\"NAME\":42}"), "INVALID");
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/b")
                                                && issue.getKeyword().equals("dependentRequired")
                                                && issue.getActualType().equals("missing")));
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/c~1d")
                                                && issue.getKeyword().equals("dependentRequired")));
        assertTrue(
                report.getIssues().stream()
                        .anyMatch(
                                issue ->
                                        issue.getPath().equals("/NAME")
                                                && issue.getKeyword().equals("propertyNames")
                                                && issue.getActualType().equals("string")));
        assertFalse(report.getIssues().stream().anyMatch(issue -> issue.getPath().equals("/a")));
    }

    @Test
    void doesNotReportRequirementsFromRejectedAlternativeBranches() throws Exception {
        for (String keyword : new String[] {"oneOf", "anyOf"}) {
            LocalSchema schema =
                    schema(
                            "{\"type\":\"object\",\"properties\":{\"result\":{\""
                                    + keyword
                                    + "\":[{\"properties\":{\"kind\":{\"const\":\"a\"},\"a\":{\"type\":\"integer\"}},\"required\":[\"a\"]},"
                                    + "{\"properties\":{\"kind\":{\"const\":\"b\"},\"b\":{\"type\":\"string\"}},\"required\":[\"b\"]}]}}}");
            ValidationReport report =
                    schema.inspect(
                            json("{\"result\":{\"kind\":\"a\",\"a\":\"wrong\"}}"), "INVALID");
            assertEquals(1, report.getIssues().size());
            assertEquals("/result", report.getIssues().get(0).getPath());
            assertEquals(keyword, report.getIssues().get(0).getKeyword());
        }
    }

    @Test
    void keepsExistingNullCauseConstructorUnambiguous() {
        OfdhException failure = new OfdhException("INVALID", "输入错误", null);
        assertNull(failure.getCause());
        assertTrue(failure.getIssues().isEmpty());
    }

    @Test
    void locatesAlternativeFailuresThroughReferencesAndArrayItems() throws Exception {
        LocalSchema schema =
                schema(
                        "{\"type\":\"object\",\"properties\":{\"a/b\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/$defs/result\"}}},"
                            + "\"$defs\":{\"result\":{\"type\":\"object\",\"anyOf\":["
                            + "{\"properties\":{\"value\":{\"type\":\"integer\"}}},"
                            + "{\"properties\":{\"value\":{\"type\":\"boolean\"}}}]}}}");
        ValidationReport report =
                schema.inspect(json("{\"a/b\":[{\"value\":\"wrong\"}]}"), "INVALID");
        assertEquals(1, report.getIssues().size());
        assertEquals("/a~1b/0", report.getIssues().get(0).getPath());
        assertEquals("anyOf", report.getIssues().get(0).getKeyword());
        assertTrue(schema.inspect(json("{\"a/b\":[{\"value\":1}]}"), "INVALID").isValid());
        ValidationReport multiple =
                schema.inspect(
                        json(
                                "{\"a/b\":[{\"value\":\"wrong\"},{\"value\":1},{\"value\":\"wrong\"}]}"),
                        "INVALID");
        assertEquals(2, multiple.getIssues().size());
        assertEquals("/a~1b/0", multiple.getIssues().get(0).getPath());
        assertEquals("/a~1b/2", multiple.getIssues().get(1).getPath());
    }

    private static LocalSchema schema(String body) throws Exception {
        return new LocalSchema(
                json(
                        "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\","
                                + body.substring(1)));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String value) throws Exception {
        return JsonIO.parse(value.getBytes(StandardCharsets.UTF_8));
    }
}
