package org.openofdh.example;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.openofdh.core.api.Ofdh;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.prepare.PreparedDocument;
import org.openofdh.core.validation.ValidationReport;
import org.openofdh.pdf.PdfSourceAdapter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 从使用者提供的请求和来源文件生成待签署文档。 */
public final class PrepareDocument {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PrepareDocument() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", "INPUT");
        result.put("artifactCreated", false);
        result.put("issues", Collections.emptyList());
        result.put("truncated", false);
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            outcome(result, "HELP", "依次提供请求 JSON、输入根目录、新的输出 OFD 和报告 JSON 路径；父目录须已存在。");
            result.put(
                    "usage",
                    "java -jar java-sdk-consumer-0.2.0.jar 请求JSON 输入根目录 输出OFD 报告JSON"
                        + " [--optimize-page-order]");
            result.put("reportStatus", "NOT_WRITTEN");
            return emit(out, result, 0);
        }
        Path report = null;
        int status;
        try {
            if (args.length != 4
                    && !(args.length == 5 && "--optimize-page-order".equals(args[4]))) {
                outcome(
                        result,
                        "ARGUMENT_INVALID",
                        "用法：请求JSON 输入根目录 输出OFD 报告JSON [--optimize-page-order]；使用 --help 查看说明。");
                return emit(err, result, 64);
            }
            Path request = Paths.get(args[0]);
            Path inputRoot = Paths.get(args[1]);
            Path output = outputPath(Paths.get(args[2]));
            Path requestedReport = outputPath(Paths.get(args[3]));
            if (output.equals(requestedReport)) {
                throw new OfdhException("OUTPUT_INVALID", "文档和报告必须使用不同的输出路径");
            }
            requireAbsent(requestedReport);
            report = requestedReport;
            requireAbsent(output);

            Ofdh sdk =
                    Ofdh.builder()
                            .sourceAdapter(new PdfSourceAdapter())
                            .optimizePageOrder(args.length == 5)
                            .build();
            result.put("stage", "VALIDATION");
            ValidationReport validation = sdk.validate(request);
            result.put("validation", validation);
            if (!validation.isValid()) {
                outcome(result, "REQUEST_INVALID", "请求字段不符合要求，请按 issues 修正输入。");
                result.put("issues", validation.getIssues());
                result.put("truncated", validation.isTruncated());
                return deliver(report, result, 2, out, err);
            }

            result.put("stage", "PREPARING");
            PreparedDocument prepared = sdk.prepare(request, inputRoot, output);
            result.put("artifactCreated", true);
            result.put("stage", "PREPARED");
            result.put("sha256", prepared.getSha256());
            CheckReport inspection = sdk.check(prepared.getPath(), CheckProfile.PREPARED);
            result.put("inspection", inspection);
            if ("ISSUES_FOUND".equals(inspection.getAssessment())) {
                outcome(result, "PREPARED_CHECK_FAILED", "文件已生成，但复核发现问题，请检查报告。");
                status = 2;
            } else {
                outcome(result, "PREPARED", "待签署文件已生成，请查看检查报告中的未覆盖项目。");
                status = 0;
            }
        } catch (OfdhException failure) {
            outcome(result, failure.getCode(), "处理未完成，请按错误码和字段问题检查输入或配置。");
            result.put("issues", failure.getIssues());
            result.put("truncated", failure.isTruncated());
            status =
                    "OPERATION_CANCELLED".equals(failure.getCode())
                                    || "ATOMIC_PUBLISH_UNSUPPORTED".equals(failure.getCode())
                            ? 4
                            : 2;
        } catch (IOException failure) {
            outcome(result, "IO_FAILURE", "文件读写失败，请检查路径、权限和存储空间。");
            status = 4;
        } catch (IllegalArgumentException failure) {
            outcome(result, "ARGUMENT_INVALID", "路径或接入参数不正确。");
            status = 64;
        } catch (RuntimeException failure) {
            outcome(result, "PROCESSING_FAILURE", "处理失败，请检查运行环境和接入配置。");
            status = 4;
        }
        return deliver(report, result, status, out, err);
    }

    private static int deliver(
            Path report, Map<String, Object> result, int status, PrintStream out, PrintStream err) {
        if (report == null) {
            result.put("reportStatus", "NOT_WRITTEN");
        } else {
            try {
                result.put("reportStatus", "WRITTEN");
                writeReport(report, JSON.writeValueAsBytes(result));
            } catch (IOException failure) {
                result.put("reportStatus", "FAILED");
                result.put("previousCode", result.get("code"));
                outcome(
                        result,
                        "REPORT_WRITE_FAILED",
                        Boolean.TRUE.equals(result.get("artifactCreated"))
                                ? "文档已生成，但报告写入失败；请保留文档并重新执行检查。"
                                : "报告写入失败，本次未生成文档。");
                status = 4;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>(result);
        summary.remove("validation");
        summary.remove("inspection");
        return emit(status == 0 ? out : err, summary, status);
    }

    private static Path outputPath(Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        if (absolute.getParent() == null || absolute.getFileName() == null) {
            throw new OfdhException("OUTPUT_INVALID", "输出必须指定文件名");
        }
        return FileSafety.unlinkedDirectory(absolute.getParent()).resolve(absolute.getFileName());
    }

    private static void requireAbsent(Path file) throws OfdhException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new OfdhException("OUTPUT_EXISTS", "输出文件已存在");
        }
    }

    private static void writeReport(Path target, byte[] bytes) throws IOException {
        Path checked = outputPath(target);
        requireAbsent(checked);
        Path temporary =
                Files.createTempFile(checked.getParent(), ".ofdh-consumer-report-", ".tmp");
        boolean published = false;
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            FileSafety.publish(temporary, checked);
            published = true;
        } finally {
            if (!published) Files.deleteIfExists(temporary);
        }
    }

    private static void outcome(Map<String, Object> result, String code, String message) {
        result.put("code", code);
        result.put("message", message);
    }

    private static int emit(PrintStream stream, Map<String, Object> result, int status) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(result);
            stream.write(bytes, 0, bytes.length);
            stream.write('\n');
            stream.flush();
            return stream.checkError() ? 4 : status;
        } catch (IOException failure) {
            byte[] bytes =
                    ("{\"code\":\"SERIALIZATION_FAILURE\"," + "\"message\":\"结果序列化失败。\"}\n")
                            .getBytes(StandardCharsets.UTF_8);
            stream.write(bytes, 0, bytes.length);
            stream.flush();
            return 4;
        }
    }
}
