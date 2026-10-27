package org.openofdh.cli;

import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.OfdhException;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.check.CheckService;
import org.openofdh.core.internal.JsonIO;
import org.openofdh.core.io.FileSafety;
import org.openofdh.core.prepare.PrepareService;
import org.openofdh.core.prepare.PreparedDocument;
import org.openofdh.core.sign.Pkcs12SignProvider;
import org.openofdh.core.sign.SignedDocument;
import org.openofdh.core.sign.SigningService;
import org.openofdh.core.validation.ClinicalSchemaRegistry;
import org.openofdh.core.validation.ClinicalSchemas;
import org.openofdh.core.validation.RequestReader;
import org.openofdh.core.validation.ValidationIssue;
import org.openofdh.core.validation.ValidationReport;
import org.openofdh.pdf.PdfSourceAdapter;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "ofdh",
        mixinStandardHelpOptions = true,
        version = "DocPaw-OFDH 0.2.0",
        description = "生成、签署和检查 OFD-H 文档。",
        subcommands = {
            OfdhCommand.Prepare.class,
            OfdhCommand.Check.class,
            OfdhCommand.Sign.class,
            OfdhCommand.ValidateRequest.class,
            OfdhCommand.ValidateClinical.class,
            OfdhCommand.Schemas.class
        })
public final class OfdhCommand implements Runnable {
    @Spec private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    public static CommandLine commandLine() {
        CommandLine command = new CommandLine(new OfdhCommand());
        command.setCaseInsensitiveEnumValuesAllowed(true);
        chineseHelp(command);
        command.setParameterExceptionHandler(
                (failure, args) -> {
                    error(
                            failure.getCommandLine().getErr(),
                            "ARGUMENT_INVALID",
                            "命令参数缺失或格式不正确，请使用 --help 查看用法。");
                    return 64;
                });
        command.setExecutionExceptionHandler(
                (failure, line, result) -> {
                    if (failure instanceof OfdhException) {
                        OfdhException problem = (OfdhException) failure;
                        error(
                                line.getErr(),
                                problem.getCode(),
                                problem.getMessage(),
                                problem.getIssues(),
                                problem.isTruncated());
                        String code = problem.getCode();
                        if (code.startsWith("REQUEST_")
                                || code.startsWith("SCHEMA_")
                                || code.startsWith("OUTPUT_")
                                || code.startsWith("SIGNING_CONFIGURATION")
                                || code.equals("PATH_OUTSIDE_ROOT")) {
                            return 64;
                        }
                        if (code.equals("SIGNING_FAILED")
                                || code.equals("OPERATION_CANCELLED")
                                || code.equals("ATOMIC_PUBLISH_UNSUPPORTED")) {
                            return 4;
                        }
                        return 2;
                    }
                    error(
                            line.getErr(),
                            failure instanceof IOException ? "IO_FAILURE" : "PROCESSING_FAILURE",
                            failure instanceof IOException ? "文件读写失败，请检查路径、权限和存储空间。" : "文档处理失败。");
                    return 4;
                });
        return command;
    }

