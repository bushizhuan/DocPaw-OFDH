package org.openofdh.core.io;

import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.w3c.dom.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 单次操作使用的文件包，实例不能跨线程共享。 */
public final class SafePackage implements AutoCloseable {
    private final Path directory;
    private final ResourceLimits limits;
    private final Set<String> names = new LinkedHashSet<>();
    private long expandedBytes;
    private boolean closed;

    private SafePackage(Path directory, ResourceLimits limits) {
        this.directory = directory;
        this.limits = limits;
    }

    public static SafePackage open(Path source, ResourceLimits limits) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new OfdhException("INPUT_INVALID", "文件包必须是普通文件。");
        }
        if (Files.size(source) > limits.getMaxInputBytes()) {
            throw new OfdhException("LIMIT_EXCEEDED", "文件包超过输入大小限额。");
        }
        inspectDirectory(source, limits);
        SafePackage result = new SafePackage(FileSafety.privateDirectory("ofdh-package-"), limits);
        try {
            result.extract(source);
            return result;
        } catch (IOException | RuntimeException error) {
            try {
                result.close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private void extract(Path source) throws IOException {
        Set<String> foldedNames = new HashSet<>();
        try (ZipFile zip = new ZipFile(source.toFile(), Charset.forName("CP437"))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                FileSafety.cancelled();
                ZipEntry entry = entries.nextElement();
                if (++count > limits.getMaxEntries())
                    throw new OfdhException("LIMIT_EXCEEDED", "文件包成员数量超过限额。");
                String name = entry.getName();
                validateName(name, entry.isDirectory());
                String normalized =
                        entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                String folded =
                        Normalizer.normalize(normalized, Normalizer.Form.NFC)
                                .toLowerCase(Locale.ROOT);
                if (!foldedNames.add(folded))
                    throw new OfdhException("ZIP_DUPLICATE_ENTRY", "文件包包含重复或含义不明确的成员名称。");
                Path target = directory.resolve(normalized);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (entry.getSize() < 0
                        || entry.getSize() > limits.getMaxExpandedBytes() - expandedBytes) {
                    throw new OfdhException("LIMIT_EXCEEDED", "文件包展开大小超过限额。");
                }
                Files.createDirectories(target.getParent());
                CRC32 crc = new CRC32();
                long size = 0;
                try (InputStream input = zip.getInputStream(entry);
                        OutputStream output =
                                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[32768];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        FileSafety.cancelled();
                        size += read;
                        expandedBytes += read;
                        if (expandedBytes > limits.getMaxExpandedBytes()
                                || size > entry.getSize()) {
                            throw new OfdhException("LIMIT_EXCEEDED", "文件包展开大小超过限额。");
                        }
                        crc.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new OfdhException("ZIP_INVALID", "文件包成员的长度或校验和不正确。");
                }
                names.add(name);
            }
        } catch (java.util.zip.ZipException error) {
            throw new OfdhException("ZIP_INVALID", "无法解析文件包。", error);
        }
    }

    static void validateName(String name, boolean directory) throws IOException {
        if (name == null
                || name.isEmpty()
                || name.length() > 1024
                || name.startsWith("/")
                || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0) {
            throw new OfdhException("PATH_INVALID", "文件包成员路径或输入路径不合法。");
        }
        String value =
                directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String component : value.split("/", -1)) {
            if (component.isEmpty()
                    || component.equals(".")
                    || component.equals("..")
                    || component.endsWith(".")
                    || component.endsWith(" ")
                    || component.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) {
                throw new OfdhException("PATH_INVALID", "路径含有歧义或越界的组成部分。");
            }
            for (int i = 0; i < component.length(); i++) {
                char ch = component.charAt(i);
                if (ch < 32 || ch == 127 || "<>\"|?*".indexOf(ch) >= 0) {
                    throw new OfdhException("PATH_INVALID", "路径包含不允许的字符。");
                }
            }
        }
    }

    /** 解析 OFD 的 ST_Loc；开头的斜杠表示文件包根目录。 */
    public static String resolve(String owner, String reference) throws IOException {
        if (reference == null
                || reference.isEmpty()
                || !reference.equals(reference.trim())
                || reference.indexOf('\\') >= 0
                || reference.indexOf(':') >= 0
                || reference.indexOf('?') >= 0
                || reference.indexOf('#') >= 0) {
            throw new OfdhException("REFERENCE_INVALID", "引用不是受支持的包内路径。");
        }
        validateName(owner, false);
        ArrayDeque<String> segments = new ArrayDeque<>();
        if (!reference.startsWith("/")) {
            int slash = owner.lastIndexOf('/');
            if (slash >= 0) Collections.addAll(segments, owner.substring(0, slash).split("/"));
        }
        String relative = reference.startsWith("/") ? reference.substring(1) : reference;
        for (String part : relative.split("/", -1)) {
            if (part.equals(".")) continue;
            if (part.equals("..")) {
                if (segments.isEmpty())
                    throw new OfdhException("PATH_OUTSIDE_ROOT", "引用路径超出文件包根目录。");
                segments.removeLast();
            } else {
                if (part.isEmpty()) throw new OfdhException("REFERENCE_INVALID", "引用路径包含空的组成部分。");
                segments.addLast(part);
            }
        }
        String result = String.join("/", segments);
        validateName(result, false);
        return result;
    }

    public Set<String> entries() {
        return Collections.unmodifiableSet(names);
    }

    public boolean contains(String name) {
        return names.contains(name);
    }

    public Path path(String name) throws IOException {
        requireOpen();
        validateName(name, false);
        if (!names.contains(name))
            throw new OfdhException("RESOURCE_MISSING", "引用的文件包成员不存在：" + name);
        return directory.resolve(name);
    }

    public byte[] read(String name) throws IOException {
        Path path = path(name);
        if (Files.size(path) > Xml.MAX_XML_BYTES)
            throw new OfdhException("LIMIT_EXCEEDED", "文件包成员超过内存读取限额。");
        return Files.readAllBytes(path);
    }

    public Document readXml(String name) throws IOException {
        return Xml.parse(path(name));
    }

    public void write(String name, byte[] bytes) throws IOException {
        requireOpen();
        validateName(name, false);
        Path target = directory.resolve(name);
        long previous = names.contains(name) ? Files.size(target) : 0;
        if (expandedBytes - previous + bytes.length > limits.getMaxExpandedBytes()
                || (!names.contains(name) && names.size() >= limits.getMaxEntries())) {
            throw new OfdhException("LIMIT_EXCEEDED", "输出超过文件包资源限额。");
        }
        if (!names.contains(name)) {
            for (String existing : names) {
                if (existing.equalsIgnoreCase(name))
                    throw new OfdhException("ZIP_DUPLICATE_ENTRY", "输出成员名称与已有成员冲突。");
            }
        }
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        names.add(name);
        expandedBytes += bytes.length - previous;
    }

    public void writeXml(String name, Document document) throws IOException {
        write(name, Xml.serialize(document));
    }

    public void publish(Path target) throws IOException {
        List<String> ordered = new ArrayList<>(names);
        ordered.sort(
                (left, right) ->
                        left.equals(right)
                                ? 0
                                : left.equals("OFD.xml")
                                        ? -1
                                        : right.equals("OFD.xml") ? 1 : left.compareTo(right));
        publish(target, ordered);
    }

    /** 按调用方已验证的布局写入；顺序必须恰好包含全部成员，不能隐式遗漏内容。 */
    public void publish(Path target, List<String> ordered) throws IOException {
        requireOpen();
        ordered = new ArrayList<>(ordered);
        if (ordered.size() != names.size() || !new HashSet<>(ordered).equals(names)) {
            throw new OfdhException("ZIP_LAYOUT_INVALID", "输出布局必须恰好包含全部文件包成员。");
        }
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new OfdhException("OUTPUT_INVALID", "输出父目录必须已存在，且不能经过链接或联接。");
        }
        parent = FileSafety.unlinkedDirectory(parent);
        absolute = parent.resolve(absolute.getFileName());
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS))
            throw new OfdhException("OUTPUT_EXISTS", "输出文件已存在。");
        Path temporary = Files.createTempFile(parent, ".ofdh-", ".tmp");
        boolean committed = false;
        try {
            try (ZipOutputStream output =
                    new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                output.setLevel(6);
                for (String name : ordered) {
                    FileSafety.cancelled();
                    ZipEntry entry = new ZipEntry(name);
                    entry.setTime(315532800000L);
                    output.putNextEntry(entry);
                    Files.copy(path(name), output);
                    output.closeEntry();
                }
            }
            if (Files.size(temporary) > limits.getMaxInputBytes())
                throw new OfdhException("LIMIT_EXCEEDED", "输出文件包超过字节大小限额。");
            try (SafePackage reopened = open(temporary, limits)) {
                if (!new ArrayList<>(reopened.entries()).equals(ordered))
                    throw new OfdhException("ZIP_INVALID", "输出成员或布局校验失败。");
            }
            FileSafety.publish(temporary, absolute);
            committed = true;
        } finally {
            if (!committed) Files.deleteIfExists(temporary);
        }
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("文件包已关闭。");
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            FileSafety.deleteTree(directory);
        }
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return (u16(bytes, offset) & 65535L) | ((u16(bytes, offset + 2) & 65535L) << 16);
    }

    private static void inspectDirectory(Path source, ResourceLimits limits) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "r")) {
            long length = file.length();
            if (length < 22) throw new OfdhException("ZIP_INVALID", "文件包缺少 ZIP 目录。");
            byte[] tail = new byte[(int) Math.min(length, 65557)];
            file.seek(length - tail.length);
            file.readFully(tail);
            int end = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) {
                    end = i;
                    break;
                }
            }
            if (end < 0) throw new OfdhException("ZIP_INVALID", "文件包的 ZIP 目录不合法。");
            int count = u16(tail, end + 10);
            long centralSize = u32(tail, end + 12), centralOffset = u32(tail, end + 16);
            if (u16(tail, end + 4) != 0
                    || u16(tail, end + 6) != 0
                    || u16(tail, end + 8) != count
                    || count == 65535
                    || centralSize == 0xffffffffL
                    || centralOffset == 0xffffffffL) {
                throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "当前处理范围不支持分卷或 ZIP64 文件包。");
            }
            if (count > limits.getMaxEntries())
                throw new OfdhException("LIMIT_EXCEEDED", "文件包成员数量超过限额。");
            if (centralOffset + centralSize != length - tail.length + end)
                throw new OfdhException("ZIP_INVALID", "ZIP 目录偏移不一致。");
            long cursor = centralOffset;
            List<long[]> regions = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (cursor + 46 > centralOffset + centralSize)
                    throw new OfdhException("ZIP_INVALID", "ZIP 目录内容不完整。");
                file.seek(cursor);
                byte[] header = new byte[46];
                file.readFully(header);
                if (u32(header, 0) != 0x02014b50L)
                    throw new OfdhException("ZIP_INVALID", "ZIP 目录成员不合法。");
                int flags = u16(header, 8), method = u16(header, 10), nameLength = u16(header, 28);
                int unixType = (int) ((u32(header, 38) >>> 16) & 0170000);
                if ((flags & (1 | 64 | 8192)) != 0)
                    throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "不支持加密的 ZIP 成员。");
                if (unixType != 0 && unixType != 0100000 && unixType != 0040000)
                    throw new OfdhException("ZIP_LINK_ENTRY", "禁止 ZIP 链接成员及特殊类型成员。");
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED)
                    throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "不支持该 ZIP 压缩方法。");
                byte[] name = new byte[nameLength];
                file.readFully(name);
                long localOffset = u32(header, 42), compressed = u32(header, 20);
                if (localOffset + 30 > centralOffset)
                    throw new OfdhException("ZIP_INVALID", "ZIP 本地文件头偏移不合法。");
                file.seek(localOffset);
                byte[] local = new byte[30];
                file.readFully(local);
                long dataEnd = localOffset + 30 + u16(local, 26) + u16(local, 28) + compressed;
                if (u32(local, 0) != 0x04034b50L
                        || u16(local, 6) != flags
                        || u16(local, 8) != method
                        || u16(local, 26) != nameLength
                        || dataEnd > centralOffset) {
                    throw new OfdhException("ZIP_INVALID", "ZIP 本地文件头与中央目录文件头不一致。");
                }
                byte[] localName = new byte[nameLength];
                file.readFully(localName);
                if (!java.util.Arrays.equals(name, localName))
                    throw new OfdhException("ZIP_INVALID", "ZIP 成员名称不一致。");
                if ((flags & 8) == 0) {
                    if (u32(local, 14) != u32(header, 16)
                            || u32(local, 18) != compressed
                            || u32(local, 22) != u32(header, 24)) {
                        throw new OfdhException("ZIP_INVALID", "ZIP 成员在本地文件头与中央目录中的大小不一致。");
                    }
                } else {
                    file.seek(dataEnd);
                    byte[] descriptor = new byte[16];
                    if (dataEnd + 12 > centralOffset)
                        throw new OfdhException("ZIP_INVALID", "缺少 ZIP 数据描述符。");
                    file.readFully(descriptor, 0, 12);
                    int offset = 0;
                    if (u32(descriptor, 0) == 0x08074b50L) {
                        if (dataEnd + 16 > centralOffset)
                            throw new OfdhException("ZIP_INVALID", "ZIP 数据描述符不完整。");
                        file.readFully(descriptor, 12, 4);
                        offset = 4;
                    }
                    if (u32(descriptor, offset) != u32(header, 16)
                            || u32(descriptor, offset + 4) != compressed
                            || u32(descriptor, offset + 8) != u32(header, 24)) {
                        throw new OfdhException("ZIP_INVALID", "ZIP 数据描述符与目录记录不一致。");
                    }
                    dataEnd += 12 + offset;
                }
                regions.add(new long[] {localOffset, dataEnd});
                cursor += 46L + nameLength + u16(header, 30) + u16(header, 32);
            }
            if (cursor != centralOffset + centralSize)
                throw new OfdhException("ZIP_INVALID", "ZIP 目录含有非预期数据。");
            regions.sort(java.util.Comparator.comparingLong(region -> region[0]));
            if (!regions.isEmpty() && regions.get(0)[0] != 0) {
                throw new OfdhException("SOURCE_PROFILE_UNSUPPORTED", "不支持带前置数据的 ZIP 文件包。");
            }
            long previousEnd = 0;
            for (long[] region : regions) {
                if (region[0] < previousEnd)
                    throw new OfdhException("ZIP_INVALID", "ZIP 成员的数据区域相互重叠。");
                previousEnd = region[1];
            }
        } catch (java.io.EOFException error) {
            throw new OfdhException("ZIP_INVALID", "ZIP 文件包内容不完整。", error);
        }
    }
}
