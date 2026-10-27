package org.openofdh.core.validation;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 按字段路径稳定排序的不可变校验结果，最多返回 100 个不同问题，可在线程间共享。
 *
 * <p>isValid 仅在没有问题且没有截断时为真。truncated 为真表示问题尚未列全， 修正后须重新校验；不能因可见列表为空而判定有效。报告不包含输入字段原值。
 */
public final class ValidationReport implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int MAX_ISSUES = 100;

    private static final Comparator<ValidationIssue> ORDER =
            Comparator.comparing(ValidationIssue::getPath)
                    .thenComparing(ValidationIssue::getKeyword)
                    .thenComparing(ValidationIssue::getExpected)
                    .thenComparing(ValidationIssue::getActualType)
                    .thenComparing(ValidationIssue::getMessage);

    private final List<ValidationIssue> issues;
    private final boolean truncated;

    public ValidationReport(List<ValidationIssue> issues, boolean truncated) {
        Objects.requireNonNull(issues, "校验问题列表不能为空");
        TreeSet<ValidationIssue> ordered = new TreeSet<>(ORDER);
        boolean omitted = truncated;
        for (ValidationIssue issue : issues) {
            ordered.add(Objects.requireNonNull(issue, "校验问题不能为空"));
            if (ordered.size() > MAX_ISSUES) {
                ordered.pollLast();
                omitted = true;
            }
        }
        this.issues = Collections.unmodifiableList(new ArrayList<>(ordered));
        this.truncated = omitted;
    }

    public boolean isValid() {
        return issues.isEmpty() && !truncated;
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }

    public boolean isTruncated() {
        return truncated;
    }
}
