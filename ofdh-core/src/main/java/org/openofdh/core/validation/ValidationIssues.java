package org.openofdh.core.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.path.NodePath;

final class ValidationIssues {
    private ValidationIssues() {}

    static String append(String parent, String property) {
        return parent + "/" + property.replace("~", "~0").replace("/", "~1");
    }

    static String pointer(NodePath location) {
        String result = "";
        for (int index = 0; index < location.getNameCount(); index++) {
            result = append(result, String.valueOf(location.getElement(index)));
        }
        return result;
    }

    static String typeOf(JsonNode value) {
        if (value == null || value.isMissingNode()) return "missing";
        if (value.isNull()) return "null";
        if (value.isObject()) return "object";
        if (value.isArray()) return "array";
        if (value.isTextual()) return "string";
        if (value.isIntegralNumber()) return "integer";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        return "unknown";
    }

    static String typeName(String type) {
        switch (type) {
            case "null":
                return "空值";
            case "object":
                return "对象";
            case "array":
                return "数组";
            case "string":
                return "字符串";
            case "integer":
                return "整数";
            case "number":
                return "数字";
            case "boolean":
                return "布尔值";
            default:
                return "符合约束的类型";
        }
    }
}
