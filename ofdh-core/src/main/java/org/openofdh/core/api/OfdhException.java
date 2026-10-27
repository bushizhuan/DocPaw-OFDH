package org.openofdh.core.api;

import org.openofdh.core.validation.ValidationIssue;
import org.openofdh.core.validation.ValidationReport;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 带稳定错误码的处理异常，字段校验失败时可同时提供结构化问题。
 *
 * <p>调用方应根据错误码及字段问题处理失败，不应解析中文提示文本。此异常继承 IOException，
 * 如需区分业务失败和普通文件错误，应先捕获本类型。消息不承诺跨版本不变；底层原因用于受控诊断， 不应直接写入患者日志或返回外部用户。
 */
public final class OfdhException extends IOException {
    private static final long serialVersionUID = 1L;
    private final String code;
    private final ValidationReport validation;

    /**
     * 创建不含字段问题的异常。
     *
     * @param code 非空的机器可读错误码
     * @param message 供开发者理解的中文说明
     */
    public OfdhException(String code, String message) {
        this(code, message, null, new ValidationReport(Collections.emptyList(), false));
    }

    /**
     * 创建带底层原因的异常，保留旧调用签名。
     *
     * @param code 机器可读错误码
     * @param message 中文说明
     * @param cause 底层原因，可为空
     */
    public OfdhException(String code, String message, Throwable cause) {
        this(code, message, cause, new ValidationReport(Collections.emptyList(), false));
    }

    /**
     * 创建带字段问题的异常，问题列表遵循校验报告的数量及截断约束。
     *
     * @param code 机器可读错误码
     * @param message 中文说明
     * @param cause 底层原因，可为空
     * @param validation 非空的结构化校验报告
     */
    public OfdhException(
            String code, String message, Throwable cause, ValidationReport validation) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "错误码不能为空");
        this.validation = Objects.requireNonNull(validation, "校验报告不能为空");
    }

    /**
     * 返回供调用方分支处理的错误码。
     *
     * @return 非空错误码；新增错误码应按未知失败处理，不从消息猜测成功或自动重试
     */
    public String getCode() {
        return code;
    }

    /**
     * 返回不可修改的字段问题列表，非字段错误可为空；空列表不表示此次操作成功。
     *
     * @return 最多 100 条问题，不包含字段原值
     */
    public List<ValidationIssue> getIssues() {
        return validation.getIssues();
    }

    /**
     * 说明是否仍有未列出的字段问题。
     *
     * @return 为真时修正后须重新校验，不能把已列问题视为全部问题
     */
    public boolean isTruncated() {
        return validation.isTruncated();
    }
}
