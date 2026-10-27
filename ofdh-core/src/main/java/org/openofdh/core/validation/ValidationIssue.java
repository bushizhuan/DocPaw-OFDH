package org.openofdh.core.validation;

import java.io.Serializable;
import java.util.Objects;

/**
 * 不可变的字段校验问题，路径使用 JSON Pointer，空字符串表示当前 JSON 的根节点。
 *
 * <p>keyword 供程序区分约束，message 与 expected 为中文说明，actualType 表示类型或 missing， 不保存输入字段原值。path
 * 包含字段名，因此调用方不应把患者信息作为动态字段名。
 */
public final class ValidationIssue implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String path;
    private final String keyword;
    private final String message;
    private final String expected;
    private final String actualType;

    public ValidationIssue(
            String path, String keyword, String message, String expected, String actualType) {
        this.path = Objects.requireNonNull(path, "字段路径不能为空");
        this.keyword = Objects.requireNonNull(keyword, "校验关键字不能为空");
        this.message = Objects.requireNonNull(message, "错误说明不能为空");
        this.expected = Objects.requireNonNull(expected, "约束说明不能为空");
        this.actualType = Objects.requireNonNull(actualType, "实际类型不能为空");
        if (!path.isEmpty() && !path.startsWith("/")) {
            throw new IllegalArgumentException("字段路径必须是 JSON Pointer");
        }
    }

    public String getPath() {
        return path;
    }

    public String getKeyword() {
        return keyword;
    }

    public String getMessage() {
        return message;
    }

    public String getExpected() {
        return expected;
    }

    public String getActualType() {
        return actualType;
    }
}
