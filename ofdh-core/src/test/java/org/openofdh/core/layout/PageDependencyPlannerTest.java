package org.openofdh.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.SafePackage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

class PageDependencyPlannerTest {
    @TempDir Path temporary;
    private int sequence;

    @Test
    void followsDocumentPageOrderAndDefersUnusedPayloads() throws Exception {
        Map<String, String> members = base("", "");
        members.put(
                "Doc_0/PublicRes.xml",
                xml(
                        "Res BaseLoc='Res'",
                        "<Fonts><Font ID='10'><FontFile>used.ttf</FontFile></Font>"
                                + "<Font ID='11'><FontFile>unused.ttf</FontFile></Font></Fonts>"));
        members.put("Doc_0/Res/used.ttf", "字体文件成员");
        members.put("Doc_0/Res/unused.ttf", "未使用的字体文件成员");
        members.put("Doc_0/Pages/P10.xml", page("", "<TextObject ID='100' Font='10'/>"));
        members.put("Doc_0/clinical.json", "临床正文无需参与布局解析");

        PageLayout layout = plan(members);
        assertEquals("Doc_0/Document.xml", layout.getDocument());
        assertEquals(
                Arrays.asList("OFD.xml", "Doc_0/Document.xml", "Doc_0/PublicRes.xml"),
                layout.getStartup());
        assertEquals(
                Arrays.asList("20", "3"),
                Arrays.asList(layout.getPages().get(0).getId(), layout.getPages().get(1).getId()));
        assertTrue(
                layout.getContentOrder().indexOf("Doc_0/Res/used.ttf")
                        < layout.getContentOrder().indexOf("Doc_0/Pages/P2.xml"));
        assertFalse(layout.getContentOrder().contains("Doc_0/Res/unused.ttf"));
        assertFalse(layout.getContentOrder().contains("Doc_0/clinical.json"));
        assertEquals(members.keySet(), new HashSet<>(layout.getEntryOrder()));
        assertEquals(members.size(), layout.getEntryOrder().size());
    }

    @Test
    void collectsRecursiveTemplatesLocalResourcesAndRelativeDrawingParameters() throws Exception {
        Map<String, String> members =
                base(
                        "<TemplatePage ID='60' BaseLoc='Templates/T1.xml'/>"
                                + "<TemplatePage ID='61' BaseLoc='Templates/T2.xml'/>",
                        "");
        members.put("Doc_0/Pages/P10.xml", page("<Template TemplateID='60'/>", ""));
        members.put(
                "Doc_0/Templates/T1.xml",
                page(
                        "<Template TemplateID='61'/><PageRes>../TemplateRes.xml</PageRes>",
                        "<PathObject ID='101' DrawParam='30'/>"));
        members.put("Doc_0/Templates/T2.xml", page("", "<ImageObject ID='102' ResourceID='40'/>"));
        members.put(
                "Doc_0/TemplateRes.xml",
                xml(
                        "Res BaseLoc='Res'",
                        "<DrawParams><DrawParam ID='30' Relative='31'/><DrawParam"
                            + " ID='31'><FillColor"
                            + " ColorSpace='32'/></DrawParam></DrawParams><ColorSpaces><ColorSpace"
                            + " ID='32' Type='RGB'"
                            + " Profile='color.icc'/></ColorSpaces><MultiMedias><MultiMedia ID='40'"
                            + " Type='Image'>"
                            + "<MediaFile>image.png</MediaFile></MultiMedia></MultiMedias>"));
        members.put("Doc_0/Res/color.icc", "色彩配置成员");
        members.put("Doc_0/Res/image.png", "图像成员");

        List<String> entries = plan(members).getPages().get(0).getEntries();
        assertTrue(
                entries.containsAll(
                        Arrays.asList(
                                "Doc_0/Templates/T1.xml",
                                "Doc_0/Templates/T2.xml",
                                "Doc_0/TemplateRes.xml",
                                "Doc_0/Res/color.icc",
                                "Doc_0/Res/image.png")));
    }

