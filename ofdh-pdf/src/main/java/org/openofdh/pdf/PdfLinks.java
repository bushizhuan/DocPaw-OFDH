package org.openofdh.pdf;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.ofdrw.core.action.Actions;
import org.ofdrw.core.action.CT_Action;
import org.ofdrw.core.action.EventType;
import org.ofdrw.core.action.actionType.OFDAction;
import org.ofdrw.core.action.actionType.URI;
import org.ofdrw.core.action.actionType.actionGoto.CT_Dest;
import org.ofdrw.core.action.actionType.actionGoto.DestType;
import org.ofdrw.core.action.actionType.actionGoto.Goto;
import org.ofdrw.core.annotation.Annotations;
import org.ofdrw.core.annotation.pageannot.AnnPage;
import org.ofdrw.core.annotation.pageannot.Annot;
import org.ofdrw.core.annotation.pageannot.AnnotType;
import org.ofdrw.core.annotation.pageannot.Appearance;
import org.ofdrw.core.annotation.pageannot.PageAnnot;
import org.ofdrw.core.basicStructure.pageObj.layer.block.PathObject;
import org.ofdrw.core.basicType.ST_Box;
import org.ofdrw.core.basicType.ST_Loc;
import org.ofdrw.core.graph.pathObj.AbbreviatedData;
import org.ofdrw.graphics2d.OFDGraphicsDocument;
import org.ofdrw.pkg.container.AnnotsDir;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.List;

final class PdfLinks {
    private PdfLinks() {}

    static void validate(PDDocument document, PDAnnotation annotation) throws IOException {
        if (!(annotation instanceof PDAnnotationLink)) {
            PdfPreflight.unsupported("仅支持 PDF 链接注释");
        }
        PDAnnotationLink link = (PDAnnotationLink) annotation;
        if (link.isHidden()
                || link.isInvisible()
                || link.isNoView()
                || link.isNoRotate()
                || link.isNoZoom()
                || (link.getQuadPoints() != null && link.getQuadPoints().length != 0)) {
            PdfPreflight.unsupported("PDF 链接必须具有可见的矩形交互区域");
        }
        PDRectangle rectangle = link.getRectangle();
        if (rectangle == null
                || !Float.isFinite(rectangle.getLowerLeftX())
                || !Float.isFinite(rectangle.getLowerLeftY())
                || !Float.isFinite(rectangle.getWidth())
                || !Float.isFinite(rectangle.getHeight())
                || rectangle.getWidth() <= 0
                || rectangle.getHeight() <= 0) {
            PdfPreflight.unsupported("PDF 链接的交互矩形无效");
        }
        PDAction action = link.getAction();
        if (action != null && link.getDestination() != null) {
            PdfPreflight.unsupported("PDF 链接同时设置了动作和直接目标");
        }
        if (action != null && action.getCOSObject().containsKey(COSName.NEXT)) {
            PdfPreflight.unsupported("不支持串联的 PDF 链接动作");
        }
        if (action instanceof PDActionURI && link.getDestination() == null) {
            validateUri(((PDActionURI) action).getURI());
        } else if (action == null || action instanceof PDActionGoTo) {
            destination(document, link);
        } else {
            PdfPreflight.unsupported("PDF 链接动作必须指向 URI 或文档内部目标");
        }
    }

