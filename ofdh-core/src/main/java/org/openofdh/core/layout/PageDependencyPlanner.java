package org.openofdh.core.layout;

import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.XMLConstants;

/** 根据 OFD 的页面和资源引用安排 ZIP 成员，不实现新的文件格式或线性化协议。 */
public final class PageDependencyPlanner {
    private static final int MAX_DEPENDENCY_DEPTH = 128;
    private static final Set<String> RESOURCE_KINDS =
            names("Font", "MultiMedia", "DrawParam", "ColorSpace", "CompositeGraphicUnit");
    private static final Set<String> RESOURCE_CONTAINERS =
            names("Fonts", "MultiMedias", "DrawParams", "ColorSpaces", "CompositeGraphicUnits");
    private static final Set<String> RESOURCE_ATTRIBUTES =
            names(
                    "Font",
                    "ResourceID",
                    "DrawParam",
                    "ColorSpace",
                    "Relative",
                    "Substitution",
                    "ImageMask",
                    "Thumbnail");
    private static final Set<String> BINARY_ELEMENTS =
            names("FontFile", "MediaFile", "Profile", "ColorProfile");
    private static final Set<String> DRAWING_ELEMENTS =
            names(
                    "Page",
                    "Area",
                    "PhysicalBox",
                    "ApplicationBox",
                    "ContentBox",
                    "BleedBox",
                    "Template",
                    "PageRes",
                    "Content",
                    "Layer",
                    "PageBlock",
                    "TextObject",
                    "Text",
                    "TextCode",
                    "CGTransform",
                    "Glyphs",
                    "PathObject",
                    "Path",
                    "AbbreviatedData",
                    "ImageObject",
                    "CompositeObject",
                    "Border",
                    "BorderColor",
                    "FillColor",
                    "StrokeColor",
                    "Color",
                    "Clips",
                    "Clip",
                    "Pattern",
                    "CellContent",
                    "AxialShd",
                    "RadialShd",
                    "GouraudShd",
                    "LaGouraudShd",
                    "Segment",
                    "Point",
                    "BackColor",
                    "Actions",
                    "Action",
                    "Goto",
                    "GotoA",
                    "URI",
                    "Dest",
                    "Bookmark",
                    "Sound",
                    "Movie",
                    "Appearance",
                    "Font",
                    "FontFile",
                    "MultiMedia",
                    "MediaFile",
                    "DrawParam",
                    "ColorSpace",
                    "Profile",
                    "ColorProfile",
                    "Palette",
                    "CV",
                    "CompositeGraphicUnit",
                    "Thumbnail",
                    "Substitution");

    private final SafePackage archive;
    private final ResourceLimits limits;
    private final Map<String, Element> xml = new HashMap<>();
    private final Map<String, Node> graph = new LinkedHashMap<>();
    private final Map<String, Resource> resources = new LinkedHashMap<>();
    private final Map<String, String> requiredResourceKinds = new LinkedHashMap<>();
    private final List<Resource> resourceDefinitions = new ArrayList<>();
    private final Map<String, String> templates = new LinkedHashMap<>();
    private final Map<String, String> pages = new LinkedHashMap<>();
    private final Set<String> catalogs = new HashSet<>();
    private final Set<String> parsedDrawings = new HashSet<>();
    private final Map<String, String> pendingDrawings = new LinkedHashMap<>();
    private final LinkedHashSet<String> startup = new LinkedHashSet<>();
    private String document;

    private PageDependencyPlanner(SafePackage archive, ResourceLimits limits) {
        this.archive = Objects.requireNonNull(archive, "文件包不能为空");
        this.limits = Objects.requireNonNull(limits, "资源限额不能为空");
    }

    public static PageLayout plan(SafePackage archive, ResourceLimits limits) throws IOException {
        return new PageDependencyPlanner(archive, limits).build();
    }