    @Test
    void includesExternalLayersAndOnlyTheAssociatedPageAnnotationAppearance() throws Exception {
        Map<String, String> members = base("", "<Annotations>Annotations.xml</Annotations>");
        members.put(
                "Doc_0/PublicRes.xml",
                xml(
                        "Res BaseLoc='Res'",
                        "<MultiMedias><MultiMedia ID='10'"
                            + " Type='Image'><MediaFile>layer.png</MediaFile></MultiMedia><MultiMedia"
                            + " ID='11' Type='Image'><MediaFile>note.png</MediaFile>"
                            + "</MultiMedia></MultiMedias>"));
        members.put(
                "Doc_0/Pages/P10.xml",
                xml("Page", "<Content><Layer BaseLoc='../Layer.xml'/></Content>"));
        members.put(
                "Doc_0/Layer.xml",
                xml("Layer ID='101'", "<ImageObject ID='102' ResourceID='10'/>"));
        members.put(
                "Doc_0/Annotations.xml",
                xml("Annotations", "<Page PageID='3'><FileLoc>Annots/P2.xml</FileLoc></Page>"));
        members.put(
                "Doc_0/Annots/P2.xml",
                xml(
                        "PageAnnot",
                        "<Annot ID='103' Type='Stamp'><Appearance Boundary='0 0 10 10'><ImageObject"
                            + " ID='104' ResourceID='11'/></Appearance></Annot>"));
        members.put("Doc_0/Res/layer.png", "图层图像成员");
        members.put("Doc_0/Res/note.png", "批注图像成员");

        PageLayout layout = plan(members);
        assertTrue(layout.getStartup().contains("Doc_0/Annotations.xml"));
        assertTrue(layout.getPages().get(0).getEntries().contains("Doc_0/Res/layer.png"));
        assertFalse(layout.getPages().get(0).getEntries().contains("Doc_0/Res/note.png"));
        assertTrue(
                layout.getPages()
                        .get(1)
                        .getEntries()
                        .containsAll(Arrays.asList("Doc_0/Annots/P2.xml", "Doc_0/Res/note.png")));
    }

    @Test
    void followsCompositePatternsImageMasksAndDefaultColorSpaceWithoutFollowingLinks()
            throws Exception {
        Map<String, String> members = base("<DefaultCS>35</DefaultCS>", "");
        members.put(
                "Doc_0/Pages/P10.xml",
                page(
                        "",
                        "<CompositeObject ID='100' ResourceID='10'/><Actions><Action"
                            + " Event='CLICK'><URI"
                            + " URI='https://example.org/record'/></Action></Actions>"));
        members.put(
                "Doc_0/PublicRes.xml",
                xml(
                        "Res BaseLoc='/Doc_0/Res/'",
                        "<CompositeGraphicUnits><CompositeGraphicUnit ID='10' Width='10'"
                            + " Height='10'><Thumbnail>20</Thumbnail><Content><PathObject"
                            + " ID='101'><FillColor><Pattern Width='10' Height='10'><CellContent"
                            + " Thumbnail='21'><ImageObject ID='102' ResourceID='20' ImageMask='22'"
                            + " Substitution='23'/>"
                            + "</CellContent></Pattern></FillColor></PathObject></Content>"
                            + "</CompositeGraphicUnit></CompositeGraphicUnits><MultiMedias><MultiMedia"
                            + " ID='20'"
                            + " Type='Image'><MediaFile>20.png</MediaFile></MultiMedia><MultiMedia"
                            + " ID='21'"
                            + " Type='Image'><MediaFile>21.png</MediaFile></MultiMedia><MultiMedia"
                            + " ID='22'"
                            + " Type='Image'><MediaFile>22.png</MediaFile></MultiMedia><MultiMedia"
                            + " ID='23'"
                            + " Type='Image'><MediaFile>23.png</MediaFile></MultiMedia></MultiMedias><ColorSpaces><ColorSpace"
                            + " ID='35' Type='RGB' Profile='color.icc'/></ColorSpaces>"));
        for (int i = 20; i <= 23; i++) members.put("Doc_0/Res/" + i + ".png", "图像成员");
        members.put("Doc_0/Res/color.icc", "默认色彩配置成员");
        PageLayout layout = plan(members);
        assertTrue(layout.getStartup().contains("Doc_0/Res/color.icc"));
        for (int i = 20; i <= 23; i++)
            assertTrue(layout.getPages().get(0).getEntries().contains("Doc_0/Res/" + i + ".png"));
    }

