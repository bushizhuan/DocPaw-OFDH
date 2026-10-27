package org.openofdh.core.check;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class CompressionInspector {
    private final Inspection inspection;

    CompressionInspector(Inspection inspection) {
        this.inspection = inspection;
    }

    void inspect(Path validatedSnapshot) throws IOException {
        int checked = 0;
        // 私有快照通过 SafePackage 检查后，只读取 ZIP 目录元数据。
        try (ZipFile zip = new ZipFile(validatedSnapshot.toFile(), Charset.forName("CP437"))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getSize() == 0 || !textMember(entry.getName()))
                    continue;
                checked++;
                if (entry.getMethod() != ZipEntry.DEFLATED) {
                    inspection.fail(
                            Rule.TEXT_COMPRESSION,
                            "非空 XML、JSON 或 TXT 成员未按本项目约束使用 Deflate；本项不代表完整标准的压缩或线性化评估。",
                            entry.getName());
                    return;
                }
            }
        }
        if (checked == 0) {
            inspection.absent(Rule.TEXT_COMPRESSION, "未发现非空 XML、JSON 或 TXT 成员。", "");
        } else {
            inspection.pass(
                    Rule.TEXT_COMPRESSION,
                    "共 " + checked + " 个非空 XML、JSON 或 TXT 成员均使用 Deflate；其他成员类型及线性化不在本规则范围内。",
                    "");
        }
    }

    private static boolean textMember(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xml") || lower.endsWith(".json") || lower.endsWith(".txt");
    }
}
