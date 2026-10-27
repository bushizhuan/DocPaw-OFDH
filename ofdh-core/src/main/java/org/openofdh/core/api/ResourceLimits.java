package org.openofdh.core.api;

/**
 * 单次操作的不可变资源预算，可被不同线程复用，不代表文档格式本身的限制。
 *
 * <p>字节预算用于输入、输出及展开过程的边界检查，不是 JVM 堆内存上限。 多任务并发会叠加资源消耗，宿主仍需设置并发数、操作期限和进程级内存预算。
 */
public final class ResourceLimits {
    private final long maxInputBytes;
    private final long maxExpandedBytes;
    private final int maxEntries;
    private final int maxPages;
    private final long maxClinicalBytes;

    /**
     * 指定文件处理限额，所有数值均须大于零。
     *
     * @param maxInputBytes 单个输入和发布文件的最大字节数
     * @param maxExpandedBytes 文件包累计展开字节数上限
     * @param maxEntries 文件包成员数量上限
     * @param maxPages 单文档页面数量上限
     * @param maxClinicalBytes 单个临床 JSON 的最大字节数
     * @throws IllegalArgumentException 任一限额不是正数
     */
    public ResourceLimits(
            long maxInputBytes,
            long maxExpandedBytes,
            int maxEntries,
            int maxPages,
            long maxClinicalBytes) {
        if (maxInputBytes < 1
                || maxExpandedBytes < 1
                || maxEntries < 1
                || maxPages < 1
                || maxClinicalBytes < 1) {
            throw new IllegalArgumentException("资源限额必须大于零");
        }
        this.maxInputBytes = maxInputBytes;
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxEntries = maxEntries;
        this.maxPages = maxPages;
        this.maxClinicalBytes = maxClinicalBytes;
    }

    /**
     * 返回输入 100 MiB、展开 1 GiB、10,000 个成员、500 页及临床 JSON 10 MiB 的预算。
     *
     * @return 独立且不可变的默认限额
     */
    public static ResourceLimits defaults() {
        return new ResourceLimits(100L << 20, 1L << 30, 10000, 500, 10L << 20);
    }

    public long getMaxInputBytes() {
        return maxInputBytes;
    }

    public long getMaxExpandedBytes() {
        return maxExpandedBytes;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public int getMaxPages() {
        return maxPages;
    }

    public long getMaxClinicalBytes() {
        return maxClinicalBytes;
    }
}
