package org.openofdh.core.io;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.openofdh.core.api.OfdhException;

import java.nio.charset.StandardCharsets;

class XmlTest {
    @Test
    void acceptsTheDepthBoundaryAndRejectsOneMoreElement() throws Exception {
        assertEquals("x", Xml.parse(nested(128)).getDocumentElement().getTagName());
        OfdhException failure = assertThrows(OfdhException.class, () -> Xml.parse(nested(129)));
        assertEquals("LIMIT_EXCEEDED", failure.getCode());
        assertEquals("XML 元素嵌套深度超过 128 层", failure.getMessage());
    }

    @Test
    void rejectsDeepXmlBeforeReadingItsMalformedTail() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 4096; i++) input.append("<x>");
        input.append("<");
        assertEquals(
                "LIMIT_EXCEEDED",
                assertThrows(
                                OfdhException.class,
                                () -> Xml.parse(input.toString().getBytes(StandardCharsets.UTF_8)))
                        .getCode());
    }

    @Test
    void rejectsDoctypeEvenWithoutEntityReferences() {
        byte[] input =
                "<!DOCTYPE x SYSTEM 'file:///unavailable'><x/>".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "XML_INVALID", assertThrows(OfdhException.class, () -> Xml.parse(input)).getCode());
    }

    @Test
    void retainsDeclaredEncodingAndNamespace() throws Exception {
        byte[] input =
                ("<?xml version='1.0' encoding='UTF-16'?><a:x xmlns:a='urn:validation'>中文</a:x>")
                        .getBytes(StandardCharsets.UTF_16);
        org.w3c.dom.Element root = Xml.parse(input).getDocumentElement();
        assertEquals("urn:validation", root.getNamespaceURI());
        assertEquals("中文", root.getTextContent());
    }

    @Test
    void rejectsMalformedXmlWithinTheDepthBudget() {
        byte[] input = "<x><y></x>".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "XML_INVALID", assertThrows(OfdhException.class, () -> Xml.parse(input)).getCode());
    }

    private static byte[] nested(int depth) {
        StringBuilder input = new StringBuilder(depth * 7);
        for (int i = 0; i < depth; i++) input.append("<x>");
        for (int i = 0; i < depth; i++) input.append("</x>");
        return input.toString().getBytes(StandardCharsets.UTF_8);
    }
}
