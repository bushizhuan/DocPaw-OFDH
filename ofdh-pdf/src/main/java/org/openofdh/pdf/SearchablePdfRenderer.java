package org.openofdh.pdf;

import org.apache.fontbox.ttf.OS2WindowsMetricsTable;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.dom4j.DocumentException;
import org.ofdrw.core.basicStructure.pageObj.layer.CT_Layer;
import org.ofdrw.core.basicStructure.pageObj.layer.Type;
import org.ofdrw.core.basicStructure.pageObj.layer.block.TextObject;
import org.ofdrw.core.basicStructure.res.resources.Fonts;
import org.ofdrw.core.basicType.ST_Array;
import org.ofdrw.core.basicType.ST_ID;
import org.ofdrw.core.text.CT_CGTransform;
import org.ofdrw.core.text.TextCode;
import org.ofdrw.core.text.font.CT_Font;
import org.ofdrw.graphics2d.OFDGraphicsDocument;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SearchablePdfRenderer extends PDFRenderer {
    private final OFDGraphicsDocument output;
    private final ResourceLimits limits;
    private final Map<PDFont, ST_ID> fontIds = new IdentityHashMap<>();
    private final Fonts fonts = new Fonts();
    private CT_Layer textLayer;
    private long glyphs;
    private long operators;
    private int pageGlyphs;
    private boolean pageHasArtwork;

    SearchablePdfRenderer(PDDocument document, OFDGraphicsDocument output, ResourceLimits limits) {
        super(document);
        this.output = output;
        this.limits = limits;
        setSubsamplingAllowed(false);
    }

    void beginPage(int index) {
        textLayer = new CT_Layer().setType(Type.Foreground);
        textLayer.setObjID(output.newID());
        pageGlyphs = 0;
        pageHasArtwork = false;
    }

    void finishPage(int index) throws IOException {
        if (pageGlyphs == 0 && pageHasArtwork) {
            throw new OfdhException(
                    "PDF_TEXT_MAPPING_UNAVAILABLE",
                    "PDF 第 " + (index + 1) + " 页缺少可处理的文字；仅含图片的页面需要单独处理");
        }
        if (pageGlyphs == 0) return;
        try {
            output.docDir
                    .obtainPages()
                    .getByIndex(index)
                    .getContent()
                    .getContent()
                    .addLayer(textLayer);
        } catch (DocumentException e) {
            throw new IOException("无法添加转换后的 PDF 文字层", e);
        }
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
        return new TextPageDrawer(parameters);
    }

    private ST_ID registerFont(PDFont font) throws IOException {
        ST_ID existing = fontIds.get(font);
        if (existing != null) {
            return existing;
        }
        PDFontDescriptor descriptor = font.getFontDescriptor();
        TrueTypeFont trueType;
        if (font instanceof PDType0Font
                && ((PDType0Font) font).getDescendantFont() instanceof PDCIDFontType2) {
            trueType =
                    ((PDCIDFontType2) ((PDType0Font) font).getDescendantFont()).getTrueTypeFont();
            if (!font.getCOSObject().containsKey(COSName.TO_UNICODE)) {
                throw mappingUnavailable("PDF 复合字体必须包含明确的 ToUnicode 映射");
            }
        } else if (font instanceof PDTrueTypeFont) {
            trueType = ((PDTrueTypeFont) font).getTrueTypeFont();
        } else {
            throw new OfdhException("FONT_UNRESOLVED", "仅支持内嵌 TrueType 字体的 PDF");
        }
        if (!font.isEmbedded()
                || font.isVertical()
                || descriptor == null
                || descriptor.getFontFile2() == null
                || trueType == null) {
            throw new OfdhException("FONT_UNRESOLVED", "PDF 字体必须为内嵌的横排 TrueType 字体");
        }
        OS2WindowsMetricsTable metrics = trueType.getOS2Windows();
        if (metrics != null && (metrics.getFsType() & (0x0002 | 0x0200)) != 0) {
            throw new OfdhException("FONT_EMBEDDING_RESTRICTED", "字体嵌入权限不允许本次转换");
        }
        if (fontIds.size() >= limits.getMaxEntries()) {
            throw new OfdhException("LIMIT_EXCEEDED", "PDF 字体数量超出资源限额");
        }
        String filename = "font-" + fontIds.size() + ".ttf";
        Path resource = output.docDir.obtainRes().getContainerPath().resolve(filename);
        try (InputStream in = descriptor.getFontFile2().createInputStream();
                OutputStream out = Files.newOutputStream(resource)) {
            byte[] buffer = new byte[8192];
            long size = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                size += count;
                if (size > limits.getMaxExpandedBytes()) {
                    throw new OfdhException("LIMIT_EXCEEDED", "PDF 内嵌字体大小超出限额");
                }
                out.write(buffer, 0, count);
            }
        }
        ST_ID id = output.newID();
        CT_Font resourceFont = new CT_Font(font.getName()).setID(id).setFontFile(filename);
        if (fontIds.isEmpty()) {
            output.resMgr.pubRes().addResource(fonts);
        }
        fonts.addFont(resourceFont);
        fontIds.put(font, id);
        return id;
    }

    private static OfdhException mappingUnavailable(String message) {
        return new OfdhException("PDF_TEXT_MAPPING_UNAVAILABLE", message);
    }

    private final class TextPageDrawer extends PageDrawer {
        private final Set<COSDictionary> activeForms =
                Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());

        TextPageDrawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands)
                throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new OfdhException("OPERATION_CANCELLED", "PDF 转换已中断");
            }
            if (++operators > limits.getMaxExpandedBytes() / 64) {
                throw new OfdhException("LIMIT_EXCEEDED", "PDF 绘制操作数量超出限额");
            }
            if ("BDC".equals(operator.getName()) || "DP".equals(operator.getName())) {
                // 内容流中的直接属性字典不会出现在文件对象图中，需要在执行前补查。
                for (COSBase operand : operands) {
                    if (operand instanceof COSDictionary) {
                        PdfPreflight.inspectTextReplacement((COSDictionary) operand);
                    }
                }
            }
            super.processOperator(operator, operands);
        }

        @Override
        protected void operatorException(
                Operator operator, List<COSBase> operands, IOException error) throws IOException {
            // PDFBox 会容忍部分缺失图片和资源，转换时必须拒绝静默遗漏。
            throw error;
        }

        @Override
        protected void unsupportedOperator(Operator operator, List<COSBase> operands)
                throws IOException {
            PdfPreflight.unsupported("PDF 包含不支持的绘制操作符");
        }

        @Override
        public void showForm(PDFormXObject form) throws IOException {
            if (activeForms.size() >= 64 || !activeForms.add(form.getCOSObject())) {
                PdfPreflight.unsupported("PDF 表单资源存在循环或嵌套层数超出限额");
            }
            try {
                super.showForm(form);
            } finally {
                activeForms.remove(form.getCOSObject());
            }
        }

        @Override
        protected void showFontGlyph(Matrix matrix, PDFont font, int code, Vector displacement)
                throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new OfdhException("OPERATION_CANCELLED", "PDF 转换已中断");
            }
            if (++glyphs > limits.getMaxExpandedBytes() / 1024) {
                throw new OfdhException("LIMIT_EXCEEDED", "PDF 字形数量超出输出限额");
            }
            RenderingMode mode = getGraphicsState().getTextState().getRenderingMode();
            if (mode.isClip() || (!mode.isFill() && !mode.isStroke())) {
                PdfPreflight.unsupported("当前不支持 PDF 隐藏文字或文字裁剪");
            }
            ST_ID fontId = registerFont(font);
            String unicode = font.toUnicode(code);
            if (unicode == null || unicode.isEmpty() || !isValidXmlText(unicode)) {
                throw mappingUnavailable("PDF 字形缺少可靠的 Unicode 映射");
            }
            int gid =
                    font instanceof PDType0Font
                            ? ((PDType0Font) font).codeToGID(code)
                            : ((PDTrueTypeFont) font).codeToGID(code);
            if (gid == 0) {
                throw mappingUnavailable("PDF 字符映射到缺失的字体字形");
            }

            AffineTransform transform = getGraphics().getTransform();
            transform.concatenate(matrix.createAffineTransform());
            AffineTransform glyphTransform = new AffineTransform(transform);
            glyphTransform.concatenate(font.getFontMatrix().createAffineTransform());
            Shape path = ((PDVectorFont) font).getPath(code);
            Rectangle2D bounds = glyphTransform.createTransformedShape(path).getBounds2D();
            if (bounds.isEmpty()) {
                bounds =
                        transform
                                .createTransformedShape(
                                        new Rectangle2D.Double(
                                                0,
                                                -0.2,
                                                Math.max(Math.abs(displacement.getX()), 0.05),
                                                1.0))
                                .getBounds2D();
            }
            if (!Double.isFinite(bounds.getX())
                    || !Double.isFinite(bounds.getY())
                    || !Double.isFinite(bounds.getWidth())
                    || !Double.isFinite(bounds.getHeight())) {
                throw mappingUnavailable("PDF 文字变换参数不是有限数值");
            }
            // PDF 字形坐标向上增长，OFD 文字坐标向下增长。
            transform.scale(1, -1);
            double[] values = new double[6];
            transform.getMatrix(values);
            if (Math.abs(transform.getDeterminant()) < 1e-12) {
                throw mappingUnavailable("PDF 文字变换矩阵不可逆");
            }
            TextObject text = new TextObject(output.newID());
            text.setBoundary(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
            text.setFont(fontId.ref()).setSize(1.0).setFill(false).setStroke(false);
            text.setCTM(
                    new ST_Array(
                            values[0],
                            values[1],
                            values[2],
                            values[3],
                            values[4] - bounds.getX(),
                            values[5] - bounds.getY()));
            text.addCGTransform(
                    new CT_CGTransform()
                            .setCodePosition(0)
                            .setCodeCount(unicode.codePointCount(0, unicode.length()))
                            .setGlyphCount(1)
                            .setGlyphs(new ST_Array(Integer.valueOf(gid))));
            text.addTextCode(new TextCode().setCoordinate(0.0, 0.0).setContent(unicode));
            textLayer.addPageBlock(text);
            pageGlyphs++;
            super.showFontGlyph(matrix, font, code, displacement);
        }

        @Override
        protected void showType3Glyph(
                Matrix matrix, PDType3Font font, int code, Vector displacement) throws IOException {
            PdfPreflight.unsupported("不支持 PDF Type 3 字体");
        }

        @Override
        public void drawImage(PDImage image) throws IOException {
            pageHasArtwork = true;
            long width = image.getWidth();
            long height = image.getHeight();
            if (width <= 0 || height <= 0 || width > limits.getMaxExpandedBytes() / 4 / height) {
                throw new OfdhException("LIMIT_EXCEEDED", "PDF 图片超出解码限额");
            }
            super.drawImage(image);
        }

        @Override
        public void strokePath() throws IOException {
            pageHasArtwork = true;
            super.strokePath();
        }

        @Override
        public void fillPath(int windingRule) throws IOException {
            pageHasArtwork = true;
            super.fillPath(windingRule);
        }
    }

    private static boolean isValidXmlText(String text) {
        for (int index = 0; index < text.length(); ) {
            int cp = text.codePointAt(index);
            if (cp == 0xfffd
                    || (cp < 0x20 && cp != 9 && cp != 10 && cp != 13)
                    || (cp >= 0xd800 && cp <= 0xdfff)
                    || cp == 0xfffe
                    || cp == 0xffff) {
                return false;
            }
            index += Character.charCount(cp);
        }
        return true;
    }
}
