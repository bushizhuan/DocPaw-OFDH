package org.openofdh.core.internal;

import org.openofdh.core.api.PageSourceAdapter;

import java.util.Map;
import java.util.Objects;

/** 统一门面与底层服务的转换器注册规则。 */
public final class PageSourceAdapters {
    private PageSourceAdapters() {}

    public static void register(
            Map<String, PageSourceAdapter> adapters, PageSourceAdapter adapter) {
        Objects.requireNonNull(adapter, "来源适配器不能为空");
        String format = adapter.format();
        if (format == null || format.trim().isEmpty()) {
            throw new IllegalArgumentException("来源适配器必须声明格式");
        }
        if ("OFD".equals(format)) {
            throw new IllegalArgumentException("原生 OFD 由内置流程处理，不能注册转换器覆盖");
        }
        if (!"PDF".equals(format)) {
            throw new IllegalArgumentException("来源适配器的格式标识必须为 PDF，区分大小写且不能包含空格");
        }
        if (adapters.containsKey(format)) {
            throw new IllegalArgumentException("同一来源格式不能重复注册适配器");
        }
        adapters.put(format, adapter);
    }
}
