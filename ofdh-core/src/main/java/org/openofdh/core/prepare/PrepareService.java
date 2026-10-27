package org.openofdh.core.prepare;

import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.PageSourceAdapter;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.check.CheckService;
import org.openofdh.core.internal.PageSourceAdapters;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.openofdh.core.layout.PageOrderOptimizer;
import org.openofdh.core.validation.ClinicalSchemaRegistry;
import org.openofdh.core.validation.RequestReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 生成新的待签署版本，不修改来源文件，也不覆盖已有输出。 */
public final class PrepareService {
    private final Map<String, PageSourceAdapter> adapters;
    private final ClinicalSchemaRegistry schemas;
    private final ResourceLimits limits;
    private final boolean optimizePageOrder;

    /**
     * 创建保留默认 ZIP 写入顺序的准备服务，不自动注册临床 Schema。
     *
     * @param adapters PDF 转换器列表，可为空列表；原生 OFD 无需适配器
     * @param schemas 已注册接入方所需规则的临床 Schema 表
     * @param limits 单次操作的资源预算
     * @throws IllegalArgumentException 转换器格式不是精确的 PDF，或重复注册
     * @throws NullPointerException 列表、列表中的转换器、规则表或预算为空
     */
    public PrepareService(
            List<PageSourceAdapter> adapters,
            ClinicalSchemaRegistry schemas,
            ResourceLimits limits) {
        this(adapters, schemas, limits, false);
    }

    /**
     * 创建准备服务，可按页面及资源依赖优化 ZIP 成员顺序。
     *
     * @param adapters PDF 转换器列表，不允许替换原生 OFD 入口
     * @param schemas 已注册所需规则的临床 Schema 表
     * @param limits 单次操作预算
     * @param optimizePageOrder 是否前置页面依赖，不增加私有索引
     * @throws IllegalArgumentException 转换器格式不支持或重复注册
     * @throws NullPointerException 必需的配置或列表成员为空
     */
    public PrepareService(
            List<PageSourceAdapter> adapters,
            ClinicalSchemaRegistry schemas,
            ResourceLimits limits,
            boolean optimizePageOrder) {
        Map<String, PageSourceAdapter> registered = new LinkedHashMap<>();
        for (PageSourceAdapter adapter : Objects.requireNonNull(adapters, "来源适配器列表不能为空")) {
            PageSourceAdapters.register(registered, adapter);
        }
        this.adapters = Collections.unmodifiableMap(registered);
        this.schemas = Objects.requireNonNull(schemas, "临床 Schema 注册表不能为空");
        this.limits = Objects.requireNonNull(limits, "资源限额不能为空");
        this.optimizePageOrder = optimizePageOrder;
    }

