package org.openofdh.core.io;

import org.openofdh.core.api.OfdhException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class FileSafety {
    private FileSafety() {}

    public static Path privateDirectory(String prefix) throws IOException {
        Path directory;
        if (java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews()
                .contains("posix")) {
            directory =
                    Files.createTempDirectory(
                            prefix,
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
        } else {
            directory = Files.createTempDirectory(prefix);
            java.nio.file.attribute.AclFileAttributeView view =
                    Files.getFileAttributeView(
                            directory, java.nio.file.attribute.AclFileAttributeView.class);
            if (view != null) {
                java.nio.file.attribute.AclEntry entry =
                        java.nio.file.attribute.AclEntry.newBuilder()
                                .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                                .setPrincipal(view.getOwner())
                                .setPermissions(
                                        java.util.EnumSet.allOf(
                                                java.nio.file.attribute.AclEntryPermission.class))
                                .setFlags(
                                        java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT,
                                        java.nio.file.attribute.AclEntryFlag.FILE_INHERIT)
                                .build();
                view.setAcl(java.util.Collections.singletonList(entry));
            }
        }
        return directory;
    }

    public static Path resolveInput(Path inputRoot, String relative) throws IOException {
        SafePackage.validateName(relative, false);
        Path root = unlinkedDirectory(inputRoot);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new OfdhException("PATH_OUTSIDE_ROOT", "输入路径超出指定的根目录。");
        Path current = root;
        for (Path component : root.relativize(target)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)
                    || !current.toRealPath(LinkOption.NOFOLLOW_LINKS)
                            .equals(current.toRealPath())) {
                throw new OfdhException("PATH_OUTSIDE_ROOT", "输入路径不能经过符号链接或目录联接。");
            }
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new OfdhException("INPUT_INVALID", "输入必须是普通文件。");
        }
        return target;
    }

    public static Path unlinkedDirectory(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            BasicFileAttributes attributes =
                    Files.readAttributes(
                            current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new OfdhException("PATH_OUTSIDE_ROOT", "目录不能经过符号链接、联接或路径重定向。");
            }
        }
        Path real = absolute.toRealPath();
        if (!Files.isDirectory(real)
                || !absolute.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(real)) {
            throw new OfdhException("PATH_OUTSIDE_ROOT", "目录不能发生路径重定向。");
        }
        return real;
    }

    public static String snapshot(Path source, Path target, long limit) throws IOException {
        BasicFileAttributes before =
                Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > limit) {
            throw new OfdhException(
                    before.size() > limit ? "LIMIT_EXCEEDED" : "INPUT_INVALID",
                    "输入文件无法在指定的资源限额内读取。");
        }
        MessageDigest digest = digest();
        long count = 0;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                OutputStream output =
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancelled();
                count += read;
                if (count > limit) throw new OfdhException("LIMIT_EXCEEDED", "输入文件超过资源限额。");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        BasicFileAttributes after =
                Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile()
                || count != before.size()
                || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new OfdhException("INPUT_CHANGED", "建立快照期间输入文件发生变化。");
        }
        return hex(digest.digest());
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancelled();
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    public static void cancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new OfdhException("OPERATION_CANCELLED", "操作已中断。");
        }
    }

    public static void publish(Path temporary, Path target) throws IOException {
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            // 硬链接只创建新目标；部分文件系统的 ATOMIC_MOVE 可能覆盖已有文件。
            Files.createLink(target, temporary);
        } catch (java.nio.file.FileAlreadyExistsException error) {
            throw new OfdhException("OUTPUT_EXISTS", "输出文件已存在。", error);
        } catch (UnsupportedOperationException error) {
            throw new OfdhException("ATOMIC_PUBLISH_UNSUPPORTED", "输出文件系统不支持所需的原子发布方式。", error);
        }
        try {
            Files.delete(temporary);
        } catch (IOException cleanup) {
            // 发布已完成，清理失败不能使调用方误判为文档生成失败。
            temporary.toFile().deleteOnExit();
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(
                root,
                new java.nio.file.SimpleFileVisitor<Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(
                            Path file, BasicFileAttributes attributes) throws IOException {
                        Files.delete(file);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult postVisitDirectory(
                            Path directory, IOException error) throws IOException {
                        if (error != null) throw error;
                        Files.delete(directory);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
    }
}
