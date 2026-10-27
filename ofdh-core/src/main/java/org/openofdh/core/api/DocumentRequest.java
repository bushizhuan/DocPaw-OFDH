package org.openofdh.core.api;

import org.openofdh.core.validation.ClinicalSchemas;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一份文书及其来源版本的不可变身份和元数据，不持有文件内容或签署凭据。
 *
 * <p>documentId、sourceRecordId 和 sourceVersion 来自业务系统，与生成结果中的 fileId 不同。
 * 版式和临床数据应由宿主从同一业务版本导出；本对象无法替代业务事务或推断数据是否一致。 构造器和构建器均不做字段校验，应调用 {@link
 * Ofdh#validate(DocumentRequest)} 或生成入口检查。
 */
public final class DocumentRequest {
    private final String contractVersion;
    private final String documentId;
    private final String sourceSystem;
    private final String sourceRecordId;
    private final String sourceVersion;
    private final Code documentType;
    private final Identity patient;
    private final Identity encounter;
    private final ResponsibleParty responsibleParty;
    private final String createdAt;
    private final List<String> keywords;
    private final Code confidentiality;
    private final Source source;
    private final ClinicalData clinicalData;

    public DocumentRequest(
            String contractVersion,
            String documentId,
            String sourceSystem,
            String sourceRecordId,
            String sourceVersion,
            Code documentType,
            Identity patient,
            Identity encounter,
            ResponsibleParty responsibleParty,
            String createdAt,
            List<String> keywords,
            Code confidentiality,
            Source source,
            ClinicalData clinicalData) {
        this.contractVersion = contractVersion;
        this.documentId = documentId;
        this.sourceSystem = sourceSystem;
        this.sourceRecordId = sourceRecordId;
        this.sourceVersion = sourceVersion;
        this.documentType = documentType;
        this.patient = patient;
        this.encounter = encounter;
        this.responsibleParty = responsibleParty;
        this.createdAt = createdAt;
        this.keywords =
                keywords == null ? null : Collections.unmodifiableList(new ArrayList<>(keywords));
        this.confidentiality = confidentiality;
        this.source = source;
        this.clinicalData = clinicalData;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getContractVersion() {
        return contractVersion;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getSourceRecordId() {
        return sourceRecordId;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public Code getDocumentType() {
        return documentType;
    }

    public Identity getPatient() {
        return patient;
    }

    public Identity getEncounter() {
        return encounter;
    }

    public ResponsibleParty getResponsibleParty() {
        return responsibleParty;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public Code getConfidentiality() {
        return confidentiality;
    }

    public Source getSource() {
        return source;
    }

    public ClinicalData getClinicalData() {
        return clinicalData;
    }

    /** 只设置请求值；完整性及格式由校验入口或生成入口检查。 */
    public static final class Builder {
        private String contractVersion = "0.1.0";
        private String documentId;
        private String sourceSystem;
        private String sourceRecordId;
        private String sourceVersion;
        private Code documentType;
        private Identity patient;
        private Identity encounter;
        private ResponsibleParty responsibleParty;
        private String createdAt;
        private List<String> keywords;
        private Code confidentiality;
        private Source source;
        private ClinicalData clinicalData;

        private Builder() {}

        public Builder contractVersion(String value) {
            this.contractVersion = value;
            return this;
        }

        public Builder documentId(String value) {
            this.documentId = value;
            return this;
        }

        public Builder sourceSystem(String value) {
            this.sourceSystem = value;
            return this;
        }

        public Builder sourceRecordId(String value) {
            this.sourceRecordId = value;
            return this;
        }

        public Builder sourceVersion(String value) {
            this.sourceVersion = value;
            return this;
        }

        public Builder sourceRecord(String system, String recordId, String version) {
            this.sourceSystem = system;
            this.sourceRecordId = recordId;
            this.sourceVersion = version;
            return this;
        }

        public Builder documentType(Code value) {
            this.documentType = value;
            return this;
        }

        public Builder documentType(String code, String scheme) {
            return documentType(new Code(code, scheme));
        }

        public Builder patient(Identity value) {
            this.patient = value;
            return this;
        }

        public Builder patient(String id, String authority) {
            return patient(new Identity(id, authority));
        }

        public Builder encounter(Identity value) {
            this.encounter = value;
            return this;
        }

        public Builder encounter(String id, String authority) {
            return encounter(new Identity(id, authority));
        }

        public Builder responsibleParty(ResponsibleParty value) {
            this.responsibleParty = value;
            return this;
        }

        public Builder responsibleParty(String id, String name, String authority) {
            return responsibleParty(new ResponsibleParty(id, name, authority));
        }

        /**
         * 保留来源时间原字符串，不补时区或修正错误格式。
         *
         * @param value 来源记录创建时间，须带秒和时区；格式错误由校验入口报告
         * @return 当前构建器
         */
        public Builder createdAt(String value) {
            this.createdAt = value;
            return this;
        }

        /**
         * 从类型化时间生成带秒和明确时区的请求值。
         *
         * @param value 来源创建时间；为空时保留缺失状态供后续校验
         * @return 当前构建器
         */
        public Builder createdAt(OffsetDateTime value) {
            this.createdAt =
                    value == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value);
            return this;
        }

        public Builder keywords(List<String> values) {
            this.keywords = values == null ? null : new ArrayList<>(values);
            return this;
        }

        public Builder keywords(String... values) {
            return keywords(values == null ? null : Arrays.asList(values));
        }

        public Builder confidentiality(Code value) {
            this.confidentiality = value;
            return this;
        }

        public Builder confidentiality(String code, String scheme) {
            return confidentiality(new Code(code, scheme));
        }

        public Builder source(Source value) {
            this.source = value;
            return this;
        }

        public Builder source(String format, String path, String sha256) {
            return source(new Source(format, path, sha256));
        }

        public Builder ofd(String path) {
            return ofd(path, null);
        }

        public Builder ofd(String path, String sha256) {
            return source("OFD", path, sha256);
        }

        public Builder pdf(String path) {
            return pdf(path, null);
        }

        public Builder pdf(String path, String sha256) {
            return source("PDF", path, sha256);
        }

        public Builder clinicalData(ClinicalData value) {
            this.clinicalData = value;
            return this;
        }

        public Builder clinicalData(String path, String schemaId, String schemaVersion) {
            return clinicalData(new ClinicalData(path, schemaId, schemaVersion));
        }

        public Builder clinicalData(String path, ClinicalSchemas schema) {
            Objects.requireNonNull(schema, "临床 Schema 不能为空");
            return clinicalData(path, schema.getId(), schema.getVersion());
        }

        /**
         * 构建请求快照，不读取文件，也不自动补造业务字段。
         *
         * @return 不可变请求，可暂时不完整；后续必须校验，生成入口也会重新校验
         */
        public DocumentRequest build() {
            return new DocumentRequest(
                    contractVersion,
                    documentId,
                    sourceSystem,
                    sourceRecordId,
                    sourceVersion,
                    documentType,
                    patient,
                    encounter,
                    responsibleParty,
                    createdAt,
                    keywords,
                    confidentiality,
                    source,
                    clinicalData);
        }
    }

    public static final class Identity {
        private final String id;
        private final String authority;

        public Identity(String id, String authority) {
            this.id = id;
            this.authority = authority;
        }

        public String getId() {
            return id;
        }

        public String getAuthority() {
            return authority;
        }
    }

    public static final class ResponsibleParty {
        private final String id;
        private final String name;
        private final String authority;

        public ResponsibleParty(String id, String name, String authority) {
            this.id = id;
            this.name = name;
            this.authority = authority;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getAuthority() {
            return authority;
        }
    }

    public static final class Code {
        private final String code;
        private final String scheme;

        public Code(String code, String scheme) {
            this.code = code;
            this.scheme = scheme;
        }

        public String getCode() {
            return code;
        }

        public String getScheme() {
            return scheme;
        }
    }

    public static final class Source {
        private final String format;
        private final String path;
        private final String sha256;

        public Source(String format, String path, String sha256) {
            this.format = format;
            this.path = path;
            this.sha256 = sha256;
        }

        public String getFormat() {
            return format;
        }

        public String getPath() {
            return path;
        }

        public String getSha256() {
            return sha256;
        }
    }

    public static final class ClinicalData {
        private final String path;
        private final String schemaId;
        private final String schemaVersion;

        public ClinicalData(String path, String schemaId, String schemaVersion) {
            this.path = path;
            this.schemaId = schemaId;
            this.schemaVersion = schemaVersion;
        }

        public String getPath() {
            return path;
        }

        public String getSchemaId() {
            return schemaId;
        }

        public String getSchemaVersion() {
            return schemaVersion;
        }
    }
}
