package org.openofdh.core.io;

import org.openofdh.core.api.OfdhException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** 支持命名空间的 XML 读写；禁止外部资源和文档类型声明。 */
public final class Xml {
    public static final String NS = "http://www.ofdspec.org/2016";
    public static final long MAX_XML_BYTES = 16L << 20;

    private Xml() {}

    private static DocumentBuilder builder() throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver(
                    (publicId, systemId) -> {
                        throw new SAXException("禁止引用外部 XML 资源。");
                    });
            builder.setErrorHandler(
                    new ErrorHandler() {
                        public void warning(SAXParseException error) throws SAXException {
                            throw error;
                        }

                        public void error(SAXParseException error) throws SAXException {
                            throw error;
                        }

                        public void fatalError(SAXParseException error) throws SAXException {
                            throw error;
                        }
                    });
            return builder;
        } catch (Exception error) {
            throw new OfdhException("XML_SECURITY_CONFIGURATION", "无法配置安全的 XML 解析器。", error);
        }
    }

    public static Document newDocument() throws IOException {
        return builder().newDocument();
    }

    public static Document parse(Path path) throws IOException {
        if (Files.size(path) > MAX_XML_BYTES) {
            throw new OfdhException("LIMIT_EXCEEDED", "XML 成员超过解析资源限额。");
        }
        return parse(Files.readAllBytes(path));
    }

    public static Document parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_XML_BYTES) {
            throw new OfdhException("LIMIT_EXCEEDED", "XML 成员超过解析资源限额。");
        }
        try {
            checkDepth(bytes);
            return builder().parse(new ByteArrayInputStream(bytes));
        } catch (SAXException error) {
            if (error.getException() instanceof IOException) {
                throw (IOException) error.getException();
            }
            throw new OfdhException("XML_INVALID", "XML 格式不正确或使用了禁止的特性。", error);
        }
    }

    private static void checkDepth(byte[] bytes) throws IOException, SAXException {
        XMLReader reader;
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            reader = factory.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (Exception error) {
            throw new OfdhException("XML_SECURITY_CONFIGURATION", "无法配置 XML 深度检查器。", error);
        }
        DefaultHandler handler =
                new DefaultHandler() {
                    private int depth;

                    @Override
                    public void startElement(
                            String uri, String localName, String name, Attributes attributes)
                            throws SAXException {
                        try {
                            FileSafety.cancelled();
                            if (++depth > 128) {
                                throw new OfdhException("LIMIT_EXCEEDED", "XML 元素嵌套深度超过 128 层");
                            }
                        } catch (IOException error) {
                            throw new SAXException(error);
                        }
                    }

                    @Override
                    public void endElement(String uri, String localName, String name) {
                        depth--;
                    }

                    @Override
                    public void warning(SAXParseException error) throws SAXException {
                        throw error;
                    }

                    @Override
                    public void error(SAXParseException error) throws SAXException {
                        throw error;
                    }

                    @Override
                    public void fatalError(SAXParseException error) throws SAXException {
                        throw error;
                    }
                };
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.setEntityResolver(
                (publicId, systemId) -> {
                    throw new SAXException("禁止引用外部 XML 资源。");
                });
        // 在创建完整 DOM 前拒绝过深的嵌套，避免解析阶段过量分配内存。
        reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
    }

    public static byte[] serialize(Document document) throws IOException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception error) {
            throw new OfdhException("XML_WRITE_FAILED", "XML 序列化失败。", error);
        }
    }

    public static String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    public static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && (name == null || name.equals(localName(node)))) {
                result.add((Element) node);
            }
        }
        return result;
    }

    public static Element child(Element parent, String name) {
        for (Element element : children(parent, name)) return element;
        return null;
    }

    public static String text(Element parent, String name) {
        Element child = child(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    public static Element append(Element parent, String name, String value) {
        Element element = parent.getOwnerDocument().createElementNS(NS, "ofd:" + name);
        if (value != null) element.setTextContent(value);
        parent.appendChild(element);
        return element;
    }
}
