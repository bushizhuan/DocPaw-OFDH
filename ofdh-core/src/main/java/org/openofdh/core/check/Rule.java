package org.openofdh.core.check;

enum Rule {
    CONTAINER("PKG-001", null, true, "请提供未加密且符合资源限额的 ZIP 文件包。"),
    TEXT_COMPRESSION("PKG-002", null, true, "非空 XML、JSON 和 TXT 成员应使用 Deflate 压缩，以满足本项目文件约束。"),
    SINGLE_DOCUMENT("OFDH-001", "6.1.2, 6.2", true, "请使用单个 DocBody，文档根入口为 Doc_0/Document.xml。"),
    NAMESPACE("OFDH-002", "6.1.2", true, "请使用 OFD 命名空间及受支持的 OFD 版本。"),
    DOCUMENT_TYPE("OFDH-003", "7.1(a)", true, "请将 OFD 根节点的 DocType 设置为 OFD-H。"),
    DOCUMENT_ID("OFDH-004", "7.3(b)", true, "请设置合法的 OFD 文档标识。"),
    BASIC_METADATA("OFDH-005", "7.1(c), 7.3(a)(b)", true, "请提供可识别的患者、作者、生成时间、关键词、文书类型及密级元数据。"),
    METADATA_PROFILE("META-001", null, true, "请修正已声明的 DocPaw-OFDH 元数据映射；不得改写已签文件。"),
    PAGES("OFDH-006", "6.2, 7.1(b)", true, "请登记所有页面并提供其引用的内容文件。"),
    RESOURCES("OFDH-007", "7.1(b), 7.2(a)", true, "请登记资源，并保证文件引用和对象引用可解析。"),
    FONTS("OFDH-008", "7.2(b)", true, "请嵌入所需字体，或提供同时满足字符集与字体豁免条件的证据。"),
    IMAGES("OFDH-009", "7.2(c)(d)", true, "请以受支持的格式嵌入图像资源。"),
    ATTACHMENTS("OFDH-010", "6.2, 7.1(e), 7.4(a)(b)", true, "请登记内部附件并修正其文件引用。"),
    ENCODING("OFDH-011", "7.5(a)(b)", true, "机读 XML 和 JSON 必须使用合法的 UTF-8 编码及正确语法。"),
    CLINICAL_DIGEST("CLINICAL-001", null, true, "请将声明的临床附件与实际 SHA-256 摘要绑定。"),
    CLINICAL_ENVELOPE("CLINICAL-002", null, true, "临床数据封套与文档元数据必须一致。"),
    SIGNATURE_REQUIRED("OFDH-012", "6.2, 5.3.1", true, "正式使用前应完成已授权的签署流程。"),
    SIGNATURE_REFERENCES("OFDH-013", "6.3(e), 8(c)", true, "请修正签名引用；内容发生修改后必须重新签署。"),
    CONTENT_COVERAGE("SIGN-001", null, true, "签名应保护文档元数据、页面内容、已登记资源和附件。"),
    SIGNATURE_CRYPTOGRAPHY("OFDH-014", "6.3, 8(e)", true, "请使用受支持的签名格式验证实际签名值。"),
    LINEARIZATION("OFDH-015", "6.1.3", false, "请提供所采用 ZIP/OFD 线性化方案的符合性证据。"),
    VISUAL_EQUIVALENCE("OFDH-016", "5.3.1, 7.3", false, "请使用独立渲染器比对来源和输出，并进行临床内容复核。"),
    CLINICAL_SEMANTICS("OFDH-017", "7.5(d)", false, "请依据适用临床规范及来源记录进行验证。"),
    SIGNATURE_TRUST("OFDH-018", "6.3, 8(e)", true, "请建立证书信任、签署授权、吊销状态及可信时间的验证证据。"),
    SIGNATURE_ALGORITHMS("OFDH-019", "6.3(a)(d), 8(d)", false, "请与签署服务提供方核对适用算法及签名产品要求。"),
    VERSION_RETENTION("OFDH-020", "7.6", false, "请确认归档系统按要求保留历史内容或变更记录。"),
    ARCHIVAL("OFDH-021", "9.1", false, "请依据适用的引用标准评估长期保存要求。"),
    READER_BEHAVIOR("OFDH-022", "9.2", false, "请在实际阅读器中验证权限、显示效果及签名处理行为。");

    final String id;
    final String clause;
    final boolean implemented;
    final String remediation;

    Rule(String id, String clause, boolean implemented, String remediation) {
        this.id = id;
        this.clause = clause;
        this.implemented = implemented;
        this.remediation = remediation;
    }
}
