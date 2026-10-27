package org.openofdh.core.check;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 绑定输入快照摘要的只读检查结果，可共享，不代表符合性认证。
 *
 * <p>assessment 为 ISSUES_FOUND 表示存在失败规则，INCOMPLETE 表示仍有未检查项， NO_ISSUES_IN_EXECUTED_CHECKS
 * 仅表示执行范围内未发现问题。逐条规则还区分不适用与未检查， 不能将两者解释为通过。签名密码验证、证书信任、吊销及时间戳应分别判断。
 *
 * <p>报告不访问网络补充信任材料。其结论适用于 artifact.sha256 对应的文件字节，文件变化后须重新检查。
 */
public final class CheckReport {
    private final String reportId = UUID.randomUUID().toString();
    private final String generatedAt = Instant.now().toString();
    private final Artifact artifact;
    private final RuleSet ruleSet;
    private final String assessment;
    private final Coverage coverage;
    private final List<Result> results;
    private final SignatureVerification signatureVerification;
    private final List<String> limitations;

    CheckReport(
            String filename,
            String digest,
            CheckProfile profile,
            List<Result> results,
            SignatureVerification signatures) {
        this.artifact = new Artifact(filename, digest);
        this.ruleSet = new RuleSet(profile);
        this.results = immutable(results);
        this.signatureVerification = signatures;
        this.coverage = new Coverage(results);
        this.assessment =
                coverage.resultCounts.get("FAIL") > 0
                        ? "ISSUES_FOUND"
                        : coverage.scopeComplete ? "NO_ISSUES_IN_EXECUTED_CHECKS" : "INCOMPLETE";
        List<String> notes = new ArrayList<>();
        notes.add("本报告仅覆盖列出的检查规则，不构成符合性认证。");
        notes.add("检查期间未访问外部附件、信任服务、Schema 或吊销查询服务。");
        if (profile == CheckProfile.PREPARED) {
            notes.add("准备阶段报告描述待签署制品，不确认法律效力。");
        }
        this.limitations = immutable(notes);
    }

    public String getContractVersion() {
        return "0.1.0";
    }

    public Tool getTool() {
        return new Tool();
    }