    private PageLayout build() throws IOException {
        FileSafety.cancelled();
        if (archive.entries().size() > limits.getMaxEntries())
            throw new OfdhException("LIMIT_EXCEEDED", "文件包成员数量超过布局分析限额。");
        Element root = root("OFD.xml", "OFD");
        if (!"1.0".equals(root.getAttribute("Version"))) throw unsupported("仅支持 OFD 1.0 的页面布局分析。");
        List<Element> bodies = Xml.children(root, "DocBody");
        if (bodies.size() != 1) throw unsupported("页面布局分析仅支持单文档 OFD 文件包。");
        Element body = bodies.get(0);
        requireName(body, "DocBody");
        if (Xml.child(body, "Encryptions") != null || Xml.child(root, "Encryptions") != null)
            throw unsupported("加密文档不能进行页面依赖分析。");
        document = reference("OFD.xml", requiredText(body, "DocRoot"));
        Element doc = root(document, "Document");
        startup.add("OFD.xml");
        startup.add(document);
        if (Xml.child(doc, "Extensions") != null) throw unsupported("文档包含扩展入口，无法确定扩展的页面依赖。");
        Element common = requiredChild(doc, "CommonData");
        registerCatalogs(common, document, "PublicRes", startup);
        registerCatalogs(common, document, "DocumentRes", startup);
        for (Element template : Xml.children(common, "TemplatePage")) {
            requireName(template, "TemplatePage");
            String id = id(template.getAttribute("ID"));
            String path = reference(document, template.getAttribute("BaseLoc"));
            if (templates.put(id, path) != null) throw invalid("模板标识重复。");
            root(path, "Page");
        }
        for (Element page : Xml.children(requiredChild(doc, "Pages"), null)) {
            requireName(page, "Page");
            String id = id(page.getAttribute("ID"));
            String path = reference(document, page.getAttribute("BaseLoc"));
            if (pages.containsValue(path) || pages.put(id, path) != null)
                throw invalid("页面标识或页面文件路径重复。");
            if (pages.size() > limits.getMaxPages())
                throw new OfdhException("LIMIT_EXCEEDED", "页面数量超过布局分析限额。");
            root(path, "Page");
        }
        if (pages.isEmpty()) throw invalid("文档未登记页面。");
        for (String path : templates.values()) drawing(path, "Page");
        for (String path : pages.values()) drawing(path, "Page");
        directories(doc);
        Element info = Xml.child(body, "DocInfo");
        if (info != null && Xml.child(info, "Cover") != null)
            startup.add(reference("OFD.xml", requiredText(info, "Cover")));
        if (Xml.child(body, "Signatures") != null)
            reference("OFD.xml", requiredText(body, "Signatures"));
        Element actions = Xml.child(doc, "Actions");
        if (actions != null) walk(actions, document, file(document));
        String defaultColor = Xml.text(common, "DefaultCS");
        if (defaultColor != null) resourceEdge(file(document), defaultColor, "ColorSpace");

        // 先登记全部资源，再解析资源间引用，允许前向引用但拒绝循环。
        int resourceIndex = 0;
        while (true) {
            while (!pendingDrawings.isEmpty()) {
                String path = pendingDrawings.keySet().iterator().next();
                String kind = pendingDrawings.remove(path);
                walk(root(path, kind), path, file(path));
            }
            if (resourceIndex == resourceDefinitions.size()) break;
            Resource resource = resourceDefinitions.get(resourceIndex++);
            walk(resource.element, resource.owner, graph.get(resource.key));
        }
        for (Map.Entry<String, String> use : requiredResourceKinds.entrySet()) {
            Resource resource = resources.get(use.getKey());
            if (resource == null || !use.getValue().equals(Xml.localName(resource.element)))
                throw invalid("页面引用的资源不存在或资源类别不匹配。");
        }
        validateGraph();
        LinkedHashSet<String> opening = new LinkedHashSet<>();
        for (String path : startup) expand(file(path), new HashSet<>(), opening);
        List<PageLayout.Page> result = new ArrayList<>();
        for (Map.Entry<String, String> page : pages.entrySet()) {
            LinkedHashSet<String> entries = new LinkedHashSet<>();
            expand(file(page.getValue()), new HashSet<>(), entries);
            result.add(
                    new PageLayout.Page(page.getKey(), page.getValue(), new ArrayList<>(entries)));
        }
        return new PageLayout(
                document, new ArrayList<>(opening), result, archive.entries(), xml.keySet());
    }

