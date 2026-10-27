package org.openofdh.core.layout;

import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.io.SafePackage;

import java.io.IOException;
import java.nio.file.Path;

/** 使用现有 OFD 引用和 ZIP 成员顺序改善渐进读取，不增加私有索引。 */
public final class PageOrderOptimizer {
    private PageOrderOptimizer() {}

    /** 按文档页序前置页面及其依赖，保留全部成员的原始内容。 输出路径必须不存在；返回实际写入的布局，供调用方记录和核验。 */
    public static PageLayout publish(SafePackage archive, Path output, ResourceLimits limits)
            throws IOException {
        PageLayout layout = PageDependencyPlanner.plan(archive, limits);
        archive.publish(output, layout.getEntryOrder());
        return layout;
    }
}