    private static void chineseHelp(CommandLine command) {
        command.getCommandSpec()
                .usageMessage()
                .synopsisHeading("用法：")
                .descriptionHeading("%n")
                .parameterListHeading("参数：%n")
                .optionListHeading("选项：%n")
                .commandListHeading("命令：%n");
        CommandLine.Model.OptionSpec help = command.getCommandSpec().findOption("--help");
        if (help != null) {
            command.getCommandSpec()
                    .remove(help)
                    .addOption(help.toBuilder().description("显示帮助信息。").build());
        }
        CommandLine.Model.OptionSpec version = command.getCommandSpec().findOption("--version");
        if (version != null) {
            command.getCommandSpec()
                    .remove(version)
                    .addOption(version.toBuilder().description("显示版本信息。").build());
        }
        command.getSubcommands().values().forEach(OfdhCommand::chineseHelp);
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    private static void error(PrintWriter writer, String code, String message) {
        error(writer, code, message, Collections.<ValidationIssue>emptyList(), false);
    }

    private static void error(
            PrintWriter writer,
            String code,
            String message,
            List<ValidationIssue> issues,
            boolean truncated) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code);
        value.put("message", message);
        value.put("issues", issues);
        value.put("truncated", truncated);
        try {
            writer.println(JsonIO.string(value));
        } catch (IOException ignored) {
            writer.println(
                    "{\"code\":\"SERIALIZATION_FAILURE\",\"message\":\"错误报告序列化失败。\",\"issues\":[],\"truncated\":false}");
        }
        writer.flush();
    }

    private abstract static class Operation {
        @Spec CommandSpec spec;

        void emit(Map<String, Object> result) throws IOException {
            spec.commandLine().getOut().println(JsonIO.string(result));
            spec.commandLine().getOut().flush();
            if (spec.commandLine().getOut().checkError()) {
                throw new IOException("处理结果无法写入标准输出。");
            }
        }

        static Map<String, Object> result(String operation) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("operation", operation);
            value.put("toolVersion", "0.2.0");
            return value;
        }

        static void checkOutput(Path output) throws IOException {
            Path target = canonicalOutput(output);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new OfdhException("OUTPUT_EXISTS", "输出文件已存在，请使用新的文件名。");
            }
        }

        static void writeReport(Path output, CheckReport report) throws IOException {
            writeBytes(output, JsonIO.bytes(report));
        }

        static void writeBytes(Path output, byte[] bytes) throws IOException {
            checkOutput(output);
            Path target = canonicalOutput(output);
            Path temporary = Files.createTempFile(target.getParent(), ".ofdh-report-", ".tmp");
            boolean committed = false;
            try {
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                FileSafety.publish(temporary, target);
                committed = true;
            } finally {
                if (!committed) Files.deleteIfExists(temporary);
            }
        }

        static Path canonicalOutput(Path path) throws IOException {
            Path absolute = path.toAbsolutePath().normalize();
            if (absolute.getParent() == null || absolute.getFileName() == null) {
                throw new OfdhException("OUTPUT_INVALID", "输出路径必须指定文件名。");
            }
            return FileSafety.unlinkedDirectory(absolute.getParent())
                    .resolve(absolute.getFileName());
        }

        static void distinct(Path first, Path second) throws IOException {
            if (canonicalOutput(first).equals(canonicalOutput(second))) {
                throw new OfdhException("OUTPUT_INVALID", "文档与报告必须使用不同的输出路径。");
            }
        }

        int deliver(Map<String, Object> result, Path report, CheckReport inspection, int status)
                throws IOException {
            try {
                writeReport(report, inspection);
                result.put("report", report.getFileName().toString());
                result.put("reportStatus", "WRITTEN");
            } catch (IOException failure) {
                // 文档已发布；报告写入失败时仍须告知调用方实际完成的阶段。
                result.put("report", null);
                result.put("reportStatus", "FAILED");
                emit(result);
                error(
                        spec.commandLine().getErr(),
                        "REPORT_WRITE_FAILED",
                        "文档已生成，但报告写入失败；请保留文档并运行 check 补生成报告。");
                return 4;
            }
            emit(result);
            return status;
        }
    }

    @Command(name = "prepare", mixinStandardHelpOptions = true, description = "生成待签署的 OFD-H 文件。")
    static final class Prepare extends Operation implements Callable<Integer> {
        @Option(names = "--request", required = true, description = "请求 JSON 文件。")
        Path request;

        @Option(names = "--input-root", required = true, description = "源文件及临床 JSON 所在的输入根目录。")
        Path inputRoot;

        @Option(names = "--out", required = true, description = "新文档的输出路径，禁止覆盖已有文件。")
        Path output;

        @Option(names = "--report", required = true, description = "检查报告的输出路径。")
        Path report;

        @Option(names = "--clinical-schema", description = "自定义本地 Draft 2020-12 Schema；使用内置规范时可省略。")
        Path schema;

        @Option(names = "--optimize-page-order", description = "按文档页序前置页面及依赖资源；不生成私有索引。")
        boolean optimizePageOrder;

        @Override
        public Integer call() throws IOException {
            distinct(output, report);
            checkOutput(output);
            checkOutput(report);
            DocumentRequest document = RequestReader.read(request);
            ResourceLimits limits = ResourceLimits.defaults();
            ClinicalSchemaRegistry registry =
                    new ClinicalSchemaRegistry(limits.getMaxClinicalBytes());
            ClinicalSchemas.registerDefaults(registry);
            if (schema != null) {
                if (document.getClinicalData() == null) {
                    throw new OfdhException("REQUEST_INVALID", "请求未声明临床数据，不能单独提供临床 Schema。");
                }
                registry.register(
                        document.getClinicalData().getSchemaId(),
                        document.getClinicalData().getSchemaVersion(),
                        schema);
            }
            PrepareService service =
                    new PrepareService(
                            Collections.singletonList(new PdfSourceAdapter()),
                            registry,
                            limits,
                            optimizePageOrder);
            PreparedDocument prepared = service.prepare(document, inputRoot, output);
            return complete(prepared, limits);
        }

        int complete(PreparedDocument prepared, ResourceLimits limits) throws IOException {
            Map<String, Object> result = result("prepare");
            result.put("stage", "PREPARED");
            result.put("artifact", prepared.getPath().getFileName().toString());
            result.put("fileId", prepared.getFileId());
            result.put("sha256", prepared.getSha256());
            result.put("sourceSha256", prepared.getSourceSha256());
            CheckReport inspection;
            try {
                inspection =
                        new CheckService(limits).check(prepared.getPath(), CheckProfile.PREPARED);
                if (!prepared.getSha256().equals(inspection.getArtifact().getSha256())) {
                    throw new IOException("复核文件与已发布文件的摘要不同。");
                }
            } catch (IOException | RuntimeException failure) {
                // 文件已发布；后续复核失败不能抹去已完成阶段，也不能交付其他版本的检查报告。
                result.put("assessment", null);
                result.put("report", null);
                result.put("reportStatus", "NOT_WRITTEN");
                emit(result);
                error(
                        spec.commandLine().getErr(),
                        "POST_PREPARE_CHECK_FAILED",
                        "文档曾完成生成，但后续复核失败或摘要已变化；请核对现有文件后运行 check 补报告。");
                return 4;
            }
            result.put("assessment", inspection.getAssessment());
            return deliver(
                    result,
                    report,
                    inspection,
                    "ISSUES_FOUND".equals(inspection.getAssessment()) ? 2 : 0);
        }
    }

    @Command(name = "check", mixinStandardHelpOptions = true, description = "检查 OFD-H 文件，不修改原文件。")
    static final class Check extends Operation implements Callable<Integer> {
        @Option(names = "--input", required = true, description = "待检查的 OFD 文件。")
        Path input;

        @Option(names = "--profile", defaultValue = "FINAL", description = "检查阶段：FINAL 或 PREPARED。")
        CheckProfile profile;

        @Option(names = "--report", required = true, description = "检查报告的输出路径。")
        Path report;

        @Option(names = "--trust", description = "本次检查明确受信任的 PEM 或 DER 证书文件。")
        Path trust;

        @Override
        public Integer call() throws Exception {
            distinct(input, report);
            checkOutput(report);
            Set<X509Certificate> roots = new LinkedHashSet<>();
            if (trust != null) {
                if (Files.size(trust) > (1L << 20)) {
                    throw new OfdhException("LIMIT_EXCEEDED", "信任证书文件不能超过 1 MiB。");
                }
                try (InputStream in = Files.newInputStream(trust)) {
                    for (java.security.cert.Certificate certificate :
                            CertificateFactory.getInstance(
                                            "X.509",
                                            new org.bouncycastle.jce.provider
                                                    .BouncyCastleProvider())
                                    .generateCertificates(in)) {
                        roots.add((X509Certificate) certificate);
                    }
                }
                if (roots.isEmpty()) {
                    throw new OfdhException("REQUEST_INVALID", "信任文件未包含证书。");
                }
            }
            CheckReport inspection =
                    new CheckService(ResourceLimits.defaults()).check(input, profile, roots);
            writeReport(report, inspection);
            Map<String, Object> result = result("check");
            result.put("assessment", inspection.getAssessment());
            result.put("report", report.getFileName().toString());
            result.put("sha256", inspection.getArtifact().getSha256());
            emit(result);
            return "ISSUES_FOUND".equals(inspection.getAssessment())
                    ? 2
                    : "INCOMPLETE".equals(inspection.getAssessment()) ? 3 : 0;
        }
    }

    @Command(
            name = "sign",
            mixinStandardHelpOptions = true,
            description = "使用明确提供的 SM2 PKCS#12 身份签署文档。")
    static final class Sign extends Operation implements Callable<Integer> {
        @Option(names = "--input", required = true, description = "待签署的 OFD-H 文件。")
        Path input;

        @Option(names = "--out", required = true, description = "签署后文档的输出路径。")
        Path output;

        @Option(names = "--report", required = true, description = "签后检查报告的输出路径。")
        Path report;

        @Option(names = "--keystore", required = true, description = "SM2 PKCS#12 身份文件。")
        Path keystore;

        @Option(names = "--alias", required = true, description = "身份文件中的密钥别名。")
        String alias;

        @Option(names = "--password-env", required = true, description = "保存身份文件密码的环境变量名称，不传入密码本身。")
        String passwordEnvironment;

        @Option(names = "--optimize-page-order", description = "按文档页序前置页面及依赖资源；不生成私有索引。")
        boolean optimizePageOrder;

        @Override
        public Integer call() throws IOException {
            distinct(output, report);
            checkOutput(output);
            checkOutput(report);
            String secret = System.getenv(passwordEnvironment);
            if (secret == null) {
                throw new OfdhException("SIGNING_CONFIGURATION", "未设置保存身份文件密码的环境变量。");
            }
            char[] password = secret.toCharArray();
            Pkcs12SignProvider provider;
            try {
                provider = Pkcs12SignProvider.load(keystore, password, alias);
            } finally {
                Arrays.fill(password, '\0');
            }
            SignedDocument signed =
                    new SigningService(ResourceLimits.defaults(), optimizePageOrder)
                            .sign(input, output, provider);
            Map<String, Object> result = result("sign");
            result.put("stage", "SIGNED");
            result.put("artifact", output.getFileName().toString());
            result.put("sha256", signed.getSha256());
            result.put("assessment", signed.getReport().getAssessment());
            return deliver(result, report, signed.getReport(), 0);
        }
    }

    @Command(
            name = "validate-request",
            mixinStandardHelpOptions = true,
            description = "校验请求字段，不读取源文档。")
    static final class ValidateRequest extends Operation implements Callable<Integer> {
        @Option(names = "--request", required = true, description = "请求 JSON 文件。")
        Path request;

        @Override
        public Integer call() throws IOException {
            RequestReader.read(request);
            Map<String, Object> result = result("validate-request");
            result.put("valid", true);
            result.put("issues", Collections.emptyList());
            result.put("truncated", false);
            emit(result);
            return 0;
        }
    }

    @Command(
            name = "validate-clinical",
            mixinStandardHelpOptions = true,
            description = "校验临床 JSON 的字段及来源一致性，不转换或签署文档。")
    static final class ValidateClinical extends Operation implements Callable<Integer> {
        @Option(names = "--request", required = true, description = "声明临床数据的请求 JSON 文件。")
        Path request;

        @Option(names = "--input-root", required = true, description = "临床 JSON 所在的输入根目录。")
        Path inputRoot;

        @Option(names = "--clinical-schema", description = "自定义本地 Schema；使用内置规范时可省略。")
        Path schema;

        @Override
        public Integer call() throws IOException {
            DocumentRequest document = RequestReader.read(request);
            if (document.getClinicalData() == null) {
                ValidationIssue issue =
                        new ValidationIssue(
                                "/clinicalData",
                                "required",
                                "请声明需要校验的临床数据。",
                                "包含路径、规范标识及版本的对象",
                                "missing");
                throw new OfdhException(
                        "REQUEST_INVALID",
                        "请求未声明临床数据。",
                        null,
                        new ValidationReport(Collections.singletonList(issue), false));
            }
            ResourceLimits limits = ResourceLimits.defaults();
            ClinicalSchemaRegistry registry =
                    new ClinicalSchemaRegistry(limits.getMaxClinicalBytes());
            ClinicalSchemas.registerDefaults(registry);
            if (schema != null) {
                registry.register(
                        document.getClinicalData().getSchemaId(),
                        document.getClinicalData().getSchemaVersion(),
                        schema);
            }
            Path source = FileSafety.resolveInput(inputRoot, document.getClinicalData().getPath());
            Path work = FileSafety.privateDirectory("ofdh-validate-");
            try {
                Path snapshot = work.resolve("clinical.json");
                FileSafety.snapshot(source, snapshot, limits.getMaxClinicalBytes());
                registry.validate(document, snapshot);
            } finally {
                FileSafety.deleteTree(work);
            }
            Map<String, Object> result = result("validate-clinical");
            result.put("valid", true);
            result.put("issues", Collections.emptyList());
            result.put("truncated", false);
            emit(result);
            return 0;
        }
    }

    @Command(
            name = "schema",
            mixinStandardHelpOptions = true,
            description = "查询或导出内置临床 Schema。",
            subcommands = {SchemaList.class, SchemaExport.class})
    static final class Schemas implements Runnable {
        @Spec CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "列出内置规范及其版本。")
    static final class SchemaList extends Operation implements Callable<Integer> {
        @Override
        public Integer call() throws IOException {
            List<Map<String, String>> schemas = new java.util.ArrayList<>();
            for (ClinicalSchemas schema : ClinicalSchemas.values()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("id", schema.getId());
                item.put("version", schema.getVersion());
                item.put("title", schema.getTitle());
                schemas.add(item);
            }
            Map<String, Object> result = result("schema-list");
            result.put("schemas", schemas);
            emit(result);
            return 0;
        }
    }

    @Command(name = "export", mixinStandardHelpOptions = true, description = "导出自包含的临床 Schema 文件。")
    static final class SchemaExport extends Operation implements Callable<Integer> {
        @Option(names = "--id", required = true, description = "内置规范标识，使用 schema list 查询。")
        String id;

        @Option(names = "--schema-version", required = true, description = "规范版本。")
        String version;

        @Option(names = "--out", required = true, description = "新 Schema 文件的输出路径。")
        Path output;

        @Override
        public Integer call() throws IOException {
            ClinicalSchemas schema =
                    ClinicalSchemas.find(id, version)
                            .orElseThrow(
                                    () -> new OfdhException("SCHEMA_UNKNOWN", "未找到指定标识和版本的内置规范。"));
            try (InputStream input = schema.openSchema()) {
                writeBytes(output, JsonIO.bytes(JsonIO.read(input, 1L << 20)));
            }
            Map<String, Object> result = result("schema-export");
            result.put("id", schema.getId());
            result.put("version", schema.getVersion());
            result.put("artifact", output.getFileName().toString());
            emit(result);
            return 0;
        }
    }
}