    public String getReportId() {
        return reportId;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public Artifact getArtifact() {
        return artifact;
    }

    public RuleSet getRuleSet() {
        return ruleSet;
    }

    public String getAssessment() {
        return assessment;
    }

    public Coverage getCoverage() {
        return coverage;
    }

    public List<Result> getResults() {
        return results;
    }

    public SignatureVerification getSignatureVerification() {
        return signatureVerification;
    }

    public List<String> getLimitations() {
        return limitations;
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public static final class Tool {
        public String getName() {
            return "DocPaw-OFDH";
        }

        public String getVersion() {
            return "0.2.0";
        }
    }

    public static final class Artifact {
        private final String name;
        private final String sha256;

        Artifact(String name, String sha256) {
            this.name = name;
            this.sha256 = sha256;
        }

        public String getName() {
            return name;
        }

        public String getSha256() {
            return sha256;
        }
    }

    public static final class RuleSet {
        private final String id;

        RuleSet(CheckProfile profile) {
            id = "openofdh-" + profile.name().toLowerCase(java.util.Locale.ROOT);
        }

        public String getId() {
            return id;
        }

        public String getVersion() {
            return "0.1.0";
        }

        public String getStandard() {
            return "GB/T 48666-2026";
        }
    }

    public static final class Coverage {
        private final int catalogRuleCount;
        private final int implementedRuleCount;
        private final int evaluatedRuleCount;
        private final Map<String, Integer> resultCounts;
        private final boolean scopeComplete;
        private final List<String> uncoveredAreas;

        Coverage(List<Result> results) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("PASS", 0);
            counts.put("FAIL", 0);
            counts.put("NOT_APPLICABLE", 0);
            counts.put("NOT_CHECKED", 0);
            List<String> uncovered = new ArrayList<>();
            for (Result result : results) {
                counts.put(result.result, counts.get(result.result) + 1);
                if ("NOT_CHECKED".equals(result.result)) uncovered.add(result.ruleId);
            }
            catalogRuleCount = Rule.values().length;
            int implemented = 0;
            for (Rule rule : Rule.values()) if (rule.implemented) implemented++;
            implementedRuleCount = implemented;
            evaluatedRuleCount = results.size() - counts.get("NOT_CHECKED");
            resultCounts = Collections.unmodifiableMap(counts);
            scopeComplete = uncovered.isEmpty();
            uncoveredAreas = immutable(uncovered);
        }

        public int getCatalogRuleCount() {
            return catalogRuleCount;
        }

        public int getImplementedRuleCount() {
            return implementedRuleCount;
        }

        public int getEvaluatedRuleCount() {
            return evaluatedRuleCount;
        }

        public Map<String, Integer> getResultCounts() {
            return resultCounts;
        }

        public boolean isScopeComplete() {
            return scopeComplete;
        }

        public List<String> getUncoveredAreas() {
            return uncoveredAreas;
        }
    }

    public static final class Result {
        private final String ruleId;
        private final String result;
        private final String severity;
        private final RuleSource ruleSource;
        private final String clause;
        private final String message;
        private final String location;
        private final String remediation;

        Result(Rule rule, String result, String message, String location) {
            this.ruleId = rule.id;
            this.result = result;
            this.severity =
                    "FAIL".equals(result)
                            ? "ERROR"
                            : "NOT_CHECKED".equals(result) ? "WARNING" : "INFO";
            this.ruleSource = new RuleSource(rule);
            this.clause = rule.clause;
            this.message = message;
            this.location = location == null ? "" : location;
            this.remediation = rule.remediation;
        }

        public String getRuleId() {
            return ruleId;
        }

        public String getResult() {
            return result;
        }

        public String getSeverity() {
            return severity;
        }

        public RuleSource getRuleSource() {
            return ruleSource;
        }

        @JsonInclude(JsonInclude.Include.ALWAYS)
        public String getClause() {
            return clause;
        }

        public String getMessage() {
            return message;
        }

        public String getLocation() {
            return location;
        }

        public String getRemediation() {
            return remediation;
        }
    }

    public static final class RuleSource {
        private final String kind;
        private final String reference;

        RuleSource(Rule rule) {
            kind = rule.clause == null ? "PROJECT" : "STANDARD";
            reference = rule.clause == null ? "DocPaw-OFDH 文件约束 0.1.0" : "GB/T 48666-2026";
        }

        public String getKind() {
            return kind;
        }

        public String getReference() {
            return reference;
        }
    }

    public static final class SignatureVerification {
        private final String enumeration;
        private final Integer signatureCount;
        private final List<Signature> signatures;

        SignatureVerification(
                String enumeration, Integer signatureCount, List<Signature> signatures) {
            this.enumeration = enumeration;
            this.signatureCount = signatureCount;
            this.signatures = immutable(signatures);
        }

        public String getEnumeration() {
            return enumeration;
        }

        @JsonInclude(JsonInclude.Include.ALWAYS)
        public Integer getSignatureCount() {
            return signatureCount;
        }

        public List<Signature> getSignatures() {
            return signatures;
        }
    }

    public static final class Signature {
        private final String signatureId;
        private final String cryptographicVerification;
        private final String certificateTrust;
        private final String timestamp;
        private final String reason;

        Signature(
                String id,
                String cryptographicVerification,
                String certificateTrust,
                String timestamp,
                String reason) {
            this.signatureId = id;
            this.cryptographicVerification = cryptographicVerification;
            this.certificateTrust = certificateTrust;
            this.timestamp = timestamp;
            this.reason = reason;
        }

        public String getSignatureId() {
            return signatureId;
        }

        public String getCryptographicVerification() {
            return cryptographicVerification;
        }

        public String getCertificateTrust() {
            return certificateTrust;
        }

        public String getRevocation() {
            return "UNKNOWN";
        }

        public String getTimestamp() {
            return timestamp;
        }

        public String getReason() {
            return reason;
        }
    }
}