    private void directories(Element documentRoot) throws IOException {
        for (String kind : Arrays.asList("Attachments", "CustomTags", "Annotations")) {
            Element entry = Xml.child(documentRoot, kind);
            if (entry == null) continue;
            String path = reference(document, entry.getTextContent().trim());
            Element directory = root(path, kind);
            startup.add(path);
            if ("Annotations".equals(kind)) {
                Set<String> annotatedPages = new HashSet<>();
                for (Element page : Xml.children(directory, null)) {
                    requireName(page, "Page");
                    String pageId = id(page.getAttribute("PageID"));
                    if (!pages.containsKey(pageId) || !annotatedPages.add(pageId))
                        throw invalid("批注目录的页面标识缺失、重复或不存在。");
                    String annotations = reference(path, requiredText(page, "FileLoc"));
                    Element annotationRoot = root(annotations, "PageAnnot");
                    Node dependency = file(annotations);
                    file(pages.get(pageId)).edges.add(dependency.key);
                    for (Element annotation : Xml.children(annotationRoot, null)) {
                        requireName(annotation, "Annot");
                        Element appearance = Xml.child(annotation, "Appearance");
                        if (appearance != null) walk(appearance, annotations, dependency);
                    }
                }
            } else {
                for (Element item : Xml.children(directory, null)) {
                    requireName(item, "Attachments".equals(kind) ? "Attachment" : "CustomTag");
                    for (String field : Arrays.asList("FileLoc", "SchemaLoc")) {
                        Element location = Xml.child(item, field);
                        if (location != null)
                            nonRenderingReference(path, location.getTextContent().trim());
                    }
                }
            }
        }
    }

    private void drawing(String path, String expectedRoot) throws IOException {
        root(path, expectedRoot);
        file(path);
        if (parsedDrawings.add(path)) pendingDrawings.put(path, expectedRoot);
    }

    private void registerCatalogs(Element owner, String ownerPath, String kind, Set<String> paths)
            throws IOException {
        for (Element location : Xml.children(owner, kind)) {
            requireName(location, kind);
            String path = reference(ownerPath, location.getTextContent().trim());
            paths.add(path);
            registerCatalog(path);
        }
    }

    private void registerCatalog(String path) throws IOException {
        if (!catalogs.add(path)) return;
        Element root = root(path, "Res");
        file(path);
        String base = root.getAttribute("BaseLoc");
        String owner =
                base.isEmpty()
                        ? path
                        : resolve(path, base + (base.endsWith("/") ? "" : "/") + "__resource__");
        for (Element container : Xml.children(root, null)) {
            if (!Xml.NS.equals(container.getNamespaceURI())
                    || !RESOURCE_CONTAINERS.contains(Xml.localName(container)))
                throw unsupported("资源目录包含不支持的资源类别。");
            for (Element element : Xml.children(container, null)) {
                if (!Xml.NS.equals(element.getNamespaceURI())
                        || !RESOURCE_KINDS.contains(Xml.localName(element)))
                    throw unsupported("资源目录包含不支持的资源对象。");
                if (!(Xml.localName(element) + "s").equals(Xml.localName(container)))
                    throw invalid("资源对象与所在资源目录的类别不匹配。");
                String id = id(element.getAttribute("ID"));
                String key = "resource:" + id;
                Resource resource = new Resource(key, owner, element);
                if (resources.put(id, resource) != null) throw invalid("资源标识重复。");
                resourceDefinitions.add(resource);
                graph.put(key, new Node(key, path));
            }
        }
    }

