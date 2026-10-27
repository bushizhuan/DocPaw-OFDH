package org.openofdh.core.sign;

import org.openofdh.core.check.CheckReport;

import java.nio.file.Path;

/**
 * 签后检查通过并已发布的不可变结果，摘要与报告均绑定最终产物的实际字节。
 *
 * <p>path 指向签署产物，sha256 是十六进制 SHA-256，report 为 FINAL 阶段报告。
 * 密码验签通过不等于业务授权、证书信任、吊销状态或完整标准符合性均已确认；应分别读取报告。 此对象可共享，但不能防止宿主随后修改路径所指文件。
 */
public final class SignedDocument {
    private final Path path;
    private final String sha256;
    private final CheckReport report;

    SignedDocument(Path path, String sha256, CheckReport report) {
        this.path = path;
        this.sha256 = sha256;
        this.report = report;
    }

    public Path getPath() {
        return path;
    }

    public String getSha256() {
        return sha256;
    }

    public CheckReport getReport() {
        return report;
    }
}
