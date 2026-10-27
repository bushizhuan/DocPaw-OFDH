package org.openofdh.core.check;

import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.io.SafePackage;

import java.io.IOException;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 检查文件，不修改内容，也不访问外部服务。 */
public final class CheckService {
    private final ResourceLimits limits;

    public CheckService(ResourceLimits limits) {
        this.limits = Objects.requireNonNull(limits, "资源限额不能为空");
    }

    public CheckReport check(Path file, CheckProfile profile) throws IOException {
        return check(file, profile, Collections.<X509Certificate>emptySet());
    }

    /** 信任锚由调用方明确提供；不联网获取证书或查询吊销状态。 */
    public CheckReport check(Path file, CheckProfile profile, Set<X509Certificate> trustRoots)
            throws IOException {
        Objects.requireNonNull(file, "待检查文件不能为空");
        Objects.requireNonNull(profile, "检查阶段不能为空");
        Set<X509Certificate> anchors =
                Collections.unmodifiableSet(
                        new HashSet<>(Objects.requireNonNull(trustRoots, "信任锚集合不能为空")));
        if (anchors.contains(null)) throw new IllegalArgumentException("信任锚不能包含空值。");
        Path directory = FileSafety.privateDirectory("ofdh-check-");
        try {
            Path snapshot = directory.resolve("input.ofd");
            String digest = FileSafety.snapshot(file, snapshot, limits.getMaxInputBytes());
            Inspection inspection;
            try (SafePackage pkg = SafePackage.open(snapshot, limits)) {
                inspection = new Inspection(pkg, profile, anchors);
                inspection.pass(Rule.CONTAINER, "文件包已通过受限 ZIP 展开及路径检查。", "");
                new CompressionInspector(inspection).inspect(snapshot);
                new DocumentInspector(inspection, limits).inspect();
            } catch (IOException | IllegalArgumentException e) {
                FileSafety.cancelled();
                inspection = new Inspection(null, profile, anchors);
                inspection.fail(Rule.CONTAINER, "无法将该文件作为安全可读的 OFD 文件包打开。", "");
            }
            return new CheckReport(
                    file.getFileName().toString(),
                    digest,
                    profile,
                    inspection.results(),
                    inspection.signatures);
        } finally {
            FileSafety.deleteTree(directory);
        }
    }
}