    private void walk(Element root, String owner, Node node) throws IOException {
        ArrayDeque<Element> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Element element = pending.pop();
            if (!walkElement(element, owner, node)) continue;
            List<Element> children = Xml.children(element, null);
            for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
        }
    }

    private boolean walkElement(Element element, String owner, Node node) throws IOException {
        FileSafety.cancelled();
        String name = Xml.localName(element);
        if (!Xml.NS.equals(element.getNamespaceURI()) || !DRAWING_ELEMENTS.contains(name))
            throw unsupported("页面或资源包含无法分析的绘制元素：" + name);
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            String namespace = attributes.item(i).getNamespaceURI();
            if (namespace != null
                    && !XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)
                    && !XMLConstants.XML_NS_URI.equals(namespace))
                throw unsupported("绘制对象包含无法分析的命名空间扩展属性。");
        }
        for (String attribute : RESOURCE_ATTRIBUTES)
            if (element.hasAttribute(attribute))
                resourceEdge(node, element.getAttribute(attribute), resourceKind(name, attribute));
        if (element.hasAttribute("TemplateID")) {
            String path = templates.get(id(element.getAttribute("TemplateID")));
            if (path == null) throw invalid("页面引用的模板不存在。");
            node.edges.add(file(path).key);
        }
        if (element.hasAttribute("Profile"))
            node.edges.add(file(reference(owner, element.getAttribute("Profile"))).key);
        if ("PageRes".equals(name)) {
            String path = reference(owner, element.getTextContent().trim());
            registerCatalog(path);
            node.edges.add(file(path).key);
            return false;
        }
        if (BINARY_ELEMENTS.contains(name)) {
            node.edges.add(file(reference(owner, element.getTextContent().trim())).key);
            return false;
        }
        if ("Thumbnail".equals(name) || "Substitution".equals(name)) {
            resourceEdge(node, element.getTextContent().trim(), "MultiMedia");
            return false;
        }
        if ("Layer".equals(name)) {
            String location = element.getAttribute("BaseLoc");
            if (location.isEmpty() && Xml.children(element, null).isEmpty())
                location = element.getTextContent().trim();
            if (!location.isEmpty()) {
                if (!Xml.children(element, null).isEmpty()) throw invalid("外链图层不能同时包含内嵌绘制内容。");
                String path = reference(owner, location);
                node.edges.add(file(path).key);
                drawing(path, "Layer");
                return false;
            }
        } else if (element.hasAttribute("BaseLoc")) {
            throw unsupported("绘制对象包含无法分析的外链路径。");
        }
        return true;
    }

    private void resourceEdge(Node node, String value, String kind) throws IOException {
        String id = id(value);
        node.edges.add("resource:" + id);
        String previous = requiredResourceKinds.put(id, kind);
        if (previous != null && !previous.equals(kind)) throw invalid("同一资源标识被用于不兼容的资源类别。");
    }

    private static String resourceKind(String element, String attribute) {
        if ("Font".equals(attribute)) return "Font";
        if ("DrawParam".equals(attribute) || "Relative".equals(attribute)) return "DrawParam";
        if ("ColorSpace".equals(attribute)) return "ColorSpace";
        if ("ResourceID".equals(attribute) && "CompositeObject".equals(element))
            return "CompositeGraphicUnit";
        return "MultiMedia";
    }

    private void validateGraph() throws IOException {
        Map<String, Integer> heights = new HashMap<>();
        Set<String> visiting = new HashSet<>();
        for (Node node : graph.values()) validate(node, heights, visiting, 0);
    }

    private int validate(Node node, Map<String, Integer> heights, Set<String> visiting, int depth)
            throws IOException {
        FileSafety.cancelled();
        if (heights.containsKey(node.key)) return heights.get(node.key);
        if (!visiting.add(node.key)) throw invalid("页面、模板或资源之间存在循环引用。");
        if (depth >= MAX_DEPENDENCY_DEPTH)
            throw new OfdhException("LIMIT_EXCEEDED", "页面依赖深度超过布局分析限额。");
        int height = 0;
        for (String key : node.edges) {
            Node next = graph.get(key);
            if (next == null) throw invalid("页面或资源引用的资源标识不存在。");
            height = Math.max(height, validate(next, heights, visiting, depth + 1) + 1);
        }
        if (height >= MAX_DEPENDENCY_DEPTH)
            throw new OfdhException("LIMIT_EXCEEDED", "页面依赖深度超过布局分析限额。");
        visiting.remove(node.key);
        heights.put(node.key, height);
        return height;
    }

    private void expand(Node node, Set<String> visited, Set<String> entries) throws IOException {
        FileSafety.cancelled();
        if (!visited.add(node.key)) return;
        entries.add(node.entry);
        for (String key : node.edges) expand(graph.get(key), visited, entries);
    }

    private Node file(String path) {
        String key = "file:" + path;
        Node node = graph.get(key);
        if (node == null) {
            node = new Node(key, path);
            graph.put(key, node);
        }
        return node;
    }

    private Element root(String path, String name) throws IOException {
        Element element = xml.get(path);
        if (element == null) {
            if (!archive.contains(path)) throw invalid("引用的包成员不存在：" + path);
            element = archive.readXml(path).getDocumentElement();
            xml.put(path, element);
        }
        requireName(element, name);
        return element;
    }

    private static void requireName(Element element, String name) throws IOException {
        if (!Xml.NS.equals(element.getNamespaceURI()) || !name.equals(Xml.localName(element)))
            throw invalid("OFD 目录或内容元素不符合预期：" + name);
    }

    private String reference(String owner, String value) throws IOException {
        String path = resolve(owner, value);
        if (!archive.contains(path)) throw invalid("引用的包成员不存在：" + path);
        return path;
    }

    private void nonRenderingReference(String owner, String value) throws IOException {
        if (value.indexOf(':') >= 0) {
            try {
                URI uri = new URI(value);
                if (uri.isAbsolute() && !value.matches("^[A-Za-z]:[/\\\\].*")) {
                    // 外部附件和标引链接不属于包内绘制依赖，保留原文且不下载。
                    return;
                }
            } catch (URISyntaxException error) {
                throw new OfdhException("PAGE_LAYOUT_INVALID", "附件或标引的外部链接格式不正确。", error);
            }
        }
        reference(owner, value);
    }

    private static String resolve(String owner, String value) throws IOException {
        try {
            return SafePackage.resolve(owner, value);
        } catch (OfdhException error) {
            throw new OfdhException("PAGE_LAYOUT_INVALID", "文档含有非法或外部资源路径。", error);
        }
    }

    private static Element requiredChild(Element parent, String name) throws IOException {
        List<Element> children = Xml.children(parent, name);
        if (children.size() != 1) throw invalid("必需的 OFD 目录缺失或重复：" + name);
        requireName(children.get(0), name);
        return children.get(0);
    }

    private static String requiredText(Element parent, String name) throws IOException {
        return requiredChild(parent, name).getTextContent().trim();
    }

    private static String id(String value) throws IOException {
        if (!value.matches("[1-9][0-9]{0,9}") || Long.parseLong(value) > 0xffffffffL)
            throw invalid("页面、模板或资源标识必须是有效的正整数。");
        return value;
    }

    private static Set<String> names(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static OfdhException invalid(String message) {
        return new OfdhException("PAGE_LAYOUT_INVALID", message);
    }

    private static OfdhException unsupported(String message) {
        return new OfdhException("PAGE_LAYOUT_UNSUPPORTED", message);
    }

    private static final class Node {
        final String key;
        final String entry;
        final Set<String> edges = new LinkedHashSet<>();

        Node(String key, String entry) {
            this.key = key;
            this.entry = entry;
        }
    }

    private static final class Resource {
        final String key;
        final String owner;
        final Element element;

        Resource(String key, String owner, Element element) {
            this.key = key;
            this.owner = owner;
            this.element = element;
        }
    }
}
