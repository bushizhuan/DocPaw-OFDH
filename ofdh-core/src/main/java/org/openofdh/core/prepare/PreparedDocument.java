package org.openofdh.core.prepare;

import java.nio.file.Path;

/**
 * 已发布待签署文件的不可变处理结果，可在不同线程之间传递。
 *
 * <p>path 指向新产物；fileId 是本次生成的文件标识，不等同于请求的业务 documentId。 generatedAt 是本次生成时间，不是来源记录创建时间。sourceSha256
 * 对应来源版式快照， sha256 对应已发布 OFD-H 文件；两者均为十六进制 SHA-256。
 *
 * <p>本结果只表示 PREPARED 阶段已完成。调用方仍需签署、检查并归档，不能据此认定最终标准符合性。 路径所指文件由宿主保管；后续文件被修改时，应重新计算摘要而非继续使用本结果。
 */
public final class PreparedDocument {
    private final Path path;
    private final String fileId;
    private final String generatedAt;
    private final String sourceSha256;
    private final String sha256;

    PreparedDocument(
            Path path, String fileId, String generatedAt, String sourceSha256, String sha256) {
        this.path = path;
        this.fileId = fileId;
        this.generatedAt = generatedAt;
        this.sourceSha256 = sourceSha256;
        this.sha256 = sha256;
    }

    public Path getPath() {
        return path;
    }

    public String getFileId() {
        return fileId;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public String getSha256() {
        return sha256;
    }
}