    @Test
    void preservesSignatureAndAttachmentMembersWithoutFrontLoadingTheirBodies() throws Exception {
        Map<String, String> members =
                base(
                        "",
                        "<Attachments>Attachments.xml</Attachments>"
                                + "<CustomTags>CustomTags.xml</CustomTags>");
        members.put(
                "Doc_0/Attachments.xml",
                xml(
                        "Attachments",
                        "<Attachment ID='90'><FileLoc>clinical.json</FileLoc></Attachment>"));
        members.put(
                "Doc_0/CustomTags.xml",
                xml(
                        "CustomTags",
                        "<CustomTag TypeID='clinical'><SchemaLoc>clinical.schema.json</SchemaLoc>"
                                + "<FileLoc>clinical.json</FileLoc></CustomTag>"));
        members.put("Doc_0/clinical.json", "布局分析不应解析临床数据");
        members.put("Doc_0/clinical.schema.json", "布局分析不应解析临床约束");
        PageLayout before = plan(members);
        members.put(
                "OFD.xml",
                members.get("OFD.xml")
                        .replace(
                                "</DocBody>",
                                "<Signatures>Doc_0/Signs/Signatures.xml</Signatures></DocBody>"));
        members.put("Doc_0/Signs/Signatures.xml", xml("Signatures", ""));
        members.put("Doc_0/Signs/Sign_0/Signature.xml", xml("Signature", ""));
        members.put("Doc_0/Signs/Sign_0/SignedValue.dat", "签章文件成员");
        PageLayout after = plan(members);
        assertEquals(before.getContentOrder(), after.getContentOrder());
        assertEquals(members.keySet(), new HashSet<>(after.getEntryOrder()));
        assertFalse(after.getContentOrder().contains("Doc_0/clinical.json"));
    }

    @Test
    void preservesExternalAttachmentAndSchemaLinksWithoutTreatingThemAsPackageMembers()
            throws Exception {
        Map<String, String> members =
                base(
                        "",
                        "<Attachments>Attachments.xml</Attachments>"
                                + "<CustomTags>CustomTags.xml</CustomTags>");
        members.put(
                "Doc_0/Attachments.xml",
                xml(
                        "Attachments",
                        "<Attachment"
                            + " ID='90'><FileLoc>https://example.invalid/record?version=1#report</FileLoc></Attachment><Attachment"
                            + " ID='91'><FileLoc>clinical.json</FileLoc></Attachment>"));
        members.put(
                "Doc_0/CustomTags.xml",
                xml(
                        "CustomTags",
                        "<CustomTag"
                            + " TypeID='clinical'><SchemaLoc>https://example.invalid/clinical.schema.json</SchemaLoc>"
                            + "<FileLoc>clinical.json</FileLoc></CustomTag><CustomTag"
                            + " TypeID='external'><SchemaLoc>urn:clinical:schema:1</SchemaLoc>"
                            + "<FileLoc>https://example.invalid/clinical.json</FileLoc></CustomTag>"));
        members.put("Doc_0/clinical.json", "布局分析不读取临床正文");
        try (SafePackage archive = SafePackage.open(write(members), ResourceLimits.defaults())) {
            byte[] attachments = archive.read("Doc_0/Attachments.xml");
            byte[] tags = archive.read("Doc_0/CustomTags.xml");
            PageLayout layout = PageDependencyPlanner.plan(archive, ResourceLimits.defaults());
            assertEquals(members.keySet(), new HashSet<>(layout.getEntryOrder()));
            assertTrue(
                    layout.getStartup()
                            .containsAll(
                                    Arrays.asList(
                                            "Doc_0/Attachments.xml", "Doc_0/CustomTags.xml")));
            assertFalse(layout.getContentOrder().contains("Doc_0/clinical.json"));
            assertArrayEquals(attachments, archive.read("Doc_0/Attachments.xml"));
            assertArrayEquals(tags, archive.read("Doc_0/CustomTags.xml"));
        }
    }

    @Test
    void stillRejectsTraversalMissingMembersAndInvalidExternalLinksInNonRenderingDirectories()
            throws Exception {
        for (String location :
                Arrays.asList(
                        "../../outside.json",
                        "missing.json",
                        "C:/outside.json",
                        "https://example.invalid/invalid link",
                        "../outside:record")) {
            Map<String, String> members = base("", "<Attachments>Attachments.xml</Attachments>");
            members.put(
                    "Doc_0/Attachments.xml",
                    xml(
                            "Attachments",
                            "<Attachment ID='90'><FileLoc>"
                                    + location
                                    + "</FileLoc></Attachment>"));
            assertCode("PAGE_LAYOUT_INVALID", members);
        }
        Map<String, String> members = base("", "<CustomTags>CustomTags.xml</CustomTags>");
        members.put(
                "Doc_0/CustomTags.xml",
                xml(
                        "CustomTags",
                        "<CustomTag TypeID='clinical'>"
                            + "<SchemaLoc>../../outside.schema.json</SchemaLoc>"
                            + "<FileLoc>https://example.invalid/clinical.json</FileLoc></CustomTag>"));
        assertCode("PAGE_LAYOUT_INVALID", members);
    }

