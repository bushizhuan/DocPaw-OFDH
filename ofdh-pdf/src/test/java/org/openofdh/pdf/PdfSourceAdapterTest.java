package org.openofdh.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.check.CheckService;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;
import org.openofdh.core.io.Xml;
import org.openofdh.core.prepare.PrepareService;
import org.openofdh.core.prepare.PreparedDocument;
import org.openofdh.core.validation.ClinicalSchemaRegistry;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

class PdfSourceAdapterTest {
    @TempDir Path directory;

    private final PdfSourceAdapter adapter = new PdfSourceAdapter();

    @Test
    void retainsBlankSeparatorPages() throws Exception {
        Path original = createPdf("separator-base.pdf", "Page", 0, false, false);
        Path input = directory.resolve("separators.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            pdf.addPage(new PDPage(PDRectangle.A4));
            pdf.save(input.toFile());
        }
        Path output = directory.resolve("separators.ofd");
        adapter.convert(input, output, ResourceLimits.defaults());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertVersion(zip);
            assertEquals(
                    2,
                    xml(zip, "Doc_0/Document.xml").getElementsByTagNameNS("*", "Page").getLength());
            assertEquals(
                    0,
                    xml(zip, "Doc_0/Pages/Page_1/Content.xml")
                            .getElementsByTagNameNS("*", "TextObject")
                            .getLength());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,1", "false,4", "true,1", "true,4"})
    void rejectsInconsistentPageTreeCountsInsteadOfDroppingPages(boolean nested, int declared)
            throws Exception {
        Path input = createPageTreePdf(nested, declared);
        String originalHash = FileSafety.sha256(input);
        try (PDDocument pdf = Loader.loadPDF(input.toFile())) {
            int actual = 0;
            for (PDPage ignored : pdf.getPages()) actual++;
            assertEquals(3, actual, "来源必须保留全部实际页面");
        }
        assertRejected(input, "SOURCE_PROFILE_UNSUPPORTED");
        assertEquals(originalHash, FileSafety.sha256(input));
    }

    @Test
    void retainsAValidNestedPageTree() throws Exception {
        Path input = createPageTreePdf(true, 3);
        Path output = directory.resolve("nested-pages.ofd");
        adapter.convert(input, output, ResourceLimits.defaults());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertEquals(
                    3,
                    xml(zip, "Doc_0/Document.xml").getElementsByTagNameNS("*", "Page").getLength());
        }
    }

