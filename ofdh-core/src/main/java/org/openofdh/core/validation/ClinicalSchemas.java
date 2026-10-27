package org.openofdh.core.validation;

import org.openofdh.core.api.OfdhException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/** 项目提供的临床数据接入约束，不代表国家标准的官方字段模型。 */
public enum ClinicalSchemas {
    ENVELOPE("envelope", "临床数据通用封套"),
    ADMISSION_RECORD("admission-record", "入院记录"),
    LAB_REPORT("lab-report", "检验报告");

    private static final String VERSION = "0.1.0";
    private final String name;
    private final String title;

    ClinicalSchemas(String name, String title) {
        this.name = name;
        this.title = title;
    }

    public String getId() {
        return "urn:openofdh:clinical:" + name;
    }

    public String getVersion() {
        return VERSION;
    }

    public String getTitle() {
        return title;
    }

    /** 返回完整的本地 Schema；调用方负责关闭流。 */
    public InputStream openSchema() throws IOException {
        InputStream input =
                ClinicalSchemas.class.getResourceAsStream(
                        "/org/openofdh/schemas/clinical/" + name + ".schema.json");
        if (input == null) {
            throw new OfdhException("SCHEMA_RESOURCE_MISSING", "发行包缺少内置临床 Schema：" + getId());
        }
        return input;
    }

    public ClinicalSchemaRegistry register(ClinicalSchemaRegistry registry) throws IOException {
        if (registry == null) {
            throw new IllegalArgumentException("临床 Schema 注册表不能为空");
        }
        try (InputStream input = openSchema()) {
            return registry.register(getId(), getVersion(), input);
        }
    }

    public static ClinicalSchemaRegistry registerDefaults(ClinicalSchemaRegistry registry)
            throws IOException {
        for (ClinicalSchemas schema : values()) {
            schema.register(registry);
        }
        return registry;
    }

    public static Optional<ClinicalSchemas> find(String id, String version) {
        for (ClinicalSchemas schema : values()) {
            if (schema.getId().equals(id) && schema.getVersion().equals(version)) {
                return Optional.of(schema);
            }
        }
        return Optional.empty();
    }
}