    @Test
    void producesTheSameOrderRegardlessOfZipInsertionOrderAndReturnsImmutableLists()
            throws Exception {
        Map<String, String> members = base("", "");
        members.put("z.bin", "余项一");
        members.put("a.bin", "余项二");
        PageLayout first = plan(members);
        List<String> paths = new ArrayList<>(members.keySet());
        Collections.reverse(paths);
        Map<String, String> reversed = new LinkedHashMap<>();
        for (String path : paths) reversed.put(path, members.get(path));
        assertEquals(first.getEntryOrder(), plan(reversed).getEntryOrder());
        assertThrows(UnsupportedOperationException.class, () -> first.getEntryOrder().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.getPages().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> first.getPages().get(0).getEntries().clear());
    }

    @Test
    void rejectsMissingBinaryAndUndefinedResourceReferences() throws Exception {
        Map<String, String> members = base("", "");
        members.put("Doc_0/Pages/P10.xml", page("", "<TextObject ID='100' Font='404'/>"));
        assertCode("PAGE_LAYOUT_INVALID", members);
        members.put(
                "Doc_0/PublicRes.xml",
                xml(
                        "Res",
                        "<Fonts><Font ID='404'>"
                                + "<FontFile>missing.ttf</FontFile></Font></Fonts>"));
        assertCode("PAGE_LAYOUT_INVALID", members);
    }

    @Test
    void rejectsResourceTypeConflictsAndForeignDrawingAttributes() throws Exception {
        Map<String, String> members = base("", "");
        members.put(
                "Doc_0/PublicRes.xml", xml("Res", "<DrawParams><DrawParam ID='10'/></DrawParams>"));
        members.put("Doc_0/Pages/P10.xml", page("", "<TextObject ID='100' Font='10'/>"));
        assertCode("PAGE_LAYOUT_INVALID", members);
        members.put(
                "Doc_0/Pages/P10.xml",
                page(
                        "",
                        "<PathObject xmlns:vendor='urn:vendor' "
                                + "ID='100' vendor:Source='other.xml'/>"));
        assertCode("PAGE_LAYOUT_UNSUPPORTED", members);
    }

    @Test
    void rejectsResourceTemplateAndExternalLayerCycles() throws Exception {
        Map<String, String> members = base("", "");
        members.put(
                "Doc_0/PublicRes.xml",
                xml(
                        "Res",
                        "<DrawParams><DrawParam ID='10' Relative='11'/><DrawParam ID='11'"
                            + " Relative='10'/></DrawParams>"));
        assertCode("PAGE_LAYOUT_INVALID", members);

        members = base("<TemplatePage ID='60' BaseLoc='Templates/T.xml'/>", "");
        members.put("Doc_0/Templates/T.xml", page("<Template TemplateID='60'/>", ""));
        assertCode("PAGE_LAYOUT_INVALID", members);

        members = base("", "");
        members.put(
                "Doc_0/Pages/P10.xml",
                xml("Page", "<Content><Layer BaseLoc='../Layer.xml'/></Content>"));
        members.put("Doc_0/Layer.xml", xml("Layer BaseLoc='Layer.xml'", ""));
        assertCode("PAGE_LAYOUT_INVALID", members);
    }

    @Test
    void rejectsTraversalExternalRenderingPathsAndDuplicateResourceIds() throws Exception {
        for (String reference :
                Arrays.asList("../../outside.ttf", "https://example.org/font.ttf")) {
            Map<String, String> members = base("", "");
            members.put(
                    "Doc_0/PublicRes.xml",
                    xml(
                            "Res",
                            "<Fonts><Font ID='10'><FontFile>"
                                    + reference
                                    + "</FontFile></Font></Fonts>"));
            assertCode("PAGE_LAYOUT_INVALID", members);
        }
        Map<String, String> members = base("", "");
        members.put(
                "Doc_0/PublicRes.xml", xml("Res", "<Fonts><Font ID='10'/><Font ID='10'/></Fonts>"));
        assertCode("PAGE_LAYOUT_INVALID", members);
    }

