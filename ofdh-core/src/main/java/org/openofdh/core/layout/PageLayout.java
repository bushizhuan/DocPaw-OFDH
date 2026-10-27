package org.openofdh.core.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 包成员的读取顺序；只描述布局，不修改 OFD 内容或增加格式字段。 */
public final class PageLayout {
    private final String document;
    private final List<String> startup;
    private final List<Page> pages;
    private final List<String> contentOrder;
    private final List<String> entryOrder;
    private final List<String> xmlEntries;

    PageLayout(
            String document,
            List<String> startup,
            List<Page> pages,
            Set<String> entries,
            Set<String> xmlEntries) {
        this.document = document;
        this.startup = immutable(startup);
        this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
        LinkedHashSet<String> ordered = new LinkedHashSet<>(startup);
        for (Page page : pages) ordered.addAll(page.getEntries());
        this.contentOrder = immutable(new ArrayList<>(ordered));
        List<String> remainder = new ArrayList<>(entries);
        Collections.sort(remainder);
        ordered.addAll(remainder);
        this.entryOrder = immutable(new ArrayList<>(ordered));
        List<String> parsed = new ArrayList<>(xmlEntries);
        Collections.sort(parsed);
        this.xmlEntries = immutable(parsed);
    }

    public String getDocument() {
        return document;
    }

    public List<String> getStartup() {
        return startup;
    }

    public List<Page> getPages() {
        return pages;
    }

    /** 启动目录以及按文档页序展开的页面依赖，不包含无关附件正文。 */
    public List<String> getContentOrder() {
        return contentOrder;
    }

    /** 全部文件成员的确定性顺序；附件、签章等剩余成员按包内路径排序置后。 */
    public List<String> getEntryOrder() {
        return entryOrder;
    }

    /** 实际按 XML 解析的成员，包括不使用 .xml 扩展名的外链内容，供编码检查复用。 */
    public List<String> getXmlEntries() {
        return xmlEntries;
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Page {
        private final String id;
        private final String path;
        private final List<String> entries;

        Page(String id, String path, List<String> entries) {
            this.id = id;
            this.path = path;
            this.entries = immutable(entries);
        }

        public String getId() {
            return id;
        }

        public String getPath() {
            return path;
        }

        public List<String> getEntries() {
            return entries;
        }
    }
}
