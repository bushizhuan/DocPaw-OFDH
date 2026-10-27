package org.openofdh.pdf;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

final class PdfPreflight {
    private final ResourceLimits limits;
    private long expanded;
    private final Set<COSDictionary> links =
            Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());
    private final Set<COSDictionary> opaquePageGroups =
            Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());

    PdfPreflight(ResourceLimits limits) {
        this.limits = limits;
    }

    void inspect(PDDocument document) throws IOException {
        if (document.isEncrypted()) {
            unsupported("不支持加密 PDF");
        }
        COSBase pageTree =
                document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.PAGES);
        if (!(pageTree instanceof COSDictionary)) {
            unsupported("PDF 缺少有效的页树");
        }
        int pages =
                inspectPageTree(
                        (COSDictionary) pageTree,
                        null,
                        Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>()),
                        0);
        if (pages == 0) exceeded("PDF 页数不在允许范围内");
        if (!document.getSignatureDictionaries().isEmpty()) {
            throw new OfdhException("SIGNED_INPUT_REQUIRES_DERIVATION", "不能改写已签署的 PDF");
        }
        if (document.getDocument().getXrefTable().size() > limits.getMaxEntries()) {
            exceeded("PDF 对象数量超出限额");
        }
        for (PDPage page : document.getPages()) {
            PDRectangle box = page.getCropBox();
            if (!Float.isFinite(box.getWidth())
                    || !Float.isFinite(box.getHeight())
                    || box.getWidth() <= 0
                    || box.getHeight() <= 0
                    || box.getWidth() > 14400
                    || box.getHeight() > 14400) {
                unsupported("PDF 页面尺寸超出支持范围");
            }
            if (page.getUserUnit() != 1f) {
                unsupported("PDF 的 UserUnit 必须为 1");
            }
            COSBase group = page.getCOSObject().getDictionaryObject(COSName.GROUP);
            if (group instanceof COSDictionary) {
                COSDictionary attributes = (COSDictionary) group;
                boolean defaultsOnly = true;
                for (COSName name : attributes.keySet()) {
                    defaultsOnly &= COSName.S.equals(name) || COSName.TYPE.equals(name);
                }
                if (defaultsOnly) opaquePageGroups.add(attributes);
            }
            for (PDAnnotation annotation : page.getAnnotations()) {
                PdfLinks.validate(document, annotation);
                links.add(annotation.getCOSObject());
            }
        }
        inspectObjects(document.getDocument().getTrailer());
    }

    private int inspectPageTree(
            COSDictionary node, COSDictionary parent, Set<COSDictionary> seen, int depth)
            throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new OfdhException("OPERATION_CANCELLED", "PDF 检查已中断");
        }
        if (depth > 64 || !seen.add(node)) {
            unsupported("PDF 页树存在循环、重复节点或嵌套层数超出限额");
        }
        if (seen.size() > limits.getMaxEntries()) exceeded("PDF 页树节点数量超出限额");
        if (node.getDictionaryObject(COSName.PARENT) != parent) {
            unsupported("PDF 页树的父子引用不一致");
        }
        if (parent != null && COSName.PAGE.equals(node.getCOSName(COSName.TYPE))) {
            if (node.containsKey(COSName.KIDS)) unsupported("PDF 叶子页面不能包含子节点");
            return 1;
        }
        COSBase kids = node.getDictionaryObject(COSName.KIDS);
        COSBase declared = node.getDictionaryObject(COSName.COUNT);
        if (!COSName.PAGES.equals(node.getCOSName(COSName.TYPE))
                || !(kids instanceof COSArray)
                || !(declared instanceof COSInteger)) {
            unsupported("PDF 页树节点必须声明有效的类型、子节点和整数页数");
        }
        long pages = 0;
        for (int index = 0; index < ((COSArray) kids).size(); index++) {
            COSBase child = ((COSArray) kids).getObject(index);
            if (!(child instanceof COSDictionary)) unsupported("PDF 页树包含无效子节点");
            pages += inspectPageTree((COSDictionary) child, node, seen, depth + 1);
            if (pages > limits.getMaxPages()) exceeded("PDF 实际页数超出限额");
        }
        if (((COSInteger) declared).longValue() != pages) {
            // PDFBox 按 Count 索引页面；容忍错误计数会导致后续转换跳页或丢页。
            unsupported("PDF 页树声明页数与实际页面数量不一致");
        }
        return (int) pages;
    }

    private void inspectObjects(COSBase root) throws IOException {
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
        Deque<COSBase> queue = new ArrayDeque<>();
        queue.add(root);
        long visited = 0;
        byte[] buffer = new byte[8192];
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new OfdhException("OPERATION_CANCELLED", "PDF 检查已中断");
            }
            COSBase value = queue.removeFirst();
            if (value instanceof COSObject) {
                value = ((COSObject) value).getObject();
            }
            if (value == null || !seen.add(value)) {
                continue;
            }
            if (++visited > (long) limits.getMaxEntries() * 64) {
                exceeded("PDF 对象引用图超出限额");
            }
            if (value instanceof COSDictionary) {
                COSDictionary dictionary = (COSDictionary) value;
                inspectDictionary(dictionary);
                if (value instanceof COSStream) {
                    try (InputStream stream = ((COSStream) value).createInputStream()) {
                        int count;
                        while ((count = stream.read(buffer)) != -1) {
                            expanded += count;
                            if (expanded > limits.getMaxExpandedBytes()) {
                                exceeded("PDF 解码流总大小超出限额");
                            }
                        }
                    }
                }
                for (Map.Entry<COSName, COSBase> entry : dictionary.entrySet()) {
                    if (entry.getValue() != null) {
                        queue.addLast(entry.getValue());
                    }
                }
            } else if (value instanceof COSArray) {
                for (COSBase item : (COSArray) value) {
                    if (item != null) {
                        queue.addLast(item);
                    }
                }
            }
        }
    }

    private void inspectDictionary(COSDictionary dictionary) throws IOException {
        inspectTextReplacement(dictionary);
        String[] active = {
            "AA",
            "OpenAction",
            "JavaScript",
            "JS",
            "Launch",
            "RichMedia",
            "EmbeddedFiles",
            "EF",
            "AF",
            "XFA",
            "OCProperties",
            "OC"
        };
        for (String name : active) {
            if (dictionary.getDictionaryObject(COSName.getPDFName(name)) != null) {
                unsupported("PDF 包含活动内容或分层内容：" + name);
            }
        }
        if (dictionary.containsKey(COSName.A) && !links.contains(dictionary)) {
            unsupported("不支持链接注释之外的 PDF 动作");
        }
        COSBase form = dictionary.getDictionaryObject(COSName.ACRO_FORM);
        if (form != null) {
            COSBase fields =
                    form instanceof COSDictionary
                            ? ((COSDictionary) form).getDictionaryObject(COSName.FIELDS)
                            : null;
            if (!(form instanceof COSDictionary)
                    || (fields != null
                            && (!(fields instanceof COSArray)
                                    || ((COSArray) fields).size() != 0))) {
                unsupported("PDF 表单必须先由来源应用扁平化");
            }
        }
        String type = dictionary.getNameAsString(COSName.TYPE);
        String subtype = dictionary.getNameAsString(COSName.SUBTYPE);
        if ("Sig".equals(type)) {
            throw new OfdhException("SIGNED_INPUT_REQUIRES_DERIVATION", "不能改写已签署的 PDF");
        }
        if (dictionary instanceof COSStream && dictionary.containsKey(COSName.F)) {
            unsupported("不支持外部 PDF 数据流");
        }
        if ("Pattern".equals(type)
                || dictionary.containsKey(COSName.PATTERN_TYPE)
                || dictionary.containsKey(COSName.SHADING_TYPE)
                || ("Transparency".equals(dictionary.getNameAsString(COSName.S))
                        && !opaquePageGroups.contains(dictionary))
                || ("Form".equals(subtype) && dictionary.containsKey(COSName.GROUP))) {
            unsupported("不支持 PDF 图案、渐变和透明组");
        }
        if (nonDefault(dictionary, COSName.SMASK, "None")
                || dictionary.getFloat(COSName.CA, 1) != 1f
                || dictionary.getFloat(COSName.CA_NS, 1) != 1f
                || nonDefault(dictionary, COSName.BM, "Normal")
                || nonDefault(dictionary, COSName.TR, "Identity")
                || nonDefault(dictionary, COSName.TR2, "Default", "Identity")) {
            unsupported("不支持 PDF 透明度、混合及颜色转换函数");
        }
        if ("Image".equals(subtype)) {
            long width = dictionary.getInt(COSName.WIDTH);
            long height = dictionary.getInt(COSName.HEIGHT);
            if (width < 1 || height < 1 || width > limits.getMaxExpandedBytes() / 4 / height) {
                exceeded("PDF 图片尺寸超出解码限额");
            }
        }
    }

    static void inspectTextReplacement(COSDictionary dictionary) throws OfdhException {
        if (dictionary.containsKey(COSName.ACTUAL_TEXT)) {
            unsupported("当前不支持 PDF ActualText 替代文本；不能以字形映射覆盖来源的可复制文字");
        }
    }

    private static boolean nonDefault(COSDictionary dictionary, COSName key, String... defaults) {
        COSBase value = dictionary.getDictionaryObject(key);
        if (value == null) return false;
        if (value instanceof COSName) {
            for (String name : defaults) {
                if (name.equals(((COSName) value).getName())) return false;
            }
        }
        return true;
    }

    static void unsupported(String message) throws OfdhException {
        throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", message);
    }

    private static void exceeded(String message) throws OfdhException {
        throw new OfdhException("LIMIT_EXCEEDED", message);
    }
}
