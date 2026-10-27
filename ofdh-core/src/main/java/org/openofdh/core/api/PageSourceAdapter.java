package org.openofdh.core.api;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 将 PDF 来源快照转换为未签署、资源完整的 OFD 文档。
 *
 * <p>当前请求契约仅接受 PDF 与原生 OFD。此扩展点用于替换 PDF 转换实现，不能注册其他格式或覆盖原生 OFD。 实现不应以整页图片、猜测字符或删除不支持内容的方式掩盖转换失败。
 *
 * <p>同一个实现可能被多个调用共享；若不支持并发，宿主应串行调用或分别创建处理入口。
 */
public interface PageSourceAdapter {
    /**
     * 声明可转换的来源格式，注册后须保持不变。
     *
     * @return 精确的 PDF 字符串，区分大小写，不含空格
     */
    String format();

    /**
     * 同步完成转换。调用方随后会重新打开产物，检查资源、引用、页面和文件包限额。
     *
     * @param source 调用方固定的来源快照，只读，不得修改或删除
     * @param target 指定的新 OFD 文件路径，父目录已存在；不得覆盖已有文件
     * @param limits 本次资源预算；实现还应为第三方解码器设置时间限制并响应线程中断
     * @throws IOException 转换失败、无法可靠保留内容、超出资源预算或操作取消
     */
    void convert(Path source, Path target, ResourceLimits limits) throws IOException;
}