    /**
     * 生成新待签文件，保留来源输入；字段及来源检查失败时不会发布输出。
     *
     * @param request 一份文书的同一来源版本及可选临床附件引用
     * @param inputRoot 已存在的根目录，请求路径相对此目录解析且不能越界
     * @param output 尚不存在的本地输出路径，父目录须已存在并支持硬链接
     * @return PREPARED 阶段结果，不代表已签署或完整标准符合性
     * @throws IOException 校验、转换、文件读写、安全发布失败或操作取消
     */
    public PreparedDocument prepare(DocumentRequest request, Path inputRoot, Path output)
            throws IOException {
        RequestReader.validate(request);
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new OfdhException("OUTPUT_EXISTS", "输出文件已存在");
        Path outputParent = target.getParent();
        if (outputParent == null || !Files.isDirectory(outputParent)) {
            throw new OfdhException("OUTPUT_INVALID", "输出目录必须已存在，且不能经过符号链接或联接点");
        }
        outputParent = FileSafety.unlinkedDirectory(outputParent);
        target = outputParent.resolve(target.getFileName());
        Path source = FileSafety.resolveInput(inputRoot, request.getSource().getPath());
        Path workspace = FileSafety.privateDirectory("ofdh-prepare-");
        Path staged = outputParent.resolve(".ofdh-" + UUID.randomUUID() + ".prepared");
        boolean published = false;
        try {
            Path snapshot = workspace.resolve("source");
            String sourceHash = FileSafety.snapshot(source, snapshot, limits.getMaxInputBytes());
            if (request.getSource().getSha256() != null
                    && !request.getSource().getSha256().equals(sourceHash)) {
                throw new OfdhException("SOURCE_HASH_MISMATCH", "来源文件与预期摘要不一致");
            }
            Path clinical = null;
            String clinicalHash = null;
            if (request.getClinicalData() != null) {
                clinical = workspace.resolve("clinical.json");
                clinicalHash =
                        FileSafety.snapshot(
                                FileSafety.resolveInput(
                                        inputRoot, request.getClinicalData().getPath()),
                                clinical,
                                limits.getMaxClinicalBytes());
                schemas.validate(request, clinical);
            }
            Path ofd = snapshot;
            if (!"OFD".equals(request.getSource().getFormat())) {
                PageSourceAdapter adapter = adapters.get(request.getSource().getFormat());
                if (adapter == null)
                    throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "尚未注册该来源格式的适配器");
                ofd = workspace.resolve("converted.ofd");
                adapter.convert(snapshot, ofd, limits);
                FileSafety.cancelled();
                if (!Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(snapshot) > limits.getMaxInputBytes()
                        || !sourceHash.equals(FileSafety.sha256(snapshot))) {
                    throw new OfdhException("INPUT_CHANGED", "来源适配器改写或删除了只读的输入快照");
                }
            }
            String fileId =
                    UUID.randomUUID()
                            .toString()
                            .replace("-", "")
                            .toUpperCase(java.util.Locale.ROOT);
            String generatedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
            try (SafePackage archive = SafePackage.open(ofd, limits)) {
                new PackageProfile(archive).validate(limits);
                writeMetadata(archive, request, fileId, generatedAt, sourceHash, clinicalHash);
                if (clinical != null) attachClinical(archive, clinical);
                new PackageProfile(archive).validate(limits);
                if (optimizePageOrder) {
                    PageOrderOptimizer.publish(archive, staged, limits);
                } else {
                    archive.publish(staged);
                }
            }
            CheckReport report = new CheckService(limits).check(staged, CheckProfile.PREPARED);
            if ("ISSUES_FOUND".equals(report.getAssessment())) {
                StringBuilder failed = new StringBuilder();
                for (CheckReport.Result result : report.getResults()) {
                    if ("FAIL".equals(result.getResult())) {
                        if (failed.length() > 0) failed.append(", ");
                        failed.append(result.getRuleId());
                    }
                }
                throw new OfdhException("PREPARED_CHECK_FAILED", "待签署文件未通过检查：" + failed);
            }
            FileSafety.deleteTree(workspace);
            FileSafety.publish(staged, target);
            published = true;
            return new PreparedDocument(
                    target, fileId, generatedAt, sourceHash, report.getArtifact().getSha256());
        } finally {
            if (!published) {
                try {
                    Files.deleteIfExists(staged);
                } finally {
                    FileSafety.deleteTree(workspace);
                }
            }
        }
    }

    private void writeMetadata(
            SafePackage archive,
            DocumentRequest request,
            String fileId,
            String generatedAt,
            String sourceHash,
            String clinicalHash)
            throws IOException {
        Document document = archive.readXml("OFD.xml");
        Element root = document.getDocumentElement();
        root.setAttribute("DocType", "OFD-H");
        Element body = Xml.child(root, "DocBody");
        List<Element> oldInfos = Xml.children(body, "DocInfo");
        if (oldInfos.size() > 1)
            throw new OfdhException("OFD_STRUCTURE_INVALID", "不支持多个 DocInfo 元素");
        Element old = oldInfos.isEmpty() ? null : oldInfos.get(0);
        if (old != null
                && Xml.children(old, null).isEmpty()
                && !old.getTextContent().trim().isEmpty()) {
            throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "引用式文档元数据需要明确的导入适配规则");
        }
        Element info = document.createElementNS(Xml.NS, "ofd:DocInfo");
        Xml.append(info, "DocID", fileId);
        preserve(info, old, "Title");
        Xml.append(info, "Author", request.getResponsibleParty().getName());
        preserve(info, old, "Subject");
        preserve(info, old, "Abstract");
        Xml.append(info, "CreationDate", generatedAt.substring(0, 10));
        preserve(info, old, "DocUsage");
        preserve(info, old, "Cover");
        Element keywords = Xml.append(info, "Keywords", null);
        for (String keyword : request.getKeywords()) Xml.append(keywords, "Keyword", keyword);
        Xml.append(info, "Creator", "DocPaw-OFDH");
        Xml.append(info, "CreatorVersion", "0.2.0");
        Element custom = Xml.append(info, "CustomDatas", null);
        Element previousCustom = old == null ? null : Xml.child(old, "CustomDatas");
        Map<String, String> previousValues = new LinkedHashMap<>();
        if (previousCustom != null) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Element item : Xml.children(previousCustom, "CustomData")) {
                String name = item.getAttribute("Name");
                if (!names.add(name)) throw new OfdhException("OFD_STRUCTURE_INVALID", "文档元数据字段重复");
                previousValues.put(name, item.getTextContent());
                if (!name.startsWith("openofdh.")) custom.appendChild(item.cloneNode(true));
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("metadataProfile", "0.1.0");
        values.put("generatedAt", generatedAt);
        values.put("documentId", request.getDocumentId());
        values.put("sourceSystem", request.getSourceSystem());
        values.put("sourceRecordId", request.getSourceRecordId());
        values.put("sourceVersion", request.getSourceVersion());
        values.put("sourceCreatedAt", request.getCreatedAt());
        values.put("sourceSha256", sourceHash);
        values.put("patient.id", request.getPatient().getId());
        values.put("patient.authority", request.getPatient().getAuthority());
        values.put("responsibleParty.id", request.getResponsibleParty().getId());
        values.put("responsibleParty.authority", request.getResponsibleParty().getAuthority());
        values.put("documentType.code", request.getDocumentType().getCode());
        values.put("documentType.scheme", request.getDocumentType().getScheme());
        values.put("confidentiality.code", request.getConfidentiality().getCode());
        values.put("confidentiality.scheme", request.getConfidentiality().getScheme());
        if (request.getEncounter() != null) {
            values.put("encounter.id", request.getEncounter().getId());
            values.put("encounter.authority", request.getEncounter().getAuthority());
        }
        if (request.getClinicalData() != null) {
            values.put("clinical.schemaId", request.getClinicalData().getSchemaId());
            values.put("clinical.schemaVersion", request.getClinicalData().getSchemaVersion());
            values.put("clinical.sha256", clinicalHash);
        } else {
            for (String key :
                    new String[] {
                        "clinical.schemaId", "clinical.schemaVersion", "clinical.sha256"
                    }) {
                String previous = previousValues.get("openofdh." + key);
                if (previous != null) values.put(key, previous);
            }
        }
        for (String key :
                new String[] {
                    "documentId",
                    "sourceSystem",
                    "sourceRecordId",
                    "sourceVersion",
                    "sourceCreatedAt",
                    "patient.id",
                    "patient.authority",
                    "encounter.id",
                    "encounter.authority",
                    "documentType.code",
                    "documentType.scheme",
                    "responsibleParty.id",
                    "responsibleParty.authority"
                }) {
            String previous = previousValues.get("openofdh." + key);
            if (previous != null && !previous.equals(values.get(key))) {
                throw new OfdhException("SOURCE_VERSION_MISMATCH", "请求与来源元数据不一致：" + key);
            }
        }
        for (Map.Entry<String, String> value : values.entrySet()) {
            Element item = Xml.append(custom, "CustomData", value.getValue());
            item.setAttribute("Name", "openofdh." + value.getKey());
        }
        if (old != null) body.replaceChild(info, old);
        else body.insertBefore(info, body.getFirstChild());
        archive.writeXml("OFD.xml", document);
    }

    private static void preserve(Element target, Element source, String name) {
        Element value = source == null ? null : Xml.child(source, name);
        if (value != null) target.appendChild(value.cloneNode(true));
    }

    private static void attachClinical(SafePackage archive, Path clinical) throws IOException {
        Document document = archive.readXml("Doc_0/Document.xml");
        Element root = document.getDocumentElement();
        List<Element> existing = Xml.children(root, "Attachments");
        if (existing.size() > 1) throw new OfdhException("OFD_STRUCTURE_INVALID", "附件索引重复");
        String indexPath = "Doc_0/Attachs/Attachments.xml";
        Document index;
        Element location = existing.isEmpty() ? null : existing.get(0);
        if (location != null) {
            String previousPath =
                    SafePackage.resolve("Doc_0/Document.xml", location.getTextContent().trim());
            index = archive.readXml(previousPath);
            if (!previousPath.equals(indexPath)) {
                if (archive.contains(indexPath))
                    throw new OfdhException("OFD_STRUCTURE_INVALID", "附件索引的目标位置已被占用");
                for (Element item : Xml.children(index.getDocumentElement(), "Attachment")) {
                    Element file = Xml.child(item, "FileLoc");
                    if (file == null) throw new OfdhException("OFD_STRUCTURE_INVALID", "附件缺少文件位置");
                    file.setTextContent(
                            "/" + SafePackage.resolve(previousPath, file.getTextContent().trim()));
                }
            }
        } else {
            if (archive.contains(indexPath))
                throw new OfdhException("OFD_STRUCTURE_INVALID", "存在未登记的附件索引");
            index = Xml.newDocument();
            index.appendChild(index.createElementNS(Xml.NS, "ofd:Attachments"));
            location = Xml.append(root, "Attachments", null);
        }
        location.setTextContent("Attachs/Attachments.xml");
        int number = 0;
        String payload;
        do {
            payload = "Doc_0/Attachs/Attach_" + number++ + ".json";
        } while (archive.contains(payload));
        Element attachment = Xml.append(index.getDocumentElement(), "Attachment", null);
        attachment.setAttribute("ID", UUID.randomUUID().toString().replace("-", ""));
        attachment.setAttribute("Name", "clinical.json");
        attachment.setAttribute("Format", "json");
        attachment.setAttribute(
                "Size",
                java.math.BigDecimal.valueOf(Files.size(clinical))
                        .divide(
                                java.math.BigDecimal.valueOf(1024),
                                6,
                                java.math.RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString());
        Xml.append(attachment, "FileLoc", payload.substring(payload.lastIndexOf('/') + 1));
        archive.write(payload, Files.readAllBytes(clinical));
        archive.writeXml(indexPath, index);
        archive.writeXml("Doc_0/Document.xml", document);
    }
}