    static void write(PDDocument document, OFDGraphicsDocument output) throws IOException {
        Annotations annotations = new Annotations();
        boolean present = false;
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            PDPage page = document.getPage(index);
            List<PDAnnotation> sourceAnnotations = page.getAnnotations();
            if (sourceAnnotations.isEmpty()) {
                continue;
            }
            present = true;
            PageAnnot pageAnnotations = new PageAnnot();
            for (PDAnnotation source : sourceAnnotations) {
                PDAnnotationLink link = (PDAnnotationLink) source;
                PDRectangle rectangle = link.getRectangle();
                Rectangle2D bounds =
                        pageTransform(page)
                                .createTransformedShape(
                                        new Rectangle2D.Double(
                                                rectangle.getLowerLeftX(),
                                                rectangle.getLowerLeftY(),
                                                rectangle.getWidth(),
                                                rectangle.getHeight()))
                                .getBounds2D();
                PathObject region = new PathObject(output.newID());
                region.setBoundary(0, 0, bounds.getWidth(), bounds.getHeight());
                region.setFill(false)
                        .setStroke(false)
                        .setAbbreviatedData(
                                new AbbreviatedData()
                                        .moveTo(0, 0)
                                        .lineTo(bounds.getWidth(), 0)
                                        .lineTo(bounds.getWidth(), bounds.getHeight())
                                        .lineTo(0, bounds.getHeight())
                                        .close());
                region.setActions(
                        new Actions()
                                .addAction(
                                        new CT_Action(
                                                EventType.CLICK, action(document, output, link))));
                Appearance appearance =
                        new Appearance(
                                        new ST_Box(
                                                bounds.getX(),
                                                bounds.getY(),
                                                bounds.getWidth(),
                                                bounds.getHeight()))
                                .addPageBlock(region);
                Annot annotation =
                        new Annot()
                                .setID(output.newID())
                                .setType(AnnotType.Link)
                                .setCreator("DocPaw-OFDH")
                                .setLastModDate(LocalDate.now())
                                .setVisible(true)
                                .setPrint(link.isPrinted())
                                .setAppearance(appearance);
                if (link.getContents() != null) {
                    annotation.setRemark(link.getContents());
                }
                pageAnnotations.addAnnot(annotation);
            }
            String filename = "Page_" + index + ".xml";
            output.docDir.obtainAnnots().putObj(filename, pageAnnotations);
            annotations.addPage(
                    new AnnPage()
                            .setPageID(output.document.getPages().getPageByIndex(index).getID())
                            .setFileLoc(new ST_Loc(filename)));
        }
        if (present) {
            AnnotsDir directory = output.docDir.obtainAnnots();
            directory.setAnnotations(annotations);
            output.document.setAnnotations(new ST_Loc("Annots/Annotations.xml"));
        }
    }

    private static OFDAction action(
            PDDocument document, OFDGraphicsDocument output, PDAnnotationLink link)
            throws IOException {
        if (link.getAction() instanceof PDActionURI) {
            return new URI(((PDActionURI) link.getAction()).getURI());
        }
        PDPageDestination source = destination(document, link);
        int page = source.retrievePageNumber();
        CT_Dest destination =
                new CT_Dest()
                        .setPageID(output.document.getPages().getPageByIndex(page).getID().ref());
        if (source instanceof PDPageFitDestination) {
            return new Goto(destination.setType(DestType.Fit));
        }
        destination.setType(DestType.XYZ);
        COSArray values = source.getCOSObject();
        Double x = number(values, 2);
        Double y = number(values, 3);
        AffineTransform transform = pageTransform(document.getPage(page));
        Double left =
                coordinate(
                        transform.getScaleX(),
                        transform.getShearX(),
                        transform.getTranslateX(),
                        x,
                        y);
        Double top =
                coordinate(
                        transform.getShearY(),
                        transform.getScaleY(),
                        transform.getTranslateY(),
                        x,
                        y);
        // GB/T 33190 附录 A.6 将这些值定义为属性；OFDRW 2.4.0 的设置方法却写入子元素。
        if (left != null) destination.addAttribute("Left", Double.toString(left));
        if (top != null) destination.addAttribute("Top", Double.toString(top));
        Double zoom = number(values, 4);
        if (zoom != null && zoom > 0) destination.addAttribute("Zoom", Double.toString(zoom));
        return new Goto(destination);
    }

    private static PDPageDestination destination(PDDocument document, PDAnnotationLink link)
            throws IOException {
        PDDestination destination =
                link.getAction() instanceof PDActionGoTo
                        ? ((PDActionGoTo) link.getAction()).getDestination()
                        : link.getDestination();
        if (destination instanceof PDNamedDestination) {
            destination =
                    document.getDocumentCatalog()
                            .findNamedDestinationPage((PDNamedDestination) destination);
        }
        if (!(destination instanceof PDPageXYZDestination)
                && !(destination instanceof PDPageFitDestination)) {
            PdfPreflight.unsupported("PDF 内部链接当前仅支持 XYZ 和 Fit 目标");
        }
        PDPageDestination page = (PDPageDestination) destination;
        if (page.retrievePageNumber() < 0
                || page.retrievePageNumber() >= document.getNumberOfPages()) {
            PdfPreflight.unsupported("PDF 链接指向文档范围之外");
        }
        if (page instanceof PDPageFitDestination && !"Fit".equals(page.getCOSObject().getName(1))) {
            PdfPreflight.unsupported("不支持按边界框适配的 PDF 链接目标");
        }
        if (page instanceof PDPageXYZDestination) {
            for (int index = 2; index <= 4; index++) number(page.getCOSObject(), index);
            if (number(page.getCOSObject(), 2) == null || number(page.getCOSObject(), 3) == null) {
                PdfPreflight.unsupported("PDF XYZ 链接必须同时指定两个坐标");
            }
            Double zoom = number(page.getCOSObject(), 4);
            if (zoom != null && (zoom < 0 || (zoom > 0 && (zoom < 0.1 || zoom > 64)))) {
                PdfPreflight.unsupported("PDF 链接缩放比例超出 OFD 目标的允许范围");
            }
        }
        return page;
    }

    private static Double number(COSArray array, int index) throws IOException {
        COSBase value = index < array.size() ? array.getObject(index) : null;
        if (value == null || value instanceof COSNull) return null;
        if (!(value instanceof COSNumber) || !Float.isFinite(((COSNumber) value).floatValue())) {
            PdfPreflight.unsupported("PDF 链接目标包含无效坐标");
        }
        return (double) ((COSNumber) value).floatValue();
    }

    private static Double coordinate(double a, double b, double offset, Double x, Double y) {
        if (Math.abs(a) < 1e-12) return y == null ? null : b * y + offset;
        return x == null ? null : a * x + offset;
    }

    private static void validateUri(String value) throws IOException {
        try {
            java.net.URI uri = new java.net.URI(value == null ? "" : value);
            String scheme = uri.getScheme();
            boolean web = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if ((!web && !"mailto".equalsIgnoreCase(scheme)) || (web && uri.getHost() == null)) {
                PdfPreflight.unsupported("PDF 链接 URI 必须为 HTTP、HTTPS 或 mailto 绝对地址");
            }
        } catch (URISyntaxException e) {
            PdfPreflight.unsupported("PDF 链接 URI 格式错误");
        }
    }

    private static AffineTransform pageTransform(PDPage page) {
        PDRectangle crop = page.getCropBox();
        AffineTransform transform =
                AffineTransform.getScaleInstance(
                        PdfSourceAdapter.MILLIMETRES_PER_POINT,
                        PdfSourceAdapter.MILLIMETRES_PER_POINT);
        switch (page.getRotation()) {
            case 90:
                transform.translate(crop.getHeight(), 0);
                break;
            case 180:
                transform.translate(crop.getWidth(), crop.getHeight());
                break;
            case 270:
                transform.translate(0, crop.getWidth());
                break;
            default:
                break;
        }
        transform.rotate(Math.toRadians(page.getRotation()));
        transform.translate(0, crop.getHeight());
        transform.scale(1, -1);
        transform.translate(-crop.getLowerLeftX(), -crop.getLowerLeftY());
        return transform;
    }
}
