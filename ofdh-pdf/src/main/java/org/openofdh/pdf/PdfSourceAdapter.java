package org.openofdh.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.dom4j.DocumentException;
import org.ofdrw.graphics2d.OFDGraphicsDocument;
import org.ofdrw.graphics2d.OFDPageGraphics2D;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.PageSourceAdapter;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.FileSafety;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 转换静态 PDF 页面，保留内嵌字体及带位置的 Unicode 文字。 */
public final class PdfSourceAdapter implements PageSourceAdapter {
    static final double MILLIMETRES_PER_POINT = 25.4 / 72.0;

    @Override
    public String format() {
        return "PDF";
    }

    @Override
    public void convert(Path source, Path target, ResourceLimits limits) throws IOException {
        Objects.requireNonNull(source, "来源文件不能为空");
        Objects.requireNonNull(target, "输出文件不能为空");
        Objects.requireNonNull(limits, "资源限额不能为空");
        if (Files.size(source) > limits.getMaxInputBytes()) {
            throw new OfdhException("LIMIT_EXCEEDED", "PDF 输入文件大小超出限额");
        }
        Path destination = target.toAbsolutePath().normalize();
        Path parent = FileSafety.unlinkedDirectory(destination.getParent());
        destination = parent.resolve(destination.getFileName());
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new OfdhException("OUTPUT_EXISTS", "输出文件已存在");
        }
        Path temporary = Files.createTempFile(parent, ".ofdh-pdf-", ".ofd");
        boolean published = false;
        try {
            convertToTemporary(source, temporary, limits);
            if (Files.size(temporary)
                    > Math.min(limits.getMaxInputBytes(), limits.getMaxExpandedBytes())) {
                throw new OfdhException("LIMIT_EXCEEDED", "PDF 转换后的输出大小超出限额");
            }
            validateOutputBudget(temporary, limits);
            FileSafety.publish(temporary, destination);
            published = true;
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    private void validateOutputBudget(Path path, ResourceLimits limits) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.size() > limits.getMaxEntries()) {
                throw new OfdhException("LIMIT_EXCEEDED", "PDF 转换后的包成员数量超出限额");
            }
            long expanded = 0;
            byte[] buffer = new byte[8192];
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                try (InputStream input = zip.getInputStream(entries.nextElement())) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        expanded += count;
                        if (expanded > limits.getMaxExpandedBytes()) {
                            throw new OfdhException("LIMIT_EXCEEDED", "PDF 转换后的展开大小超出限额");
                        }
                    }
                }
            }
        }
    }

    private void convertToTemporary(Path source, Path target, ResourceLimits limits)
            throws IOException {
        try (PDDocument pdf = Loader.loadPDF(source.toFile())) {
            new PdfPreflight(limits).inspect(pdf);
            try (OFDGraphicsDocument ofd = new OFDGraphicsDocument(target)) {
                // 本转换器按 GB/T 33190-2016 生成页面，显式覆盖 OFDRW 2.4 的 1.2 默认值。
                ofd.ofdDir.setOfd(ofd.ofdDir.getOfd().setVersion("1.0"));
                SearchablePdfRenderer renderer = new SearchablePdfRenderer(pdf, ofd, limits);
                for (int index = 0; index < pdf.getNumberOfPages(); index++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new OfdhException("OPERATION_CANCELLED", "PDF 转换已中断");
                    }
                    PDPage page = pdf.getPage(index);
                    PDRectangle crop = page.getCropBox();
                    boolean landscape = page.getRotation() == 90 || page.getRotation() == 270;
                    double width = landscape ? crop.getHeight() : crop.getWidth();
                    double height = landscape ? crop.getWidth() : crop.getHeight();
                    OFDPageGraphics2D graphics =
                            ofd.newPage(
                                    width * MILLIMETRES_PER_POINT, height * MILLIMETRES_PER_POINT);
                    try {
                        renderer.beginPage(index);
                        renderer.renderPageToGraphics(
                                index, graphics, (float) MILLIMETRES_PER_POINT);
                        renderer.finishPage(index);
                    } finally {
                        graphics.dispose();
                    }
                }
                PdfLinks.write(pdf, ofd);
            }
        } catch (DocumentException e) {
            throw new OfdhException("PDF_CONVERSION_FAILED", "无法设置 OFD 生成版本", e);
        } catch (InvalidPasswordException e) {
            throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "不支持加密 PDF", e);
        } catch (OfdhException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OfdhException("PDF_CONVERSION_FAILED", "PDF 渲染失败", e);
        }
    }
}
