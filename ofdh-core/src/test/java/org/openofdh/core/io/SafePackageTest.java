package org.openofdh.core.io;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

class SafePackageTest {
    @TempDir Path temporary;

    @Test
    void publishesOnlyCompletePermutationsWithoutChangingContent() throws Exception {
        Map<String, String> members = new LinkedHashMap<>();
        members.put("OFD.xml", "入口");
        members.put("Doc_0/Attachments/data.json", "临床数据");
        members.put("Doc_0/Pages/Page_1/Content.xml", "页面");
        Path input = zip(members);
        Path output = temporary.resolve("ordered.ofd");
        java.util.List<String> order =
                java.util.Arrays.asList(
                        "OFD.xml", "Doc_0/Pages/Page_1/Content.xml", "Doc_0/Attachments/data.json");
        try (SafePackage archive = SafePackage.open(input, ResourceLimits.defaults())) {
            for (java.util.List<String> invalid :
                    java.util.Arrays.asList(
                            order.subList(0, 2),
                            java.util.Arrays.asList("OFD.xml", "OFD.xml", order.get(2)),
                            java.util.Arrays.asList("OFD.xml", "missing.xml", order.get(2)))) {
                assertEquals(
                        "ZIP_LAYOUT_INVALID",
                        assertThrows(OfdhException.class, () -> archive.publish(output, invalid))
                                .getCode());
                assertFalse(Files.exists(output));
            }
            archive.publish(output, order);
        }
        java.util.List<String> physical = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zip =
                new java.util.zip.ZipInputStream(Files.newInputStream(output))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                physical.add(entry.getName());
            }
        }
        assertEquals(order, physical);
        try (SafePackage archive = SafePackage.open(output, ResourceLimits.defaults())) {
            assertEquals(order, new java.util.ArrayList<>(archive.entries()));
            for (Map.Entry<String, String> member : members.entrySet()) {
                assertArrayEquals(
                        member.getValue().getBytes(StandardCharsets.UTF_8),
                        archive.read(member.getKey()));
            }
        }
        byte[] published = Files.readAllBytes(output);
        try (SafePackage archive = SafePackage.open(input, ResourceLimits.defaults())) {
            assertEquals(
                    "OUTPUT_EXISTS",
                    assertThrows(OfdhException.class, () -> archive.publish(output, order))
                            .getCode());
        }
        assertArrayEquals(published, Files.readAllBytes(output));
    }

    @Test
    void resolvesPackageAbsoluteAndBoundedRelativeLocations() throws Exception {
        assertEquals(
                "Doc_0/Res/font.ttf",
                SafePackage.resolve("Doc_0/Pages/Page_0/Content.xml", "../../Res/font.ttf"));
        assertEquals("Doc_0/Document.xml", SafePackage.resolve("OFD.xml", "/Doc_0/Document.xml"));
        assertThrows(OfdhException.class, () -> SafePackage.resolve("OFD.xml", "../outside.xml"));
        assertThrows(
                OfdhException.class,
                () -> SafePackage.resolve("OFD.xml", "https://example.org/data"));
        assertThrows(OfdhException.class, () -> SafePackage.resolve("OFD.xml", "C:\\outside"));
        assertThrows(
                OfdhException.class, () -> SafePackage.resolve("OFD.xml", "//Doc_0/Document.xml"));
    }

    @Test
    void rejectsTraversalBeforeCreatingMemberFiles() throws Exception {
        Path input = zip("../outside.xml", "data");
        assertEquals(
                "PATH_INVALID",
                assertThrows(
                                OfdhException.class,
                                () -> SafePackage.open(input, ResourceLimits.defaults()))
                        .getCode());
        assertFalse(Files.exists(temporary.resolve("outside.xml")));
    }

    @Test
    void rejectsCaseAmbiguity() throws Exception {
        Map<String, String> members = new LinkedHashMap<>();
        members.put("OFD.xml", "a");
        members.put("ofd.xml", "b");
        Path input = zip(members);
        assertEquals(
                "ZIP_DUPLICATE_ENTRY",
                assertThrows(
                                OfdhException.class,
                                () -> SafePackage.open(input, ResourceLimits.defaults()))
                        .getCode());
    }

    @Test
    void enforcesExpandedByteBudget() throws Exception {
        Path input = zip("entry", String.join("", java.util.Collections.nCopies(1024, "x")));
        ResourceLimits limits = new ResourceLimits(4096, 128, 10, 10, 128);
        assertEquals(
                "LIMIT_EXCEEDED",
                assertThrows(OfdhException.class, () -> SafePackage.open(input, limits)).getCode());
    }

    @Test
    void rejectsEncryptionFlagBeforeParsingPayload() throws Exception {
        Path input = zip("entry", "data");
        byte[] bytes = Files.readAllBytes(input);
        for (int i = 0; i < bytes.length - 10; i++) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                bytes[i + 8] |= 1;
            }
        }
        Files.write(input, bytes);
        assertEquals(
                "SOURCE_PROFILE_UNSUPPORTED",
                assertThrows(
                                OfdhException.class,
                                () -> SafePackage.open(input, ResourceLimits.defaults()))
                        .getCode());
    }

    @Test
    void rejectsUnixSymlinkEntries() throws Exception {
        Path input = zip("entry", "target");
        byte[] bytes = Files.readAllBytes(input);
        for (int i = 0; i < bytes.length - 46; i++) {
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                bytes[i + 5] = 3;
                bytes[i + 40] = (byte) 0xff;
                bytes[i + 41] = (byte) 0xa1;
            }
        }
        Files.write(input, bytes);
        assertEquals(
                "ZIP_LINK_ENTRY",
                assertThrows(
                                OfdhException.class,
                                () -> SafePackage.open(input, ResourceLimits.defaults()))
                        .getCode());
    }

    @Test
    void rejectsANameThatDiffersBetweenLocalAndCentralHeaders() throws Exception {
        Path input = zip("entry.xml", "<entry/>");
        byte[] bytes = Files.readAllBytes(input);
        bytes[30] = 'x';
        Files.write(input, bytes);
        assertRejectedWithoutPublication(input, "ZIP_INVALID");
    }

    @Test
    void rejectsChangedStoredContentEvenWhenTheHeadersAgree() throws Exception {
        byte[] payload = "original payload".getBytes(StandardCharsets.UTF_8);
        CRC32 checksum = new CRC32();
        checksum.update(payload);
        Path input = temporary.resolve("stored.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(input))) {
            ZipEntry entry = new ZipEntry("entry.bin");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(checksum.getValue());
            output.putNextEntry(entry);
            output.write(payload);
            output.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(input);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int dataOffset =
                30
                        + Short.toUnsignedInt(header.getShort(26))
                        + Short.toUnsignedInt(header.getShort(28));
        bytes[dataOffset] ^= 1;
        Files.write(input, bytes);
        assertRejectedWithoutPublication(input, "ZIP_INVALID");
    }

    @Test
    void rejectsCentralEntriesThatReuseOneLocalMember() throws Exception {
        Map<String, String> members = new LinkedHashMap<>();
        members.put("a.xml", "identical payload");
        members.put("b.xml", "identical payload");
        Path input = zip(members);
        byte[] bytes = Files.readAllBytes(input);
        int first = signatureOffset(bytes, 0x02014b50);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int recordLength =
                46
                        + Short.toUnsignedInt(header.getShort(first + 28))
                        + Short.toUnsignedInt(header.getShort(first + 30))
                        + Short.toUnsignedInt(header.getShort(first + 32));
        int second = first + recordLength;
        assertEquals(0x02014b50, header.getInt(second));
        System.arraycopy(bytes, first, bytes, second, recordLength);
        Files.write(input, bytes);
        assertRejectedWithoutPublication(input, "ZIP_INVALID");
    }

    @Test
    void rejectsAPrefixedArchiveWithOtherwiseValidOffsets() throws Exception {
        Path input = zip("entry.xml", "<entry/>");
        byte[] original = Files.readAllBytes(input);
        int central = signatureOffset(original, 0x02014b50);
        int end = signatureOffset(original, 0x06054b50);
        int prefixLength = 4;
        byte[] prefixed = new byte[original.length + prefixLength];
        prefixed[0] = 'M';
        prefixed[1] = 'Z';
        System.arraycopy(original, 0, prefixed, prefixLength, original.length);
        ByteBuffer before = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer after = ByteBuffer.wrap(prefixed).order(ByteOrder.LITTLE_ENDIAN);
        after.putInt(central + prefixLength + 42, before.getInt(central + 42) + prefixLength);
        after.putInt(end + prefixLength + 16, before.getInt(end + 16) + prefixLength);
        Files.write(input, prefixed);
        try (ZipFile readable = new ZipFile(input.toFile())) {
            assertEquals(1, readable.size());
        }
        assertRejectedWithoutPublication(input, "SOURCE_PROFILE_UNSUPPORTED");
    }

    @Test
    void rejectsZip64DirectorySentinelsBeforeExtractingMembers() throws Exception {
        Path input = zip("entry.xml", "<entry/>");
        byte[] bytes = Files.readAllBytes(input);
        int end = signatureOffset(bytes, 0x06054b50);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort(end + 8, (short) 0xffff);
        header.putShort(end + 10, (short) 0xffff);
        header.putInt(end + 12, -1);
        header.putInt(end + 16, -1);
        Files.write(input, bytes);
        assertRejectedWithoutPublication(input, "SOURCE_PROFILE_UNSUPPORTED");
    }

    @Test
    void refusesToReplaceAnExistingOutput() throws Exception {
        Path input = zip("OFD.xml", "<OFD/>"), output = temporary.resolve("existing.ofd");
        Files.write(output, new byte[] {1, 2, 3});
        try (SafePackage archive = SafePackage.open(input, ResourceLimits.defaults())) {
            assertEquals(
                    "OUTPUT_EXISTS",
                    assertThrows(OfdhException.class, () -> archive.publish(output)).getCode());
        }
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(output));
    }

    @Test
    void publishesAnArchiveThatCanBeReadAgain() throws Exception {
        Path input = zip("OFD.xml", "<OFD/>"), output = temporary.resolve("published.ofd");
        try (SafePackage archive = SafePackage.open(input, ResourceLimits.defaults())) {
            archive.write("Doc_0/data.json", "{}".getBytes(StandardCharsets.UTF_8));
            archive.publish(output);
        }
        try (SafePackage archive = SafePackage.open(output, ResourceLimits.defaults())) {
            assertEquals(2, archive.entries().size());
            assertEquals("{}", new String(archive.read("Doc_0/data.json"), StandardCharsets.UTF_8));
        }
    }

    @Test
    void xmlRejectsExternalEntities() {
        byte[] input =
                "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///private'>]><x>&secret;</x>"
                        .getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "XML_INVALID", assertThrows(OfdhException.class, () -> Xml.parse(input)).getCode());
    }

    @Test
    void xmlSupportsAnyPrefixForTheOfdNamespace() throws Exception {
        org.w3c.dom.Document document =
                Xml.parse(
                        ("<x:OFD xmlns:x='" + Xml.NS + "'><x:DocBody/></x:OFD>")
                                .getBytes(StandardCharsets.UTF_8));
        assertNotNull(Xml.child(document.getDocumentElement(), "DocBody"));
        assertEquals(Xml.NS, document.getDocumentElement().getNamespaceURI());
    }

    private Path zip(String name, String data) throws Exception {
        Map<String, String> members = new LinkedHashMap<>();
        members.put(name, data);
        return zip(members);
    }

    private void assertRejectedWithoutPublication(Path input, String expectedCode)
            throws Exception {
        byte[] original = Files.readAllBytes(input);
        Path output = temporary.resolve("rejected-output.ofd");
        OfdhException failure =
                assertThrows(
                        OfdhException.class,
                        () -> {
                            try (SafePackage archive =
                                    SafePackage.open(input, ResourceLimits.defaults())) {
                                archive.publish(output);
                            }
                        });
        assertEquals(expectedCode, failure.getCode());
        assertFalse(Files.exists(output));
        assertArrayEquals(original, Files.readAllBytes(input));
    }

    private static int signatureOffset(byte[] bytes, int signature) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset <= bytes.length - 4; offset++) {
            if (buffer.getInt(offset) == signature) return offset;
        }
        throw new AssertionError("Expected ZIP record is missing from the test archive");
    }

    private Path zip(Map<String, String> members) throws Exception {
        Path path = temporary.resolve("input-" + java.util.UUID.randomUUID() + ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> member : members.entrySet()) {
                output.putNextEntry(new ZipEntry(member.getKey()));
                output.write(member.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }
}