    @Test
    void rejectsUnsupportedDrawingExtensionsAndInvalidAnnotationPageIds() throws Exception {
        Map<String, String> members = base("", "");
        members.put("Doc_0/Pages/P10.xml", page("", "<VendorObject ResourceID='10'/>"));
        assertCode("PAGE_LAYOUT_UNSUPPORTED", members);
        members = base("", "<Extensions>Extensions.xml</Extensions>");
        assertCode("PAGE_LAYOUT_UNSUPPORTED", members);
        members = base("", "<Annotations>Annotations.xml</Annotations>");
        members.put(
                "Doc_0/Annotations.xml",
                xml(
                        "Annotations",
                        "<Page PageID='99'>" + "<FileLoc>Annots/P.xml</FileLoc></Page>"));
        assertCode("PAGE_LAYOUT_INVALID", members);
    }

    @Test
    void appliesThePageBudgetAndHonorsCancellation() throws Exception {
        Path input = write(base("", ""));
        try (SafePackage archive = SafePackage.open(input, ResourceLimits.defaults())) {
            ResourceLimits onePage = new ResourceLimits(1 << 20, 1 << 20, 50, 1, 1024);
            assertEquals(
                    "LIMIT_EXCEEDED",
                    assertThrows(
                                    OfdhException.class,
                                    () -> PageDependencyPlanner.plan(archive, onePage))
                            .getCode());
            Thread.currentThread().interrupt();
            try {
                assertEquals(
                        "OPERATION_CANCELLED",
                        assertThrows(
                                        OfdhException.class,
                                        () ->
                                                PageDependencyPlanner.plan(
                                                        archive, ResourceLimits.defaults()))
                                .getCode());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void rejectsExcessiveDependencyDepthEvenWhenSharedDependenciesWereAlreadyVisited()
            throws Exception {
        Map<String, String> members = base("", "");
        StringBuilder parameters = new StringBuilder("<DrawParams><DrawParam ID='1'/>");
        for (int i = 2; i <= 130; i++)
            parameters
                    .append("<DrawParam ID='")
                    .append(i)
                    .append("' Relative='")
                    .append(i - 1)
                    .append("'/>");
        parameters.append("</DrawParams>");
        members.put("Doc_0/PublicRes.xml", xml("Res", parameters.toString()));
        assertCode("LIMIT_EXCEEDED", members);
    }

    private void assertCode(String code, Map<String, String> members) throws Exception {
        assertEquals(code, assertThrows(OfdhException.class, () -> plan(members)).getCode());
    }

    private PageLayout plan(Map<String, String> members) throws Exception {
        try (SafePackage archive = SafePackage.open(write(members), ResourceLimits.defaults())) {
            return PageDependencyPlanner.plan(archive, ResourceLimits.defaults());
        }
    }

    private Path write(Map<String, String> members) throws Exception {
        Path path = temporary.resolve("layout-" + (++sequence) + ".ofd");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : members.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }

    private static Map<String, String> base(String common, String directories) {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(
                "OFD.xml",
                xml(
                        "OFD Version='1.0' DocType='OFD'",
                        "<DocBody><DocRoot>Doc_0/Document.xml</DocRoot></DocBody>"));
        entries.put(
                "Doc_0/Document.xml",
                xml(
                        "Document",
                        "<CommonData><MaxUnitID>1000</MaxUnitID>"
                                + "<PageArea><PhysicalBox>0 0 210 297</PhysicalBox></PageArea>"
                                + "<PublicRes>PublicRes.xml</PublicRes>"
                                + common
                                + "</CommonData>"
                                + "<Pages><Page ID='20' BaseLoc='Pages/P10.xml'/>"
                                + "<Page ID='3' BaseLoc='Pages/P2.xml'/></Pages>"
                                + directories));
        entries.put("Doc_0/PublicRes.xml", xml("Res", ""));
        entries.put("Doc_0/Pages/P10.xml", page("", ""));
        entries.put("Doc_0/Pages/P2.xml", page("", ""));
        return entries;
    }

    private static String page(String definitions, String objects) {
        return xml(
                "Page", definitions + "<Content><Layer ID='500'>" + objects + "</Layer></Content>");
    }

    private static String xml(String root, String content) {
        String name = root.split(" ", 2)[0];
        return "<" + root + " xmlns='http://www.ofdspec.org/2016'>" + content + "</" + name + ">";
    }
}
