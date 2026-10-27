package org.openofdh.core.check;

import static org.openofdh.core.check.Inspection.child;
import static org.openofdh.core.check.Inspection.descendants;
import static org.openofdh.core.check.Inspection.elements;
import static org.openofdh.core.check.Inspection.text;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.openofdh.core.layout.PageDependencyPlanner;
import org.openofdh.core.layout.PageLayout;
import org.openofdh.core.validation.ClinicalEnvelopeProfile;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DocumentInspector {
    private static final List<String> REQUIRED_METADATA =
            Arrays.asList(
                    "documentId",
                    "sourceSystem",
                    "sourceRecordId",
                    "sourceVersion",
                    "sourceCreatedAt",
                    "patient.id",
                    "patient.authority",
                    "responsibleParty.id",
                    "responsibleParty.authority",
                    "documentType.code",
                    "documentType.scheme",
                    "confidentiality.code",
                    "confidentiality.scheme",
                    "generatedAt",
                    "sourceSha256");
    private final Inspection state;
    private final ResourceLimits limits;
    private final ObjectMapper json;
    private final Map<String, Element> resources = new LinkedHashMap<>();
    private final List<String> pages = new ArrayList<>();
    private final Set<String> resourceCatalogs = new HashSet<>();
    private final Set<String> templateIds = new HashSet<>();
    private final List<String> machineReadable = new ArrayList<>();
    private boolean profileKnown;
    private String author = "";
    private final Set<String> keywords = new HashSet<>();

    DocumentInspector(Inspection state, ResourceLimits limits) {
        this.state = state;
        this.limits = limits;
        JsonFactory factory = new JsonFactory();
        factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        factory.setStreamReadConstraints(
                StreamReadConstraints.builder()
                        .maxNestingDepth(100)
                        .maxStringLength(
                                (int) Math.min(limits.getMaxClinicalBytes(), Integer.MAX_VALUE))
                        .build());
        json = new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    void inspect() throws IOException {
        Element ofd;
        Element body;
        Element document;
        String documentEntry;
        try {
            ofd = state.xml("OFD.xml");
            if (!"OFD".equals(ofd.getLocalName()) || !Xml.NS.equals(ofd.getNamespaceURI())) {
                state.fail(Rule.NAMESPACE, "主文件的 OFD 命名空间或根节点无法识别。", "OFD.xml");
                return;
            }
            String version = ofd.getAttribute("Version");
            if ("1.0".equals(version))
                state.pass(Rule.NAMESPACE, "已识别 OFD 1.0 命名空间及版本。", "OFD.xml");
            else state.unknown(Rule.NAMESPACE, "当前检查器尚未验证所声明的 OFD 版本。", "OFD.xml");
            if ("OFD-H".equals(ofd.getAttribute("DocType")))
                state.pass(Rule.DOCUMENT_TYPE, "DocType 已设置为 OFD-H。", "OFD.xml");
            else state.fail(Rule.DOCUMENT_TYPE, "DocType 未设置为 OFD-H。", "OFD.xml");
            List<Element> bodies = descendants(ofd, "DocBody");
            if (bodies.size() != 1) {
                state.fail(Rule.SINGLE_DOCUMENT, "文件包必须且只能包含一个文档体。", "OFD.xml");
                return;
            }
            body = bodies.get(0);
            documentEntry = state.requireFile("OFD.xml", text(body, "DocRoot"));
            if (!"Doc_0/Document.xml".equals(documentEntry)) {
                state.fail(Rule.SINGLE_DOCUMENT, "文档根入口不是 Doc_0/Document.xml。", "OFD.xml");
            } else state.pass(Rule.SINGLE_DOCUMENT, "单个文档体使用 Doc_0/Document.xml 作为根入口。", "OFD.xml");
            document = state.xml(documentEntry);
            if (!"Document".equals(document.getLocalName())
                    || !Xml.NS.equals(document.getNamespaceURI())) {
                state.fail(Rule.SINGLE_DOCUMENT, "DocRoot 未引用 OFD Document 元素。", documentEntry);
                return;
            }
        } catch (IOException e) {
            FileSafety.cancelled();
            state.fail(Rule.SINGLE_DOCUMENT, "无法安全解析 OFD 入口或文档根节点。", "OFD.xml");
            return;
        }
        inspectMetadata(body);
        inspectPagesAndResources(document, documentEntry);
        inspectAttachments(document, documentEntry);
        inspectEncoding();
        inspectClinical();
        new SignatureInspector(state).inspect(body);
    }

    private void inspectMetadata(Element body) {
        Element info = child(body, "DocInfo");
        if (info == null) {
            state.fail(Rule.DOCUMENT_ID, "缺少文档信息。", "OFD.xml");
            state.unknown(Rule.BASIC_METADATA, "未找到受支持的内嵌文档元数据。", "OFD.xml");
            return;
        }
        String fileId = text(info, "DocID");
        if (fileId.matches("(?i)[0-9a-f]{32}")) {
            state.pass(Rule.DOCUMENT_ID, "文档标识符合 OFD UUID 表示形式。", "OFD.xml");
        } else state.fail(Rule.DOCUMENT_ID, "DocID 不是 32 位十六进制 UUID。", "OFD.xml");
        author = text(info, "Author");
        for (Element keyword : descendants(info, "Keyword"))
            keywords.add(keyword.getTextContent().trim());
        boolean duplicate = false;
        for (Element data : descendants(info, "CustomData")) {
            String name = data.getAttribute("Name");
            if (state.metadata.put(name, data.getTextContent().trim()) != null) duplicate = true;
        }
        profileKnown = "0.1.0".equals(state.metadata.get("openofdh.metadataProfile"));
        if (!profileKnown) {
            state.unknown(Rule.METADATA_PROFILE, "未声明受支持的 DocPaw-OFDH 元数据映射。", "OFD.xml");
            state.unknown(Rule.BASIC_METADATA, "第三方元数据需要明确的字段映射，才能判断必要临床字段。", "OFD.xml");
            return;
        }
        boolean required = !author.isEmpty() && !descendants(info, "Keyword").isEmpty();
        for (String name : REQUIRED_METADATA) required &= hasMetadata(name);
        try {
            LocalDate.parse(text(info, "CreationDate"));
            OffsetDateTime.parse(metadata("generatedAt"));
            OffsetDateTime.parse(metadata("sourceCreatedAt"));
        } catch (DateTimeParseException e) {
            required = false;
        }
        for (Element keyword : descendants(info, "Keyword"))
            if (keyword.getTextContent().trim().isEmpty()) required = false;
        if (required && !duplicate && metadata("sourceSha256").matches("[0-9a-f]{64}")) {
            state.pass(Rule.METADATA_PROFILE, "必要映射字段、时间和来源摘要均已提供，且格式正确。", "OFD.xml");
            state.pass(Rule.BASIC_METADATA, "必要元数据可通过声明的映射识别；本项不验证身份信息的真实性。", "OFD.xml");
        } else {
            state.fail(Rule.METADATA_PROFILE, "声明的映射元数据存在缺失、重复或格式错误。", "OFD.xml");
            state.fail(Rule.BASIC_METADATA, "无法通过声明的映射确认必要元数据。", "OFD.xml");
        }
    }

    private void inspectPagesAndResources(Element document, String entry) throws IOException {
        try {
            Element pageList = child(document, "Pages");
            List<Element> registrations =
                    pageList == null ? new ArrayList<Element>() : descendants(pageList, "Page");
            if (registrations.isEmpty() || registrations.size() > limits.getMaxPages()) {
                state.fail(Rule.PAGES, "页面数量为空或超过检查限额。", entry);
                return;
            }
            Set<String> pageIds = new HashSet<>();
            Set<String> pagePaths = new HashSet<>();
            for (Element page : registrations) {
                FileSafety.cancelled();
                String id = page.getAttribute("ID");
                if (!id.matches("[1-9][0-9]*") || !pageIds.add(id))
                    throw new IOException("页面标识不合法。");
                String path = state.requireFile(entry, page.getAttribute("BaseLoc"));
                if (!pagePaths.add(path)) throw new IOException("页面路径重复。");
                Element content = state.xml(path);
                if (!"Page".equals(content.getLocalName())
                        || !Xml.NS.equals(content.getNamespaceURI()))
                    throw new IOException("页面根节点不合法。");
                pages.add(path);
                registerResources(content, path, "PageRes");
            }
            state.pass(Rule.PAGES, "已登记的页面均可解析到不同的 OFD 页面文档。", entry);
            registerResources(document, entry, "PublicRes");
            registerResources(document, entry, "DocumentRes");
            for (Element template : descendants(document, "TemplatePage")) {
                if (pages.size() >= limits.getMaxPages()) throw new IOException("页面及模板总数超过限额。");
                if (!templateIds.add(template.getAttribute("ID"))) throw new IOException("模板标识重复。");
                String path = state.requireFile(entry, template.getAttribute("BaseLoc"));
                Element content = state.xml(path);
                pages.add(path);
                registerResources(content, path, "PageRes");
            }
            inspectResourceObjects();
            inspectDrawingDependencies(entry);
        } catch (IOException e) {
            FileSafety.cancelled();
            state.fail(Rule.RESOURCES, "页面、模板或资源引用存在缺失、重复或不支持的情况。", entry);
            state.fail(Rule.PAGES, "无法完整解析页面及资源的引用关系。", entry);
        }
    }

    private void inspectDrawingDependencies(String entry) throws IOException {
        try {
            // 与页面优化共用引用分析，普通布局也必须识别断链、类别错配和循环。
            PageLayout layout = PageDependencyPlanner.plan(state.pkg, limits);
            state.contentFiles.addAll(layout.getContentOrder());
            state.contentFiles.addAll(layout.getXmlEntries());
            state.xmlFiles.addAll(layout.getXmlEntries());
            state.pass(Rule.RESOURCES, "已检查页面、模板、批注及资源间引用，内部依赖和资源类别一致。", entry);
        } catch (OfdhException error) {
            if ("PAGE_LAYOUT_UNSUPPORTED".equals(error.getCode())
                    || "LIMIT_EXCEEDED".equals(error.getCode())) {
                state.unknown(Rule.RESOURCES, "已完成基础资源检查，但绘制特性或依赖规模超出完整引用分析范围。", entry);
            } else {
                throw error;
            }
        }
    }

    private void registerResources(Element owner, String ownerPath, String kind)
            throws IOException {
        for (Element reference : descendants(owner, kind)) {
            String path = state.requireFile(ownerPath, reference.getTextContent());
            if (!resourceCatalogs.add(path)) continue;
            Element root = state.xml(path);
            if (!"Res".equals(root.getLocalName()) || !Xml.NS.equals(root.getNamespaceURI()))
                throw new IOException("资源根节点不合法。");
            String base = root.getAttribute("BaseLoc");
            String resourceOwner =
                    base.isEmpty()
                            ? path
                            : SafePackage.resolve(
                                    path, base + (base.endsWith("/") ? "" : "/") + "__resource__");
            for (Element element : elements(root)) {
                if (element.hasAttribute("ID")) {
                    String id = element.getAttribute("ID");
                    Element previous = resources.put(id, element);
                    if (!id.matches("[1-9][0-9]*") || previous != null && previous != element)
                        throw new IOException("资源标识重复。");
                }
                for (String member : Arrays.asList("FontFile", "MediaFile", "Profile")) {
                    Element file = child(element, member);
                    if (file != null) {
                        String target = state.requireFile(resourceOwner, file.getTextContent());
                        file.setUserData("packagePath", target, null);
                    }
                }
            }
        }
    }

    private void inspectResourceObjects() throws IOException {
        for (String pagePath : pages) {
            Element page = state.xml(pagePath);
            for (Element object : elements(page)) {
                if (object.hasAttribute("TemplateID")
                        && !templateIds.contains(object.getAttribute("TemplateID"))) {
                    throw new IOException("无法解析模板标识。");
                }
                for (String name : Arrays.asList("Font", "ResourceID", "DrawParam", "ColorSpace")) {
                    if (object.hasAttribute(name)
                            && !resources.containsKey(object.getAttribute(name))) {
                        throw new IOException("无法解析资源对象标识。");
                    }
                }
            }
        }
        state.pass(Rule.RESOURCES, "已登记的资源文件及受支持的页面对象引用均可解析。", "Doc_0");
        int fonts = 0;
        boolean unembedded = false;
        int images = 0;
        for (Element resource : resources.values()) {
            if ("Font".equals(resource.getLocalName())) {
                fonts++;
                Element fontFile = child(resource, "FontFile");
                if (fontFile == null) unembedded = true;
                else if (Files.size(state.pkg.path((String) fontFile.getUserData("packagePath")))
                        == 0) {
                    state.fail(Rule.FONTS, "嵌入的字体文件为空。", "Doc_0");
                }
            }
            if ("MultiMedia".equals(resource.getLocalName())
                    && "Image".equals(resource.getAttribute("Type"))) {
                images++;
                Element media = child(resource, "MediaFile");
                if (media == null
                        || !imageHeader(
                                (String) media.getUserData("packagePath"),
                                resource.getAttribute("Format"))) {
                    state.fail(Rule.IMAGES, "图像缺失，或其文件特征与声明的受支持格式不一致。", "Doc_0");
                }
            }
        }
        if (fonts == 0) state.absent(Rule.FONTS, "已检查的资源关系中未使用已登记字体。", "Doc_0");
        else if (unembedded) state.unknown(Rule.FONTS, "至少一个字体未嵌入；是否同时满足两项豁免条件需要外部证据。", "Doc_0");
        else state.pass(Rule.FONTS, "已登记字体均有非空内嵌文件；字形保真仍需渲染验证。", "Doc_0");
        if (images == 0) state.absent(Rule.IMAGES, "文档未登记图像资源。", "Doc_0");
        else state.pass(Rule.IMAGES, "图像资源已嵌入且格式特征可识别；完整解码和清晰度不在本项检查范围内。", "Doc_0");
    }

    private boolean imageHeader(String entry, String declared) throws IOException {
        byte[] prefix = new byte[16];
        int count = 0;
        try (InputStream input = Files.newInputStream(state.pkg.path(entry))) {
            while (count < prefix.length) {
                int n = input.read(prefix, count, prefix.length - count);
                if (n < 0) break;
                count += n;
            }
        }
        String format = "";
        if (count >= 8
                && prefix[0] == (byte) 137
                && prefix[1] == 80
                && prefix[2] == 78
                && prefix[3] == 71) format = "PNG";
        else if (count >= 3
                && prefix[0] == (byte) 255
                && prefix[1] == (byte) 216
                && prefix[2] == (byte) 255) format = "JPEG";
        else if (count >= 2 && prefix[0] == 66 && prefix[1] == 77) format = "BMP";
        else if (count >= 4
                && (prefix[0] == 73 && prefix[1] == 73 && prefix[2] == 42 && prefix[3] == 0
                        || prefix[0] == 77 && prefix[1] == 77 && prefix[2] == 0 && prefix[3] == 42))
            format = "TIFF";
        else if (count >= 12
                && prefix[4] == 106
                && prefix[5] == 80
                && prefix[6] == 32
                && prefix[7] == 32) format = "JPEG2000";
        else if (count >= 2 && prefix[0] == (byte) 255 && prefix[1] == 79) format = "JPEG2000";
        else if (count >= 8
                && prefix[0] == (byte) 151
                && prefix[1] == 74
                && prefix[2] == 66
                && prefix[3] == 50) format = "JBIG2";
        String expected = declared.replace("-", "").toUpperCase(Locale.ROOT);
        if ("JPG".equals(expected)) expected = "JPEG";
        if ("JP2".equals(expected)) expected = "JPEG2000";
        return !format.isEmpty() && (expected.isEmpty() || expected.equals(format));
    }

    private void inspectAttachments(Element document, String entry) throws IOException {
        Element reference = child(document, "Attachments");
        if (reference == null) {
            state.absent(Rule.ATTACHMENTS, "文档未声明附件。", entry);
            return;
        }
        try {
            String path = state.requireFile(entry, reference.getTextContent());
            Element root = state.xml(path);
            if (!"Attachments".equals(root.getLocalName())
                    || !Xml.NS.equals(root.getNamespaceURI())
                    || descendants(root, "Attachment").isEmpty())
                throw new IOException("附件根节点不合法。");
            if (!"Doc_0/Attachs/Attachments.xml".equals(path)) {
                state.fail(Rule.ATTACHMENTS, "附件目录未采用 OFD-H 规定的 Attachs 位置。", path);
            }
            Set<String> ids = new HashSet<>();
            boolean external = false;
            for (Element attachment : descendants(root, "Attachment")) {
                String id = attachment.getAttribute("ID");
                if (id.isEmpty() || !ids.add(id)) throw new IOException("附件标识重复。");
                String location = text(attachment, "FileLoc");
                if (location.contains(":")) {
                    external = true;
                    continue;
                }
                String content = state.requireFile(path, location);
                if (content.toLowerCase(Locale.ROOT).endsWith(".json")
                        || content.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    machineReadable.add(content);
                }
            }
            if (external) state.unknown(Rule.ATTACHMENTS, "内部附件引用可解析；外部引用未下载或验证。", path);
            else state.pass(Rule.ATTACHMENTS, "附件登记标识唯一，且可解析到内部文件。", path);
        } catch (IOException e) {
            FileSafety.cancelled();
            state.fail(Rule.ATTACHMENTS, "附件登记存在格式错误、重复或无法解析的引用。", entry);
        }
    }

    private void inspectEncoding() throws IOException {
        Set<String> entries = new HashSet<>(state.xmlFiles);
        entries.addAll(machineReadable);
        for (String entry : entries) {
            FileSafety.cancelled();
            try {
                if (machineReadable.contains(entry)
                        && Files.size(state.pkg.path(entry)) > limits.getMaxClinicalBytes()) {
                    state.unknown(Rule.ENCODING, "机读附件超过检查资源限额。", entry);
                    return;
                }
                String content = Inspection.utf8(state.pkg.read(entry));
                if (entry.toLowerCase(Locale.ROOT).endsWith(".json")) {
                    if (content.startsWith("\uFEFF")) content = content.substring(1);
                    if (json.readTree(content) == null) throw new IOException("JSON 附件为空。");
                } else {
                    Matcher declaration =
                            Pattern.compile(
                                            "^\\s*<\\?xml[^>]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]",
                                            Pattern.CASE_INSENSITIVE)
                                    .matcher(content);
                    if (declaration.find() && !"UTF-8".equalsIgnoreCase(declaration.group(1))) {
                        throw new IOException("XML 编码不是 UTF-8。");
                    }
                    Xml.parse(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                FileSafety.cancelled();
                state.fail(Rule.ENCODING, "机读文档的 UTF-8 编码或 XML、JSON 语法不正确。", entry);
                return;
            }
        }
        state.pass(Rule.ENCODING, "已检查的 OFD XML 及已登记的 XML、JSON 附件均通过严格 UTF-8 解码。", "");
    }

    private void inspectClinical() throws IOException {
        if (!hasMetadata("clinical.sha256")) {
            if (profileKnown
                    && (hasMetadata("clinical.schemaId")
                            || hasMetadata("clinical.schemaVersion"))) {
                state.fail(Rule.CLINICAL_DIGEST, "临床数据规范元数据不完整。", "OFD.xml");
            } else state.absent(Rule.CLINICAL_DIGEST, "未声明本项目临床附件摘要。", "OFD.xml");
            state.unknown(Rule.CLINICAL_ENVELOPE, "未声明受支持的临床附件绑定。", "OFD.xml");
            return;
        }
        String matched = null;
        for (String entry : machineReadable) {
            if (metadata("clinical.sha256").equals(FileSafety.sha256(state.pkg.path(entry)))) {
                if (matched != null) {
                    state.fail(Rule.CLINICAL_DIGEST, "临床摘要同时匹配多个已登记附件。", "OFD.xml");
                    return;
                }
                matched = entry;
            }
        }
        if (matched == null
                || !hasMetadata("clinical.schemaId")
                || !hasMetadata("clinical.schemaVersion")) {
            state.fail(Rule.CLINICAL_DIGEST, "声明的临床附件缺失、已被修改或描述不完整。", "OFD.xml");
            return;
        }
        state.pass(Rule.CLINICAL_DIGEST, "一个已登记的临床附件与声明的 SHA-256 摘要匹配。", matched);
        if (!matched.endsWith(".json")
                || Files.size(state.pkg.path(matched)) > limits.getMaxClinicalBytes()) {
            state.unknown(Rule.CLINICAL_ENVELOPE, "附件格式或大小超出 JSON 封套检查范围。", matched);
            return;
        }
        try {
            JsonNode root = json.readTree(Inspection.utf8(state.pkg.read(matched)));
            if (root == null
                    || !ClinicalEnvelopeProfile.ID.equals(root.at("/head/profile").asText())) {
                state.unknown(Rule.CLINICAL_ENVELOPE, "附件未明确选择受支持的项目封套，因此未推断第三方字段的业务含义。", matched);
                return;
            }
            if (!profileKnown) {
                state.unknown(Rule.CLINICAL_ENVELOPE, "文档元数据映射未知，无法确认封套一致性。", matched);
                return;
            }
            for (String pointer : ClinicalEnvelopeProfile.requiredTextFields()) {
                JsonNode value = root.at(pointer);
                if (!value.isTextual() || value.asText().trim().isEmpty()) {
                    state.fail(Rule.CLINICAL_ENVELOPE, "已选择封套的字段存在缺失或格式错误。", matched);
                    return;
                }
            }
            JsonNode keywordValues = root.at("/meta/keywords");
            if (!keywordValues.isArray() || keywordValues.isEmpty()) {
                state.fail(Rule.CLINICAL_ENVELOPE, "所选封套要求提供非空关键词数组。", matched);
                return;
            }
            Set<String> clinicalKeywords = new HashSet<>();
            for (JsonNode keyword : keywordValues) {
                if (!keyword.isTextual() || keyword.asText().trim().isEmpty()) {
                    state.fail(Rule.CLINICAL_ENVELOPE, "封套关键词必须是非空白字符串。", matched);
                    return;
                }
                clinicalKeywords.add(keyword.asText());
            }
            OffsetDateTime created = OffsetDateTime.parse(root.at("/head/createdAt").asText());
            OffsetDateTime modified = OffsetDateTime.parse(root.at("/head/modifiedAt").asText());
            if (!created.toInstant()
                            .equals(OffsetDateTime.parse(metadata("sourceCreatedAt")).toInstant())
                    || modified.toInstant().isBefore(created.toInstant())
                    || !clinicalKeywords.equals(keywords)) {
                state.fail(Rule.CLINICAL_ENVELOPE, "封套时间或关键词与声明的文档映射不一致。", matched);
                return;
            }
            Map<String, String> expected = new HashMap<>();
            expected.put("/head/version", metadata("clinical.schemaVersion"));
            expected.put("/head/schemaId", metadata("clinical.schemaId"));
            expected.put("/head/documentVersion", metadata("sourceVersion"));
            expected.put("/identifier/documentId", metadata("documentId"));
            expected.put("/patient/id", metadata("patient.id"));
            expected.put("/patient/authority", metadata("patient.authority"));
            expected.put("/identifier/documentType/code", metadata("documentType.code"));
            expected.put("/identifier/documentType/scheme", metadata("documentType.scheme"));
            expected.put("/identifier/confidentiality/code", metadata("confidentiality.code"));
            expected.put("/identifier/confidentiality/scheme", metadata("confidentiality.scheme"));
            expected.put("/meta/creator/id", metadata("responsibleParty.id"));
            expected.put("/meta/creator/name", author);
            expected.put("/meta/creator/authority", metadata("responsibleParty.authority"));
            expected.put("/meta/properties/sourceSystem", metadata("sourceSystem"));
            expected.put("/meta/properties/sourceRecordId", metadata("sourceRecordId"));
            for (Map.Entry<String, String> value : expected.entrySet()) {
                JsonNode actual = root.at(value.getKey());
                if (!actual.isTextual() || !value.getValue().equals(actual.asText())) {
                    state.fail(Rule.CLINICAL_ENVELOPE, "临床封套的标识或版本与文档元数据不一致。", matched);
                    return;
                }
            }
            state.pass(Rule.CLINICAL_ENVELOPE, "已选择的封套与文档元数据一致；本项不验证正文 Schema 或临床语义。", matched);
        } catch (IOException | DateTimeParseException e) {
            FileSafety.cancelled();
            state.fail(Rule.CLINICAL_ENVELOPE, "无法解析临床 JSON 封套。", matched);
        }
    }

    private boolean hasMetadata(String name) {
        return !metadata(name).isEmpty();
    }

    private String metadata(String name) {
        String value = state.metadata.get("openofdh." + name);
        return value == null ? "" : value;
    }
}
