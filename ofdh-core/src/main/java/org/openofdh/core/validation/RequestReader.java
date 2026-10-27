package org.openofdh.core.validation;

import com.fasterxml.jackson.databind.JsonNode;

import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.DocumentRequest.ClinicalData;
import org.openofdh.core.api.DocumentRequest.Code;
import org.openofdh.core.api.DocumentRequest.Identity;
import org.openofdh.core.api.DocumentRequest.ResponsibleParty;
import org.openofdh.core.api.DocumentRequest.Source;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.internal.JsonIO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RequestReader {
    private static final long MAX_REQUEST_BYTES = 1L << 20;

    private RequestReader() {}

    private static final class Contract {
        private static final LocalSchema SCHEMA = load();

        private static LocalSchema load() {
            try (InputStream input =
                    RequestReader.class.getResourceAsStream(
                            "/org/openofdh/contracts/document.schema.json")) {
                if (input == null) {
                    throw new IllegalStateException("类库中缺少文档请求契约");
                }
                return new LocalSchema(JsonIO.read(input, MAX_REQUEST_BYTES));
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    public static DocumentRequest read(Path requestFile) throws IOException {
        JsonNode node = JsonIO.read(requestFile, MAX_REQUEST_BYTES);
        ValidationReport report = inspectNode(node);
        if (!report.isValid()) {
            throw new OfdhException("REQUEST_INVALID", "文档请求校验失败，请按问题列表修正输入", null, report);
        }
        List<String> keywords = new ArrayList<>();
        node.get("keywords").forEach(value -> keywords.add(value.asText()));
        JsonNode party = node.get("responsibleParty");
        JsonNode source = node.get("source");
        JsonNode clinical = node.get("clinicalData");
        return new DocumentRequest(
                text(node, "contractVersion"),
                text(node, "documentId"),
                text(node, "sourceSystem"),
                text(node, "sourceRecordId"),
                text(node, "sourceVersion"),
                code(node.get("documentType")),
                identity(node.get("patient")),
                identity(node.get("encounter")),
                new ResponsibleParty(
                        text(party, "id"), text(party, "name"), text(party, "authority")),
                text(node, "createdAt"),
                keywords,
                code(node.get("confidentiality")),
                new Source(text(source, "format"), text(source, "path"), text(source, "sha256")),
                clinical == null
                        ? null
                        : new ClinicalData(
                                text(clinical, "path"),
                                text(clinical, "schemaId"),
                                text(clinical, "schemaVersion")));
    }

    public static void validate(DocumentRequest request) throws OfdhException {
        ValidationReport report = inspect(request);
        if (!report.isValid()) {
            throw new OfdhException("REQUEST_INVALID", "文档请求校验失败，请按问题列表修正输入", null, report);
        }
    }

    public static ValidationReport inspect(DocumentRequest request) throws OfdhException {
        return inspectNode(JsonIO.tree(request));
    }

    public static ValidationReport inspect(Path requestFile) throws IOException {
        return inspectNode(JsonIO.read(requestFile, MAX_REQUEST_BYTES));
    }

    private static ValidationReport inspectNode(JsonNode node) throws OfdhException {
        ValidationReport report = Contract.SCHEMA.inspect(node, "REQUEST_INVALID");
        List<ValidationIssue> characterIssues = new ArrayList<>(report.getIssues());
        inspectXmlCharacters(node, "", characterIssues);
        report = new ValidationReport(characterIssues, report.isTruncated());
        JsonNode createdAt = node.path("createdAt");
        if (!createdAt.isTextual()
                || report.getIssues().stream()
                        .anyMatch(issue -> "/createdAt".equals(issue.getPath()))) {
            return report;
        }
        try {
            OffsetDateTime.parse(createdAt.asText());
            return report;
        } catch (DateTimeParseException failure) {
            List<ValidationIssue> issues = new ArrayList<>(report.getIssues());
            issues.add(
                    new ValidationIssue(
                            "/createdAt",
                            "format",
                            "创建时间超出当前引擎支持范围",
                            "可由 OffsetDateTime 表示的带时区时间，秒值为 00～59",
                            "string"));
            return new ValidationReport(issues, report.isTruncated());
        }
    }

    private static void inspectXmlCharacters(
            JsonNode node, String path, List<ValidationIssue> issues) {
        // 多收集一条即可确认截断，避免为大数组创建不会返回给调用方的问题对象。
        if (issues.size() > ValidationReport.MAX_ISSUES) return;
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (issues.size() > ValidationReport.MAX_ISSUES) return;
                inspectXmlCharacters(
                        field.getValue(), ValidationIssues.append(path, field.getKey()), issues);
            }
        } else if (node.isArray()) {
            for (int index = 0;
                    index < node.size() && issues.size() <= ValidationReport.MAX_ISSUES;
                    index++) {
                inspectXmlCharacters(node.get(index), path + "/" + index, issues);
            }
        } else if (node.isTextual()) {
            String value = node.asText();
            for (int offset = 0; offset < value.length(); ) {
                int point = value.codePointAt(offset);
                // 请求文本将写入 OFD 元数据；JSON 可表示而 XML 1.0 禁止的字符需在字段校验时拒绝。
                if (!(point == 0x9
                        || point == 0xa
                        || point == 0xd
                        || (point >= 0x20 && point <= 0xd7ff)
                        || (point >= 0xe000 && point <= 0xfffd)
                        || (point >= 0x10000 && point <= 0x10ffff))) {
                    issues.add(
                            new ValidationIssue(
                                    path,
                                    "xmlCharacter",
                                    "字段含有 OFD 元数据无法表示的字符",
                                    "使用 XML 1.0 允许的字符，代理项必须成对",
                                    "string"));
                    return;
                }
                offset += Character.charCount(point);
            }
        }
    }

    private static Code code(JsonNode node) {
        return new Code(text(node, "code"), text(node, "scheme"));
    }

    private static Identity identity(JsonNode node) {
        return node == null ? null : new Identity(text(node, "id"), text(node, "authority"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null ? null : value.asText();
    }
}
