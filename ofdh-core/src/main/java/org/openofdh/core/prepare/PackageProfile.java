package org.openofdh.core.prepare;

import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class PackageProfile {
    private static final Set<String> XML_REFERENCES =
            new HashSet<>(
                    Arrays.asList(
                            "DocRoot",
                            "PublicRes",
                            "DocumentRes",
                            "PageRes",
                            "Annotations",
                            "Attachments",
                            "CustomTags"));
    private static final Set<String> BINARY_REFERENCES =
            new HashSet<>(
                    Arrays.asList(
                            "FontFile",
                            "MediaFile",
                            "ColorProfile",
                            "FileLoc",
                            "Profile",
                            "Cover"));
    private final SafePackage archive;
    private final Set<String> visited = new HashSet<>();
    private final Set<String> visiting = new HashSet<>();
    private long maximumId;

    PackageProfile(SafePackage archive) {
        this.archive = archive;
    }

    long validate(ResourceLimits limits) throws IOException {
        for (String name : archive.entries()) {
            String leaf = name.substring(name.lastIndexOf('/') + 1);
            if (leaf.equalsIgnoreCase("Encryptions.xml")) {
                throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "不能处理加密 OFD 文档");
            }
            if (leaf.equalsIgnoreCase("Signatures.xml") || leaf.equalsIgnoreCase("Signature.xml")) {
                throw new OfdhException("SIGNED_INPUT_REQUIRES_DERIVATION", "不能改写已签署的来源文档");
            }
            if (name.startsWith("Doc_") && !name.startsWith("Doc_0/")) {
                throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "仅支持以 Doc_0 为根的单文档结构");
            }
        }
        Document entry = archive.readXml("OFD.xml");
        Element root = entry.getDocumentElement();
        if (!Xml.NS.equals(root.getNamespaceURI())
                || !"OFD".equals(Xml.localName(root))
                || !"1.0".equals(root.getAttribute("Version"))) {
            throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "不支持该 OFD 命名空间或版本");
        }
        java.util.List<Element> bodies = Xml.children(root, "DocBody");
        if (bodies.size() != 1)
            throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "必须且只能包含一个 DocBody");
        Element body = bodies.get(0);
        if (Xml.child(body, "Signatures") != null) {
            throw new OfdhException("SIGNED_INPUT_REQUIRES_DERIVATION", "不能改写已签署的来源文档");
        }
        if (!"Doc_0/Document.xml"
                .equals(SafePackage.resolve("OFD.xml", Xml.text(body, "DocRoot")))) {
            throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "文档根路径必须为 Doc_0/Document.xml");
        }
        Document document = archive.readXml("Doc_0/Document.xml");
        Element documentRoot = document.getDocumentElement();
        if (!"Document".equals(Xml.localName(documentRoot))
                || !Xml.NS.equals(documentRoot.getNamespaceURI())) {
            throw new OfdhException("OFD_STRUCTURE_INVALID", "Document.xml 的根元素无效");
        }
        Element common = Xml.child(documentRoot, "CommonData");
        Element pages = Xml.child(documentRoot, "Pages");
        if (Xml.children(documentRoot, "CommonData").size() != 1
                || Xml.children(documentRoot, "Pages").size() != 1
                || common == null
                || pages == null
                || Xml.children(pages, "Page").isEmpty()) {
            throw new OfdhException("OFD_STRUCTURE_INVALID", "文档必须包含 CommonData 和页面");
        }
        if (Xml.children(pages, "Page").size() > limits.getMaxPages()) {
            throw new OfdhException("LIMIT_EXCEEDED", "文档页数超出限额");
        }
        maximumId = parseId(Xml.text(common, "MaxUnitID"));
        visit("OFD.xml");
        return maximumId;
    }

    private void visit(String name) throws IOException {
        if (visiting.contains(name)) throw new OfdhException("REFERENCE_CYCLE", "OFD 引用关系存在循环");
        if (visited.contains(name)) return;
        if (visiting.size() > 128) throw new OfdhException("LIMIT_EXCEEDED", "OFD 引用深度超出限额");
        visiting.add(name);
        Element root = archive.readXml(name).getDocumentElement();
        String resourceOwner = name;
        if ("Res".equals(Xml.localName(root)) && root.hasAttribute("BaseLoc")) {
            resourceOwner =
                    SafePackage.resolve(name, root.getAttribute("BaseLoc") + "/__resource__");
        }
        inspect(root, name, resourceOwner, "Annotations".equals(Xml.localName(root)));
        visiting.remove(name);
        visited.add(name);
    }

    private void inspect(
            Element element, String owner, String resourceOwner, boolean annotationCatalog)
            throws IOException {
        if (!Xml.NS.equals(element.getNamespaceURI())) {
            throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "当前来源格式不支持外部命名空间的 XML 扩展");
        }
        String name = Xml.localName(element);
        if ("Signatures".equals(name) || "Signature".equals(name)) {
            throw new OfdhException("SIGNED_INPUT_REQUIRES_DERIVATION", "不能改写已签署的来源文档");
        }
        if ("Encryptions".equals(name) || "JavaScript".equals(name) || "Launch".equals(name)) {
            throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "文档包含不支持的受保护对象或活动对象");
        }
        if (element.hasAttribute("ID") && !"Attachment".equals(name)) {
            maximumId = Math.max(maximumId, parseId(element.getAttribute("ID")));
        }
        if ("Font".equals(name) && Xml.child(element, "FontFile") == null) {
            throw new OfdhException("FONT_UNRESOLVED", "当前来源格式要求字体内嵌");
        }
        if (XML_REFERENCES.contains(name) && Xml.children(element, null).isEmpty()) {
            visit(SafePackage.resolve(owner, element.getTextContent().trim()));
        } else if (("Page".equals(name) || "TemplatePage".equals(name))
                && element.hasAttribute("BaseLoc")) {
            visit(SafePackage.resolve(owner, element.getAttribute("BaseLoc")));
        } else if (BINARY_REFERENCES.contains(name) && Xml.children(element, null).isEmpty()) {
            String location = SafePackage.resolve(resourceOwner, element.getTextContent().trim());
            archive.path(location);
            if ("FileLoc".equals(name) && annotationCatalog) visit(location);
        }
        for (Element child : Xml.children(element, null))
            inspect(child, owner, resourceOwner, annotationCatalog);
    }

    private static long parseId(String value) throws IOException {
        try {
            long id = Long.parseLong(value);
            if (id < 0 || id > 0xffffffffL) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException error) {
            throw new OfdhException("OFD_STRUCTURE_INVALID", "OFD 图元标识无效", error);
        }
    }
}
