package org.openofdh.core.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.path.NodePath;

import org.openofdh.core.api.OfdhException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅编译自包含的本地 Schema，不解析外部文件或网络引用。 */
final class LocalSchema {
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> META_IDS =
            new HashSet<>(
                    Arrays.asList(
                            DIALECT,
                            "https://json-schema.org/draft/2020-12/meta/core",
                            "https://json-schema.org/draft/2020-12/meta/applicator",
                            "https://json-schema.org/draft/2020-12/meta/unevaluated",
                            "https://json-schema.org/draft/2020-12/meta/validation",
                            "https://json-schema.org/draft/2020-12/meta/meta-data",
                            "https://json-schema.org/draft/2020-12/meta/format-annotation",
                            "https://json-schema.org/draft/2020-12/meta/content"));
    private final Schema schema;

    LocalSchema(JsonNode definition) throws OfdhException {
        if (!DIALECT.equals(definition.path("$schema").asText())) {
            throw new OfdhException("SCHEMA_INVALID", "Schema 必须声明 JSON Schema Draft 2020-12");
        }
        rejectExternalReferences(definition);
        try {
            SchemaRegistry registry =
                    SchemaRegistry.withDialect(
                            Dialects.getDraft202012(),
                            builder ->
                                    builder.schemaRegistryConfig(
                                                    SchemaRegistryConfig.builder()
                                                            .formatAssertionsEnabled(true)
                                                            .build())
                                            .schemaLoader(
                                                    loader ->
                                                            loader.allow(
                                                                    iri ->
                                                                            META_IDS.contains(
                                                                                    iri
                                                                                            .toString()))));
            List<Error> errors =
                    registry.getSchema(SchemaLocation.of(DIALECT)).validate(definition);
            if (!errors.isEmpty()) {
                throw new OfdhException(
                        "SCHEMA_INVALID", "Schema 定义不符合元模式约束", null, report(definition, errors));
            }
            schema = registry.getSchema(definition);
        } catch (OfdhException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OfdhException("SCHEMA_INVALID", "Schema 编译失败", e);
        }
    }

    void validate(JsonNode input, String code) throws OfdhException {
        ValidationReport report = inspect(input, code);
        if (!report.isValid()) {
            throw new OfdhException(code, "字段校验失败，请按问题列表修正输入", null, report);
        }
    }

    ValidationReport inspect(JsonNode input, String code) throws OfdhException {
        try {
            return report(input, schema.validate(input));
        } catch (RuntimeException e) {
            throw new OfdhException(code, "Schema 校验执行失败", e);
        }
    }

