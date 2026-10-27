package org.openofdh.core.check;

import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Inspection {
    final SafePackage pkg;
    final CheckProfile profile;
    final Set<X509Certificate> trustRoots;
    final Map<String, String> metadata = new LinkedHashMap<>();
    final Set<String> contentFiles = new LinkedHashSet<>();
    final Set<String> xmlFiles = new LinkedHashSet<>();
    private final Map<Rule, CheckReport.Result> results = new EnumMap<>(Rule.class);
    CheckReport.SignatureVerification signatures =
            new CheckReport.SignatureVerification(
                    "NOT_CHECKED", null, Collections.<CheckReport.Signature>emptyList());

    Inspection(SafePackage pkg, CheckProfile profile, Set<X509Certificate> trustRoots) {
        this.pkg = pkg;
        this.profile = profile;
        this.trustRoots = trustRoots;
        for (Rule rule : Rule.values()) {
            unknown(rule, rule.implemented ? "前置结构检查未通过，无法执行本项检查。" : "本次检查尚未验证该要求，需要补充外部证据。", "");
        }
    }

    void pass(Rule rule, String message, String location) {
        set(rule, "PASS", message, location);
    }

    void fail(Rule rule, String message, String location) {
        set(rule, "FAIL", message, location);
    }

    void unknown(Rule rule, String message, String location) {
        set(rule, "NOT_CHECKED", message, location);
    }

    void absent(Rule rule, String message, String location) {
        set(rule, "NOT_APPLICABLE", message, location);
    }

    private void set(Rule rule, String result, String message, String location) {
        CheckReport.Result previous = results.get(rule);
        if (previous != null && "FAIL".equals(previous.getResult()) && !"FAIL".equals(result))
            return;
        results.put(rule, new CheckReport.Result(rule, result, message, location));
    }

    List<CheckReport.Result> results() {
        return new ArrayList<>(results.values());
    }

    Element xml(String entry) throws IOException {
        Element root = pkg.readXml(entry).getDocumentElement();
        xmlFiles.add(entry);
        contentFiles.add(entry);
        return root;
    }

    String requireFile(String owner, String reference) throws IOException {
        if (reference == null || reference.trim().isEmpty()) throw new IOException("缺少内部引用。");
        String target = SafePackage.resolve(owner, reference.trim());
        if (!pkg.contains(target)) throw new IOException("无法解析内部引用。");
        contentFiles.add(target);
        return target;
    }

    static List<Element> descendants(Element root, String name) {
        List<Element> values = new ArrayList<>();
        NodeList nodes = root.getElementsByTagNameNS(Xml.NS, name);
        for (int i = 0; i < nodes.getLength(); i++) values.add((Element) nodes.item(i));
        return values;
    }

    static List<Element> elements(Element root) {
        List<Element> values = new ArrayList<>();
        values.add(root);
        NodeList nodes = root.getElementsByTagNameNS(Xml.NS, "*");
        for (int i = 0; i < nodes.getLength(); i++) values.add((Element) nodes.item(i));
        return values;
    }

    static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element
                    && Xml.NS.equals(node.getNamespaceURI())
                    && name.equals(node.getLocalName())) return (Element) node;
        }
        return null;
    }

    static String text(Element parent, String name) {
        Element node = child(parent, name);
        return node == null ? "" : node.getTextContent().trim();
    }

    static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前环境不支持 SHA-256。", e);
        }
    }

    static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            output[i * 2] = alphabet[(bytes[i] >>> 4) & 15];
            output[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(output);
    }
}