    @Test
    void countsActualPagesAgainstTheLimitEvenWhenDeclaredCountIsSmaller() throws Exception {
        Path input = createPageTreePdf(false, 1);
        Path output = directory.resolve("too-many-pages.ofd");
        OfdhException failure =
                assertThrows(
                        OfdhException.class,
                        () ->
                                adapter.convert(
                                        input,
                                        output,
                                        new ResourceLimits(1L << 20, 1L << 20, 10000, 2, 1024)));
        assertEquals("LIMIT_EXCEEDED", failure.getCode());
        assertFalse(Files.exists(output));
        assertNoConversionTemporaries();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cycle", "duplicate", "deep"})
    void rejectsUnsafePageTreesBeforePdfBoxEnumeration(String kind) throws Exception {
        Path original = createPageTreePdf(true, 3);
        Path input = directory.resolve("unsafe-pages.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            COSDictionary root = pdf.getPages().getCOSObject();
            COSArray children = root.getCOSArray(COSName.KIDS);
            COSDictionary branch = (COSDictionary) children.getObject(0);
            if ("cycle".equals(kind)) {
                branch.getCOSArray(COSName.KIDS).add(branch);
            } else if ("duplicate".equals(kind)) {
                children.add(branch);
            } else {
                COSDictionary parent = root;
                for (int index = 0; index < 66; index++) {
                    parent = nestPages(parent);
                }
            }
            pdf.save(input.toFile());
        }
        assertRejected(input, "SOURCE_PROFILE_UNSUPPORTED");
    }

    private Path createPageTreePdf(boolean nested, int declared) throws IOException {
        Path original = createMultipagePdf();
        Path input = directory.resolve("page-tree.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            COSDictionary target = pdf.getPages().getCOSObject();
            if (nested) target = nestPages(target);
            target.setInt(COSName.COUNT, declared);
            pdf.save(input.toFile());
        }
        return input;
    }

    private static COSDictionary nestPages(COSDictionary parent) {
        COSArray children = parent.getCOSArray(COSName.KIDS);
        COSDictionary nested = new COSDictionary();
        nested.setItem(COSName.TYPE, COSName.PAGES);
        nested.setInt(COSName.COUNT, parent.getInt(COSName.COUNT));
        nested.setItem(COSName.PARENT, parent);
        nested.setItem(COSName.KIDS, children);
        for (int index = 0; index < children.size(); index++) {
            ((COSDictionary) children.getObject(index)).setItem(COSName.PARENT, nested);
        }
        COSArray replacement = new COSArray();
        replacement.add(nested);
        parent.setItem(COSName.KIDS, replacement);
        return nested;
    }

    @Test
    void retainsChinesePunctuationAndSupplementaryPlaneCharacters() throws Exception {
        String text = "中文标点：，。温度 36.5 ℃ 生僻字龘\uD840\uDC87";
        Path input = directory.resolve("chinese.pdf");
        try (PDDocument pdf = new PDDocument();
                InputStream bytes = getClass().getResourceAsStream("/fonts/validation-sans.ttf")) {
            assertNotNull(bytes);
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            writeText(pdf, page, PDType0Font.load(pdf, bytes, true), text);
            pdf.save(input.toFile());
        }
        Path output = directory.resolve("chinese.ofd");
        adapter.convert(input, output, ResourceLimits.defaults());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertVersion(zip);
            Document page = xml(zip, "Doc_0/Pages/Page_0/Content.xml");
            NodeList codes = page.getElementsByTagNameNS("*", "TextCode");
            StringBuilder extracted = new StringBuilder();
            for (int index = 0; index < codes.getLength(); index++)
                extracted.append(codes.item(index).getTextContent());
            assertEquals(text, extracted.toString());
            assertEquals(text.codePointCount(0, text.length()), codes.getLength());
            Element last =
                    (Element)
                            page.getElementsByTagNameNS("*", "CGTransform")
                                    .item(codes.getLength() - 1);
            assertEquals("1", last.getAttribute("CodeCount"));
        }
    }