    private static ValidationReport report(JsonNode input, List<Error> errors) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, Map<String, ValidationIssue>> alternatives = new LinkedHashMap<>();
        for (Error error : errors) {
            if ("oneOf".equals(error.getKeyword()) || "anyOf".equals(error.getKeyword())) {
                String path = ValidationIssues.pointer(error.getInstanceLocation());
                alternatives
                        .computeIfAbsent(
                                ValidationIssues.pointer(error.getEvaluationPath()),
                                ignored -> new LinkedHashMap<>())
                        .put(
                                path,
                                new ValidationIssue(
                                        path,
                                        error.getKeyword(),
                                        message(error.getKeyword()),
                                        expected(error.getKeyword(), error.getSchemaNode()),
                                        ValidationIssues.typeOf(input.at(path))));
            }
            collectAnyOf(input, error, alternatives);
        }
        for (Map.Entry<String, Map<String, ValidationIssue>> entry : alternatives.entrySet()) {
            if (!insideFailedAlternative(entry.getKey(), alternatives.keySet()))
                issues.addAll(entry.getValue().values());
        }
        for (Error error : errors) {
            if (insideFailedAlternative(
                    ValidationIssues.pointer(error.getEvaluationPath()), alternatives.keySet()))
                continue;
            String keyword = error.getKeyword();
            if ("oneOf".equals(keyword) || "anyOf".equals(keyword)) continue;
            String path = ValidationIssues.pointer(error.getInstanceLocation());
            if ("dependentRequired".equals(keyword)) {
                Object[] arguments = error.getArguments();
                if (arguments != null && arguments.length > 0 && arguments[0] instanceof String) {
                    path = ValidationIssues.append(path, (String) arguments[0]);
                }
            } else if (error.getProperty() != null
                    && ("required".equals(keyword)
                            || "additionalProperties".equals(keyword)
                            || "unevaluatedProperties".equals(keyword)
                            || "propertyNames".equals(keyword))) {
                path = ValidationIssues.append(path, error.getProperty());
            }
            String expected = expected(keyword, error.getSchemaNode());
            issues.add(
                    new ValidationIssue(
                            path,
                            keyword,
                            message(keyword),
                            expected,
                            "propertyNames".equals(keyword)
                                    ? "string"
                                    : ValidationIssues.typeOf(input.at(path))));
        }
        return new ValidationReport(issues, false);
    }

    /** 引擎的 anyOf 失败只含候选分支错误；按求值路径恢复组合字段，引用不增加实例层级。 */
    private static void collectAnyOf(
            JsonNode input, Error error, Map<String, Map<String, ValidationIssue>> alternatives) {
        NodePath evaluation = error.getEvaluationPath();
        NodePath instance = error.getInstanceLocation();
        int instanceDepth = 0;
        String evaluationPrefix = "";
        for (int index = 0; index < evaluation.getNameCount(); index++) {
            String segment = String.valueOf(evaluation.getElement(index));
            evaluationPrefix = ValidationIssues.append(evaluationPrefix, segment);
            if ("anyOf".equals(segment)) {
                String path = "";
                for (int depth = 0;
                        depth < Math.min(instanceDepth, instance.getNameCount());
                        depth++) {
                    path =
                            ValidationIssues.append(
                                    path, String.valueOf(instance.getElement(depth)));
                }
                alternatives
                        .computeIfAbsent(evaluationPrefix, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(
                                path,
                                new ValidationIssue(
                                        path,
                                        "anyOf",
                                        message("anyOf"),
                                        "至少满足一个候选约束",
                                        ValidationIssues.typeOf(input.at(path))));
            }
            if ("properties".equals(segment)
                    || "patternProperties".equals(segment)
                    || "prefixItems".equals(segment)) {
                if (index + 1 < evaluation.getNameCount()) {
                    instanceDepth++;
                    evaluationPrefix =
                            ValidationIssues.append(
                                    evaluationPrefix,
                                    String.valueOf(evaluation.getElement(++index)));
                }
            } else if (("items".equals(segment)
                            || "additionalProperties".equals(segment)
                            || "unevaluatedProperties".equals(segment)
                            || "contains".equals(segment)
                            || "unevaluatedItems".equals(segment))
                    && index + 1 < evaluation.getNameCount()) {
                instanceDepth++;
            }
        }
    }

    private static boolean insideFailedAlternative(String path, Set<String> alternatives) {
        int separator = path.lastIndexOf('/');
        while (separator >= 0) {
            path = path.substring(0, separator);
            if (alternatives.contains(path)) return true;
            separator = path.lastIndexOf('/');
        }
        return false;
    }

    private static String message(String keyword) {
        switch (keyword) {
            case "required":
            case "dependentRequired":
                return "缺少必填字段";
            case "type":
                return "字段类型不符合要求";
            case "format":
                return "字段格式或日期值不符合要求";
            case "additionalProperties":
            case "unevaluatedProperties":
                return "字段未在当前规范中定义";
            case "propertyNames":
                return "字段名称不符合规范";
            case "enum":
            case "const":
                return "字段值不符合声明的取值约束";
            case "pattern":
                return "字段内容不符合格式约束";
            case "minLength":
            case "maxLength":
                return "字符串长度不符合要求";
            case "minItems":
            case "maxItems":
                return "数组元素数量不符合要求";
            case "uniqueItems":
                return "数组存在重复元素";
            case "minimum":
            case "maximum":
            case "exclusiveMinimum":
            case "exclusiveMaximum":
            case "multipleOf":
                return "数值不符合范围或步长约束";
            case "minProperties":
            case "maxProperties":
                return "对象字段数量不符合要求";
            case "anyOf":
            case "oneOf":
            case "allOf":
            case "not":
                return "字段不符合组合约束";
            case "contains":
            case "minContains":
            case "maxContains":
                return "数组内容不符合匹配要求";
            default:
                return "字段未通过规范校验";
        }
    }

    private static String expected(String keyword, JsonNode constraint) {
        if (constraint == null) return "满足当前 Schema 的字段约束";
        switch (keyword) {
            case "required":
            case "dependentRequired":
                return "提供该必填字段";
            case "type":
                if (constraint.isTextual()) return ValidationIssues.typeName(constraint.asText());
                if (constraint.isArray()) {
                    List<String> types = new ArrayList<>();
                    constraint.forEach(type -> types.add(ValidationIssues.typeName(type.asText())));
                    return String.join("或", types);
                }
                return "符合声明的数据类型";
            case "format":
                switch (constraint.asText()) {
                    case "date-time":
                        return "有效日期和时间，包含时区，例如 2026-01-01T09:30:00+08:00";
                    case "date":
                        return "有效日期，格式为 YYYY-MM-DD";
                    case "time":
                        return "有效时间并包含时区";
                    case "email":
                        return "有效的电子邮件地址";
                    case "uri":
                        return "有效的绝对 URI";
                    case "uuid":
                        return "有效的 UUID";
                    case "ipv4":
                        return "有效的 IPv4 地址";
                    case "ipv6":
                        return "有效的 IPv6 地址";
                    default:
                        return "符合声明的格式";
                }
            case "additionalProperties":
            case "unevaluatedProperties":
                return "移除该字段或使用支持它的规范版本";
            case "propertyNames":
                return "使用符合当前规范命名规则的字段名称";
            case "const":
                return "符合当前规范声明的固定值";
            case "enum":
                return "使用当前规范允许的枚举值";
            case "pattern":
                if ("\\S".equals(constraint.asText())) return "至少包含一个非空白字符";
                if ("^[a-f0-9]{64}$".equals(constraint.asText())) return "64 位小写十六进制 SHA-256 摘要";
                return "符合当前字段的格式规则";
            case "minLength":
                return numericConstraint("字符数不少于 ", constraint);
            case "maxLength":
                return numericConstraint("字符数不多于 ", constraint);
            case "minItems":
                return numericConstraint("元素数量不少于 ", constraint);
            case "maxItems":
                return numericConstraint("元素数量不多于 ", constraint);
            case "minProperties":
                return numericConstraint("字段数量不少于 ", constraint);
            case "maxProperties":
                return numericConstraint("字段数量不多于 ", constraint);
            case "minimum":
                return numericConstraint("数值大于或等于 ", constraint);
            case "maximum":
                return numericConstraint("数值小于或等于 ", constraint);
            case "exclusiveMinimum":
                return numericConstraint("数值大于 ", constraint);
            case "exclusiveMaximum":
                return numericConstraint("数值小于 ", constraint);
            case "multipleOf":
                return numericConstraint("数值为此步长的整数倍：", constraint);
            case "uniqueItems":
                return "数组元素不得重复";
            case "anyOf":
                return "至少满足一个候选约束";
            case "oneOf":
                return "恰好满足一个候选约束";
            case "allOf":
                return "满足全部组合约束";
            case "not":
                return "不得符合被排除的约束";
            default:
                return "满足当前 Schema 的字段约束";
        }
    }

    private static String numericConstraint(String prefix, JsonNode value) {
        return value.isNumber() ? prefix + value.asText() : "满足声明的数值约束";
    }

    private static void rejectExternalReferences(JsonNode node) throws OfdhException {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (("$ref".equals(field.getKey()) || "$dynamicRef".equals(field.getKey()))
                        && (!field.getValue().isTextual()
                                || !field.getValue().asText().startsWith("#"))) {
                    throw new OfdhException("SCHEMA_EXTERNAL_REFERENCE", "Schema 引用只能使用当前文件内的片段标识");
                }
                rejectExternalReferences(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                rejectExternalReferences(item);
            }
        }
    }
}
