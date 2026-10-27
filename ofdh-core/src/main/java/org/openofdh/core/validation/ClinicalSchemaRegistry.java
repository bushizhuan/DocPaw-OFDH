package org.openofdh.core.validation;

import com.fasterxml.jackson.databind.JsonNode;

import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.internal.JsonIO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 按标识和版本显式注册临床 Schema；所有引用均在本地校验。 */
public final class ClinicalSchemaRegistry {
    private final ConcurrentMap<Key, LocalSchema> schemas = new ConcurrentHashMap<>();
    private final long maxBytes;

    public ClinicalSchemaRegistry() {
        this(ResourceLimits.defaults().getMaxClinicalBytes());
    }

    public ClinicalSchemaRegistry(long maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("临床数据字节上限必须为正数");
        }
        this.maxBytes = maxBytes;
    }

    public ClinicalSchemaRegistry register(String schemaId, String version, Path definition)
            throws IOException {
        try (InputStream input = Files.newInputStream(definition)) {
            return register(schemaId, version, input);
        }
    }

    /** 输入流由调用方关闭，适用于类路径资源和宿主管理的本地流。 */
    public ClinicalSchemaRegistry register(String schemaId, String version, InputStream definition)
            throws IOException {
        Key key = new Key(schemaId, version);
        LocalSchema schema = new LocalSchema(JsonIO.read(definition, 1L << 20));
        if (schemas.putIfAbsent(key, schema) != null) {
            throw new OfdhException("SCHEMA_ALREADY_REGISTERED", "该标识和版本的 Schema 已注册");
        }
        return this;
    }

    public void validate(DocumentRequest request, Path clinicalFile) throws IOException {
        Evaluation evaluation = evaluate(request, clinicalFile);
        if (!evaluation.report.isValid()) {
            throw new OfdhException(
                    evaluation.code, "临床数据校验失败，请按问题列表修正输入", null, evaluation.report);
        }
    }

    public ValidationReport inspect(DocumentRequest request, Path clinicalFile) throws IOException {
        return evaluate(request, clinicalFile).report;
    }

    private Evaluation evaluate(DocumentRequest request, Path clinicalFile) throws IOException {
        ValidationReport requestReport = RequestReader.inspect(request);
        if (!requestReport.isValid()) return new Evaluation("REQUEST_INVALID", requestReport);
        DocumentRequest.ClinicalData reference = request.getClinicalData();
        if (reference == null) {
            return failure(
                    "REQUEST_INVALID",
                    "/clinicalData",
                    "required",
                    "缺少临床数据引用",
                    "提供临床数据文件及规范标识和版本",
                    "missing");
        }
        LocalSchema schema =
                schemas.get(new Key(reference.getSchemaId(), reference.getSchemaVersion()));
        if (schema == null) {
            return failure(
                    "CLINICAL_SCHEMA_UNKNOWN",
                    "/clinicalData/schemaId",
                    "registeredSchema",
                    "临床数据规范尚未注册",
                    "先注册此标识和版本对应的本地 Schema",
                    "string");
        }
        JsonNode data = JsonIO.read(clinicalFile, maxBytes);
        ValidationReport structure = schema.inspect(data, "CLINICAL_DATA_INVALID");
        List<ValidationIssue> issues = new ArrayList<>(structure.getIssues());
        String profile = data.at("/head/profile").asText("");
        if (!ClinicalEnvelopeProfile.ID.equals(profile)) {
            if (profile.startsWith("urn:openofdh:clinical-envelope:")) {
                add(
                        issues,
                        data,
                        "/head/profile",
                        "supportedProfile",
                        "临床数据封套版本暂不支持",
                        "使用当前支持的 DocPaw-OFDH 封套版本");
                return new Evaluation(
                        structure.isValid()
                                ? "CLINICAL_PROFILE_UNSUPPORTED"
                                : "CLINICAL_DATA_INVALID",
                        new ValidationReport(issues, structure.isTruncated()));
            }
            return new Evaluation("CLINICAL_DATA_INVALID", structure);
        }
        int structureCount = issues.size();
        validateEnvelope(data, issues);
        int envelopeCount = issues.size();
        compare(data, request.getDocumentId(), "/identifier/documentId", issues);
        compare(data, request.getSourceVersion(), "/head/documentVersion", issues);
        compare(data, reference.getSchemaId(), "/head/schemaId", issues);
        compare(data, reference.getSchemaVersion(), "/head/version", issues);
        OffsetDateTime created = timestamp(data, "/head/createdAt", issues);
        if (created != null
                && !created.toInstant()
                        .equals(OffsetDateTime.parse(request.getCreatedAt()).toInstant())) {
            add(
                    issues,
                    data,
                    "/head/createdAt",
                    "sourceConsistency",
                    "创建时间与来源文书不一致",
                    "与请求 createdAt 表示同一时间点");
        }
        compare(data, request.getPatient().getId(), "/patient/id", issues);
        compare(data, request.getPatient().getAuthority(), "/patient/authority", issues);
        compare(data, request.getSourceRecordId(), "/meta/properties/sourceRecordId", issues);
        compare(data, request.getSourceSystem(), "/meta/properties/sourceSystem", issues);
        compare(data, request.getDocumentType().getCode(), "/identifier/documentType/code", issues);
        compare(
                data,
                request.getDocumentType().getScheme(),
                "/identifier/documentType/scheme",
                issues);
        compare(
                data,
                request.getConfidentiality().getCode(),
                "/identifier/confidentiality/code",
                issues);
        compare(
                data,
                request.getConfidentiality().getScheme(),
                "/identifier/confidentiality/scheme",
                issues);
        compare(data, request.getResponsibleParty().getId(), "/meta/creator/id", issues);
        compare(data, request.getResponsibleParty().getName(), "/meta/creator/name", issues);
        compare(
                data,
                request.getResponsibleParty().getAuthority(),
                "/meta/creator/authority",
                issues);
        JsonNode keywords = data.at("/meta/keywords");
        if (validTextArray(keywords)) {
            Set<String> actual = new HashSet<>();
            keywords.forEach(value -> actual.add(value.asText()));
            if (!actual.equals(new HashSet<>(request.getKeywords()))) {
                add(
                        issues,
                        data,
                        "/meta/keywords",
                        "sourceConsistency",
                        "关键词与来源文书不一致",
                        "与请求 keywords 包含相同的关键词");
            }
        }
        if (request.getEncounter() != null) {
            compare(data, request.getEncounter().getId(), "/encounter/id", issues);
            compare(data, request.getEncounter().getAuthority(), "/encounter/authority", issues);
        }
        String code =
                !structure.isValid()
                        ? "CLINICAL_DATA_INVALID"
                        : envelopeCount > structureCount
                                ? "CLINICAL_ENVELOPE_INVALID"
                                : "SOURCE_VERSION_MISMATCH";
        return new Evaluation(code, new ValidationReport(issues, structure.isTruncated()));
    }

    private static void validateEnvelope(JsonNode data, List<ValidationIssue> issues) {
        String[] objects = {
            "/head",
            "/identifier",
            "/identifier/documentType",
            "/identifier/confidentiality",
            "/patient",
            "/meta",
            "/meta/creator",
            "/meta/properties"
        };
        for (String path : objects) {
            if (blocked(issues, path)) continue;
            JsonNode value = data.at(path);
            if (!value.isObject()) {
                add(
                        issues,
                        data,
                        path,
                        value.isMissingNode() ? "required" : "type",
                        "封套字段必须是对象",
                        "对象");
            }
        }
        for (String path : ClinicalEnvelopeProfile.requiredTextFields()) {
            if (blocked(issues, path)) continue;
            JsonNode value = data.at(path);
            if (value.isMissingNode()) {
                add(issues, data, path, "required", "缺少封套必填字段", "非空字符串");
            } else if (!value.isTextual()) {
                add(issues, data, path, "type", "封套字段类型不符合要求", "字符串");
            } else if (value.asText().trim().isEmpty()) {
                add(issues, data, path, "pattern", "封套字段不能为空白", "至少包含一个非空白字符");
            }
        }
        if (!blocked(issues, "/meta/keywords")) {
            JsonNode keywords = data.at("/meta/keywords");
            if (!keywords.isArray()) {
                add(
                        issues,
                        data,
                        "/meta/keywords",
                        keywords.isMissingNode() ? "required" : "type",
                        "封套关键词必须是数组",
                        "非空字符串数组");
            } else if (keywords.isEmpty()) {
                add(issues, data, "/meta/keywords", "minItems", "封套关键词不能为空", "至少一个关键词");
            } else {
                for (int index = 0; index < keywords.size(); index++) {
                    JsonNode value = keywords.get(index);
                    if (!value.isTextual() || value.asText().trim().isEmpty()) {
                        add(
                                issues,
                                data,
                                "/meta/keywords/" + index,
                                value.isTextual() ? "pattern" : "type",
                                "关键词必须是非空字符串",
                                "非空字符串");
                    }
                }
            }
        }
        OffsetDateTime created = timestamp(data, "/head/createdAt", issues);
        OffsetDateTime modified = timestamp(data, "/head/modifiedAt", issues);
        if (created != null
                && modified != null
                && modified.toInstant().isBefore(created.toInstant())) {
            add(
                    issues,
                    data,
                    "/head/modifiedAt",
                    "chronology",
                    "修改时间早于创建时间",
                    "修改时间不得早于 head.createdAt");
        }
    }

    private static OffsetDateTime timestamp(
            JsonNode data, String path, List<ValidationIssue> issues) {
        if (blocked(issues, path)) return null;
        JsonNode value = data.at(path);
        if (!value.isTextual() || value.asText().trim().isEmpty()) return null;
        try {
            return OffsetDateTime.parse(value.asText());
        } catch (DateTimeParseException failure) {
            add(
                    issues,
                    data,
                    path,
                    "format",
                    "时间格式或日期值不符合要求",
                    "有效日期和时间，包含时区，例如 2026-01-01T09:30:00+08:00");
            return null;
        }
    }

    private static void compare(
            JsonNode data, String expected, String path, List<ValidationIssue> issues) {
        if (blocked(issues, path)) return;
        JsonNode value = data.at(path);
        if (value.isMissingNode()) return;
        if (!value.isTextual()) {
            add(issues, data, path, "type", "来源标识字段必须是字符串", "字符串");
        } else if (value.asText().trim().isEmpty()) {
            add(issues, data, path, "pattern", "来源标识字段不能为空白", "至少包含一个非空白字符");
        } else if (!value.asText().equals(expected)) {
            add(issues, data, path, "sourceConsistency", "字段与来源文书不一致", "与请求中对应的来源字段一致");
        }
    }

    private static boolean validTextArray(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) return false;
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().trim().isEmpty()) return false;
        }
        return true;
    }

    private static boolean blocked(List<ValidationIssue> issues, String path) {
        for (ValidationIssue issue : issues) {
            if (("required".equals(issue.getKeyword()) || "type".equals(issue.getKeyword()))
                    && (issue.getPath().equals(path) || path.startsWith(issue.getPath() + "/")))
                return true;
        }
        return false;
    }

    private static void add(
            List<ValidationIssue> issues,
            JsonNode data,
            String path,
            String keyword,
            String message,
            String expected) {
        for (ValidationIssue issue : issues) {
            if (path.equals(issue.getPath()) && keyword.equals(issue.getKeyword())) return;
        }
        issues.add(
                new ValidationIssue(
                        path, keyword, message, expected, ValidationIssues.typeOf(data.at(path))));
    }

    private static Evaluation failure(
            String code,
            String path,
            String keyword,
            String message,
            String expected,
            String actualType) {
        return new Evaluation(
                code,
                new ValidationReport(
                        Collections.singletonList(
                                new ValidationIssue(path, keyword, message, expected, actualType)),
                        false));
    }

    private static final class Evaluation {
        private final String code;
        private final ValidationReport report;

        private Evaluation(String code, ValidationReport report) {
            this.code = code;
            this.report = report;
        }
    }

    private static final class Key {
        private final String id;
        private final String version;

        private Key(String id, String version) {
            this.id = Objects.requireNonNull(id, "Schema 标识不能为空");
            this.version = Objects.requireNonNull(version, "Schema 版本不能为空");
            if (id.trim().isEmpty() || version.trim().isEmpty()) {
                throw new IllegalArgumentException("Schema 标识和版本不得为空白");
            }
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return id.equals(other.id) && version.equals(other.version);
        }

        @Override
        public int hashCode() {
            return 31 * id.hashCode() + version.hashCode();
        }
    }
}