    @Test
    void preservesUriAndInternalLinksWithRotatedDestinations() throws Exception {
        Path original = createPdf("link-base.pdf", "Links", 0, false, false);
        Path input = directory.resolve("links.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            PDPage target = new PDPage(PDRectangle.A4);
            target.setRotation(90);
            pdf.addPage(target);
            writeText(
                    pdf,
                    target,
                    pdf.getPage(0).getResources().getFont(COSName.getPDFName("F1")),
                    "Links");
            PDAnnotationLink uriLink = new PDAnnotationLink();
            uriLink.setRectangle(new PDRectangle(72, 680, 60, 20));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.org/specification");
            uriLink.setAction(uri);
            pdf.getPage(0).getAnnotations().add(uriLink);
            PDAnnotationLink localLink = new PDAnnotationLink();
            localLink.setRectangle(new PDRectangle(150, 680, 60, 20));
            PDPageXYZDestination destination = new PDPageXYZDestination();
            destination.setPage(target);
            destination.setLeft(72);
            destination.setTop(700);
            destination.setZoom(1.25f);
            localLink.setDestination(destination);
            pdf.getPage(0).getAnnotations().add(localLink);
            pdf.save(input.toFile());
        }
        Path output = directory.resolve("links.ofd");
        adapter.convert(input, output, ResourceLimits.defaults());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertVersion(zip);
            Document annots = xml(zip, "Doc_0/Annots/Page_0.xml");
            assertEquals(2, annots.getElementsByTagNameNS("*", "Annot").getLength());
            Element uri = (Element) annots.getElementsByTagNameNS("*", "URI").item(0);
            assertEquals("https://example.org/specification", uri.getAttribute("URI"));
            Element appearance = (Element) annots.getElementsByTagNameNS("*", "Appearance").item(0);
            double[] bounds = numbers(appearance.getAttribute("Boundary"));
            assertEquals(25.4, bounds[0], 0.001);
            assertEquals((PDRectangle.A4.getHeight() - 700) * 25.4 / 72, bounds[1], 0.001);
            Element destination = (Element) annots.getElementsByTagNameNS("*", "Dest").item(0);
            assertEquals(
                    700 * 25.4 / 72, Double.parseDouble(destination.getAttribute("Left")), 0.001);
            assertEquals(25.4, Double.parseDouble(destination.getAttribute("Top")), 0.001);
            assertEquals(1.25, Double.parseDouble(destination.getAttribute("Zoom")), 0.001);
            Document document = xml(zip, "Doc_0/Document.xml");
            Element page = (Element) document.getElementsByTagNameNS("*", "Page").item(1);
            assertEquals(page.getAttribute("ID"), destination.getAttribute("PageID"));
            assertEquals(
                    "Annots/Annotations.xml",
                    document.getElementsByTagNameNS("*", "Annotations").item(0).getTextContent());
            assertEquals(
                    "Page_0.xml",
                    xml(zip, "Doc_0/Annots/Annotations.xml")
                            .getElementsByTagNameNS("*", "FileLoc")
                            .item(0)
                            .getTextContent());
        }
    }

    @Test
    void rejectsUnsafeLinkActionsAndDoesNotOmitMissingImages() throws Exception {
        Path original = createPdf("resource-base.pdf", "Resources", 0, false, false);
        Path active = directory.resolve("unsafe-link.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 680, 60, 20));
            PDActionURI uri = new PDActionURI();
            uri.setURI("javascript:alert(1)");
            link.setAction(uri);
            pdf.getPage(0).getAnnotations().add(link);
            pdf.save(active.toFile());
        }
        assertRejected(active, "SOURCE_PROFILE_UNSUPPORTED");
        Path missing = directory.resolve("missing-image.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            try (PDPageContentStream content =
                    new PDPageContentStream(
                            pdf, pdf.getPage(0), PDPageContentStream.AppendMode.APPEND, true)) {
                content.appendRawCommands("/Missing Do\n");
            }
            pdf.save(missing.toFile());
        }
        Path output = directory.resolve("missing-image.ofd");
        assertThrows(
                IOException.class,
                () -> adapter.convert(missing, output, ResourceLimits.defaults()));
        assertFalse(Files.exists(output));
    }

    @Test
    void retainsUnicodeCoordinatesVectorGeometryAndOriginalFontBytes() throws Exception {
        String text = "Unicode: \u00b5 \u2265 \u2264 \u2212 1.25 mg";
        Path input = createPdf("unicode.pdf", text, 0, false, true);
        Path output = directory.resolve("unicode.ofd");
        adapter.convert(input, output, ResourceLimits.defaults());

        try (ZipFile zip = new ZipFile(output.toFile());
                PDDocument pdf = Loader.loadPDF(input.toFile())) {
            assertVersion(zip);
            Document page = xml(zip, "Doc_0/Pages/Page_0/Content.xml");
            NodeList codes = page.getElementsByTagNameNS("*", "TextCode");
            StringBuilder extracted = new StringBuilder();
            for (int index = 0; index < codes.getLength(); index++) {
                extracted.append(codes.item(index).getTextContent());
            }
            assertEquals(text, extracted.toString());
            NodeList objects = page.getElementsByTagNameNS("*", "TextObject");
            assertEquals(text.codePointCount(0, text.length()), objects.getLength());
            Element first = (Element) objects.item(0);
            assertEquals("false", first.getAttribute("Fill"));
            assertEquals("false", first.getAttribute("Stroke"));
            double[] boundary = numbers(first.getAttribute("Boundary"));
            double[] transform = numbers(first.getAttribute("CTM"));
            assertEquals(25.4, boundary[0] + transform[4], 0.001);
            assertEquals(
                    (PDRectangle.A4.getHeight() - 700) * 25.4 / 72,
                    boundary[1] + transform[5],
                    0.001);
            assertTrue(page.getElementsByTagNameNS("*", "PathObject").getLength() > 3);
            assertEquals(1, page.getElementsByTagNameNS("*", "ImageObject").getLength());
            assertNotNull(zip.getEntry("Doc_0/Res/font-0.ttf"));
            PDFont font = pdf.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            try (InputStream expected =
                            font.getFontDescriptor().getFontFile2().createInputStream();
                    InputStream actual = zip.getInputStream(zip.getEntry("Doc_0/Res/font-0.ttf"))) {
                assertArrayEquals(readBytes(expected), readBytes(actual));
            }
        }
    }

    @Test
    void keepsCroppedAndRotatedPageGeometry() throws Exception {
        for (int rotation : new int[] {0, 90, 180, 270}) {
            Path input = createPdf("rotate-" + rotation + ".pdf", "Rotate", rotation, true, false);
            Path output = directory.resolve("rotate-" + rotation + ".ofd");
            adapter.convert(input, output, ResourceLimits.defaults());
            try (ZipFile zip = new ZipFile(output.toFile())) {
                assertVersion(zip);
                Document page = xml(zip, "Doc_0/Pages/Page_0/Content.xml");
                Element first = (Element) page.getElementsByTagNameNS("*", "TextObject").item(0);
                double[] bounds = numbers(first.getAttribute("Boundary"));
                double[] matrix = numbers(first.getAttribute("CTM"));
                double x;
                double y;
                switch (rotation) {
                    case 90:
                        x = 700 - 30;
                        y = 72 - 20;
                        break;
                    case 180:
                        x = 500 - (72 - 20);
                        y = 700 - 30;
                        break;
                    case 270:
                        x = 760 - (700 - 30);
                        y = 500 - (72 - 20);
                        break;
                    default:
                        x = 72 - 20;
                        y = 760 - (700 - 30);
                }
                assertEquals(x * 25.4 / 72, bounds[0] + matrix[4], 0.001);
                assertEquals(y * 25.4 / 72, bounds[1] + matrix[5], 0.001);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparesMultipagePdfWithSharedArtworkAndLinks(boolean optimizePageOrder) throws Exception {
        Path source = createMultipagePdf();
        String sourceHash = FileSafety.sha256(source);
        Path output = directory.resolve("prepared.ofd");
        ResourceLimits limits = ResourceLimits.defaults();
        PreparedDocument prepared =
                new PrepareService(
                                Collections.singletonList(adapter),
                                new ClinicalSchemaRegistry(),
                                limits,
                                optimizePageOrder)
                        .prepare(request("PDF", source, sourceHash), directory, output);

        assertEquals(sourceHash, FileSafety.sha256(source));
        assertEquals(sourceHash, prepared.getSourceSha256());
        assertEquals(FileSafety.sha256(output), prepared.getSha256());
        CheckReport report = new CheckService(limits).check(output, CheckProfile.PREPARED);
        assertEquals(prepared.getSha256(), report.getArtifact().getSha256());
        for (CheckReport.Result result : report.getResults()) {
            assertFalse("FAIL".equals(result.getResult()), result.getRuleId());
            if ("OFDH-006".equals(result.getRuleId())
                    || "OFDH-007".equals(result.getRuleId())
                    || "OFDH-008".equals(result.getRuleId())
                    || "OFDH-009".equals(result.getRuleId())) {
                assertEquals("PASS", result.getResult(), result.getRuleId());
            }
        }
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertVersion(zip);
            assertEquals("OFD-H", xml(zip, "OFD.xml").getDocumentElement().getAttribute("DocType"));
            Document document = xml(zip, "Doc_0/Document.xml");
            NodeList pages = document.getElementsByTagNameNS(Xml.NS, "Page");
            assertEquals(3, pages.getLength());
            for (int index = 0; index < pages.getLength(); index++) {
                Element reference = (Element) pages.item(index);
                String path =
                        SafePackage.resolve(
                                "Doc_0/Document.xml", reference.getAttribute("BaseLoc"));
                Document page = xml(zip, path);
                NodeList codes = page.getElementsByTagNameNS(Xml.NS, "TextCode");
                StringBuilder text = new StringBuilder();
                for (int glyph = 0; glyph < codes.getLength(); glyph++) {
                    text.append(codes.item(glyph).getTextContent());
                }
                assertTrue(text.toString().contains("Page " + (index + 1)));
                assertTrue(text.toString().contains("Archive template"));
                assertEquals(1, page.getElementsByTagNameNS(Xml.NS, "ImageObject").getLength());
                assertTrue(page.getElementsByTagNameNS(Xml.NS, "PathObject").getLength() > 0);
            }
            assertResourceFiles(zip, document);
            Document annotations = xml(zip, "Doc_0/Annots/Page_0.xml");
            Element uri = (Element) annotations.getElementsByTagNameNS(Xml.NS, "URI").item(0);
            assertNotNull(uri);
            assertEquals("https://example.org/archive", uri.getAttribute("URI"));
        }
    }

    @Test
    void rejectsForeignOfdVersionWithoutRelabellingIt() throws Exception {
        Path source = createPdf("version-source.pdf", "Version", 0, false, false);
        Path converted = directory.resolve("converted.ofd");
        ResourceLimits limits = ResourceLimits.defaults();
        adapter.convert(source, converted, limits);
        try (ZipFile zip = new ZipFile(converted.toFile())) {
            assertVersion(zip);
        }
        Path foreign = directory.resolve("foreign-version.ofd");
        try (SafePackage archive = SafePackage.open(converted, limits)) {
            Document entry = archive.readXml("OFD.xml");
            entry.getDocumentElement().setAttribute("Version", "1.2");
            archive.writeXml("OFD.xml", entry);
            archive.publish(foreign);
        }
        String originalHash = FileSafety.sha256(foreign);
        Path output = directory.resolve("rejected.ofd");
        OfdhException failure =
                assertThrows(
                        OfdhException.class,
                        () ->
                                new PrepareService(
                                                Collections.singletonList(adapter),
                                                new ClinicalSchemaRegistry(),
                                                limits)
                                        .prepare(
                                                request("OFD", foreign, originalHash),
                                                directory,
                                                output));
        assertEquals("SOURCE_PROFILE_UNSUPPORTED", failure.getCode());
        assertEquals(originalHash, FileSafety.sha256(foreign));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsUnembeddedFontsWithoutLeavingAnOutput() throws Exception {
        Path input = directory.resolve("unembedded.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            writeText(pdf, page, new PDType1Font(Standard14Fonts.FontName.HELVETICA), "Unembedded");
            pdf.save(input.toFile());
        }
        assertRejected(input, "FONT_UNRESOLVED");
    }

    @Test
    void rejectsCompositeFontsWithoutUnicodeMappings() throws Exception {
        Path original = createPdf("mapped.pdf", "Mapping", 0, false, false);
        Path input = directory.resolve("missing-map.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            pdf.getPage(0)
                    .getResources()
                    .getFont(COSName.getPDFName("F1"))
                    .getCOSObject()
                    .removeItem(COSName.TO_UNICODE);
            pdf.save(input.toFile());
        }
        assertRejected(input, "PDF_TEXT_MAPPING_UNAVAILABLE");
    }

    @Test
    void rejectsEncryptedAndActiveDocuments() throws Exception {
        Path original = createPdf("base.pdf", "Protected", 0, false, false);
        Path encrypted = directory.resolve("encrypted.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            pdf.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            pdf.save(encrypted.toFile());
        }
        assertRejected(encrypted, "SOURCE_PROFILE_UNSUPPORTED");
        Path active = directory.resolve("active.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            pdf.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('active')"));
            pdf.save(active.toFile());
        }
        assertRejected(active, "SOURCE_PROFILE_UNSUPPORTED");
    }

    @Test
    void rejectsSignatureDictionariesAndImageOnlyPages() throws Exception {
        Path original = createPdf("unsigned.pdf", "Unsigned", 0, false, false);
        Path signed = directory.resolve("signature-container.pdf");
        try (PDDocument pdf = Loader.loadPDF(original.toFile())) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            pdf.addSignature(signature);
            pdf.save(signed.toFile());
        }
        assertRejected(signed, "SIGNED_INPUT_REQUIRES_DERIVATION");
        Path scanned = directory.resolve("image-only.pdf");
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.drawImage(
                        LosslessFactory.createFromImage(
                                pdf, new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)),
                        10,
                        10);
            }
            pdf.save(scanned.toFile());
        }
        assertRejected(scanned, "PDF_TEXT_MAPPING_UNAVAILABLE");
    }

    @Test
    void enforcesInputAndExpandedStreamBudgets() throws Exception {
        Path input = createPdf("limit.pdf", "Limits", 0, false, false);
        Path output = directory.resolve("limit.ofd");
        OfdhException inputError =
                assertThrows(
                        OfdhException.class,
                        () ->
                                adapter.convert(
                                        input,
                                        output,
                                        new ResourceLimits(1, 1000000, 1000, 100, 1000)));
        assertEquals("LIMIT_EXCEEDED", inputError.getCode());
        OfdhException expandedError =
                assertThrows(
                        OfdhException.class,
                        () ->
                                adapter.convert(
                                        input,
                                        output,
                                        new ResourceLimits(1000000, 10, 1000, 100, 1000)));
        assertEquals("LIMIT_EXCEEDED", expandedError.getCode());
        assertFalse(Files.exists(output));
    }

    @Test
    void enforcesPublishedFileBudgetWhenTheConvertedDocumentGrows() throws Exception {
        Path input = createPdf("output-budget.pdf", "Output budget", 0, false, false);
        Path reference = directory.resolve("output-budget-reference.ofd");
        adapter.convert(input, reference, ResourceLimits.defaults());
        long inputBytes = Files.size(input);
        assertTrue(Files.size(reference) > inputBytes, "测试来源必须在转换后增大");
        String inputHash = FileSafety.sha256(input);
        Path output = directory.resolve("output-budget-limited.ofd");
        OfdhException failure =
                assertThrows(
                        OfdhException.class,
                        () ->
                                adapter.convert(
                                        input,
                                        output,
                                        new ResourceLimits(
                                                inputBytes, 1L << 20, 10000, 100, 1024)));
        assertEquals("LIMIT_EXCEEDED", failure.getCode());
        assertEquals(inputHash, FileSafety.sha256(input));
        assertFalse(Files.exists(output));
        assertNoConversionTemporaries();
    }

    @ParameterizedTest
    @CsvSource({"false,Replacement", "true,Replacement", "false,''", "true,''"})
    void rejectsActualTextInsteadOfSilentlyReplacingItsMeaning(boolean named, String replacement)
            throws Exception {
        Path input = createMarkedPdf(named, replacement);
        String inputHash = FileSafety.sha256(input);
        try (PDDocument pdf = Loader.loadPDF(input.toFile())) {
            assertEquals(replacement, new PDFTextStripper().getText(pdf).trim());
        }
        assertRejected(input, "SOURCE_PROFILE_UNSUPPORTED");
        assertEquals(inputHash, FileSafety.sha256(input));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainsOrdinaryMarkedContentWithoutActualText(boolean named) throws Exception {
        Path input = createMarkedPdf(named, null);
        Path output = directory.resolve("marked.ofd");
        adapter.convert(input, output, ResourceLimits.defaults());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            NodeList codes =
                    xml(zip, "Doc_0/Pages/Page_0/Content.xml")
                            .getElementsByTagNameNS("*", "TextCode");
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < codes.getLength(); index++) {
                text.append(codes.item(index).getTextContent());
            }
            assertEquals("Visible", text.toString());
        }
    }

    private Path createMarkedPdf(boolean named, String replacement) throws IOException {
        Path input = directory.resolve("marked.pdf");
        try (PDDocument pdf = new PDDocument();
                InputStream bytes =
                        PDDocument.class.getResourceAsStream(
                                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(bytes);
            PDPage page = new PDPage();
            pdf.addPage(page);
            PDFont font = PDType0Font.load(pdf, bytes, true);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                if (named) {
                    COSDictionary properties = new COSDictionary();
                    properties.setString(COSName.LANG, "en");
                    if (replacement != null) properties.setString(COSName.ACTUAL_TEXT, replacement);
                    content.beginMarkedContent(
                            COSName.getPDFName("Span"), PDPropertyList.create(properties));
                } else {
                    content.appendRawCommands(
                            "/Span << /Lang (en)"
                                    + (replacement == null
                                            ? ""
                                            : " /ActualText (" + replacement + ")")
                                    + " >> BDC\n");
                }
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(72, 700);
                content.showText("Visible");
                content.endText();
                content.endMarkedContent();
            }
            pdf.save(input.toFile());
        }
        return input;
    }

    @Test
    void neverOverwritesAnExistingOutput() throws Exception {
        Path input = createPdf("input.pdf", "Keep", 0, false, false);
        Path output = directory.resolve("existing.ofd");
        byte[] original = "existing bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(output, original);
        OfdhException error =
                assertThrows(
                        OfdhException.class,
                        () -> adapter.convert(input, output, ResourceLimits.defaults()));
        assertEquals("OUTPUT_EXISTS", error.getCode());
        assertArrayEquals(original, Files.readAllBytes(output));
    }

    private Path createMultipagePdf() throws IOException {
        Path source = directory.resolve("multipage.pdf");
        try (PDDocument pdf = new PDDocument();
                InputStream bytes =
                        PDDocument.class.getResourceAsStream(
                                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(bytes);
            PDFont font = PDType0Font.load(pdf, bytes, true);
            PDFormXObject template = new PDFormXObject(pdf);
            template.setResources(new PDResources());
            template.setBBox(PDRectangle.A4);
            try (PDFormContentStream content = new PDFormContentStream(template)) {
                content.beginText();
                content.setFont(font, 10);
                content.newLineAtOffset(72, 640);
                content.showText("Archive template");
                content.endText();
                content.setStrokingColor(Color.BLUE);
                content.addRect(50, 480, 200, 100);
                content.stroke();
                content.drawImage(
                        LosslessFactory.createFromImage(
                                pdf, new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)),
                        60,
                        500,
                        16,
                        16);
            }
            for (int index = 0; index < 3; index++) {
                PDPage page = new PDPage(PDRectangle.A4);
                pdf.addPage(page);
                writeText(pdf, page, font, "Page " + (index + 1));
                try (PDPageContentStream content =
                        new PDPageContentStream(
                                pdf, page, PDPageContentStream.AppendMode.APPEND, true)) {
                    content.drawForm(template);
                }
            }
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, 680, 100, 20));
            PDActionURI action = new PDActionURI();
            action.setURI("https://example.org/archive");
            link.setAction(action);
            pdf.getPage(0).getAnnotations().add(link);
            pdf.save(source.toFile());
        }
        return source;
    }

    private static DocumentRequest request(String format, Path source, String digest) {
        return DocumentRequest.builder()
                .documentId("pdf-integration")
                .sourceRecord("integration", "pdf-source", "1")
                .documentType("record", "integration")
                .patient("subject", "integration")
                .responsibleParty("author", "转换校验", "integration")
                .createdAt("2026-01-01T00:00:00Z")
                .keywords("PDF 转换校验")
                .confidentiality("internal", "integration")
                .source(format, source.getFileName().toString(), digest)
                .build();
    }

    private static void assertVersion(ZipFile zip) throws Exception {
        Element root = xml(zip, "OFD.xml").getDocumentElement();
        assertEquals(Xml.NS, root.getNamespaceURI());
        assertEquals("1.0", root.getAttribute("Version"));
    }

    private static void assertResourceFiles(ZipFile zip, Document document) throws Exception {
        Element common = Xml.child(document.getDocumentElement(), "CommonData");
        int fonts = 0;
        int images = 0;
        for (String kind : new String[] {"PublicRes", "DocumentRes"}) {
            for (Element reference : Xml.children(common, kind)) {
                String catalog =
                        SafePackage.resolve("Doc_0/Document.xml", reference.getTextContent());
                Element root = xml(zip, catalog).getDocumentElement();
                String base = root.getAttribute("BaseLoc");
                String owner =
                        base.isEmpty() ? catalog : SafePackage.resolve(catalog, base + "/resource");
                for (String tag : new String[] {"FontFile", "MediaFile"}) {
                    NodeList files = root.getElementsByTagNameNS(Xml.NS, tag);
                    for (int index = 0; index < files.getLength(); index++) {
                        String path =
                                SafePackage.resolve(owner, files.item(index).getTextContent());
                        ZipEntry entry = zip.getEntry(path);
                        assertNotNull(entry, "资源文件缺失：" + path);
                        assertTrue(entry.getSize() > 0, "资源文件为空：" + path);
                    }
                    if ("FontFile".equals(tag)) fonts += files.getLength();
                    else images += files.getLength();
                }
            }
        }
        assertTrue(fonts > 0, "未找到内嵌字体");
        assertTrue(images > 0, "未找到模板图像资源");
    }

    private Path createPdf(String name, String text, int rotation, boolean cropped, boolean image)
            throws IOException {
        Path path = directory.resolve(name);
        try (PDDocument pdf = new PDDocument();
                InputStream fontBytes =
                        PDDocument.class.getResourceAsStream(
                                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(fontBytes);
            PDFont font = PDType0Font.load(pdf, fontBytes, true);
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(rotation);
            if (cropped) {
                page.setCropBox(new PDRectangle(20, 30, 500, 760));
            }
            pdf.addPage(page);
            writeText(pdf, page, font, text);
            if (image) {
                try (PDPageContentStream content =
                        new PDPageContentStream(
                                pdf, page, PDPageContentStream.AppendMode.APPEND, true)) {
                    content.setStrokingColor(Color.BLUE);
                    content.addRect(50, 600, 200, 50);
                    content.stroke();
                    content.drawImage(
                            LosslessFactory.createFromImage(
                                    pdf, new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)),
                            50,
                            500,
                            16,
                            16);
                }
            }
            pdf.save(path.toFile());
        }
        return path;
    }

    private void writeText(PDDocument pdf, PDPage page, PDFont font, String text)
            throws IOException {
        try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
            content.beginText();
            content.setFont(font, 12);
            content.newLineAtOffset(72, 700);
            content.showText(text);
            content.endText();
        }
    }

    private void assertRejected(Path input, String code) throws IOException {
        Path output = directory.resolve(input.getFileName() + ".ofd");
        OfdhException error =
                assertThrows(
                        OfdhException.class,
                        () -> adapter.convert(input, output, ResourceLimits.defaults()));
        assertEquals(code, error.getCode());
        assertFalse(Files.exists(output));
        assertNoConversionTemporaries();
    }

    private void assertNoConversionTemporaries() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertFalse(
                    files.anyMatch(path -> path.getFileName().toString().startsWith(".ofdh-pdf-")));
        }
    }

    private static Document xml(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertNotNull(entry, name);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (InputStream stream = zip.getInputStream(entry)) {
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static double[] numbers(String value) {
        String[] words = value.trim().split("\\s+");
        double[] result = new double[words.length];
        for (int index = 0; index < words.length; index++) {
            result[index] = Double.parseDouble(words[index]);
        }
        return result;
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }
}
