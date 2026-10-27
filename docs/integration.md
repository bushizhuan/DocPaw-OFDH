# 接入说明

本文对应 0.2.0 的实际 Java API 和 CLI。新增接入优先使用 [简化 SDK 与字段校验](sdk.md)；本文保留底层服务 API 和完整处理边界。接入方提供同一业务版本的版式文件和元数据，工具生成新的待签文件，签署后检查最终字节，再由原系统归档。

当前支持范围和外部验收状态见 [实现与验收状态](implementation-status.md)。医院 CA 联调、独立阅读器验证及完整标准符合性尚未完成，不能用一次命令成功替代这些验收。

首次接入可先构建 [独立 Java 工程](../examples/java-sdk/README.md)，再对照本文调整业务调用；常见失败及重试边界见 [错误码与诊断](errors.md)。

## 1. 运行与依赖

构建使用 JDK 21、Maven 3.9。运行构建命令：

```sh
mvn -B -ntp clean install
```

这会执行测试、构建模块并安装到本机 Maven 仓库。SDK 当前通过源码安装或组织内部 Maven 仓库分发。部署到内部制品库时使用本组织的发布流程。

CLI 压缩包位于 `ofdh-cli/target/ofdh-0.2.0.zip`。解压后使用 Windows 的 `bin/ofdh.cmd` 或 Linux 的 `bin/ofdh`；脚本从 `JAVA_HOME` 或 `PATH` 查找 Java，发行包不包含 JDK。建议使用维护中的 JDK 21。默认最大堆为 1 GiB，可以通过 `OFDH_MAX_HEAP` 调整。

SDK 以 Java 8 字节码为兼容目标，接入前仍应验证宿主实际 JDK、密码提供者及依赖冲突。旧 Java Web 工程已有 Jackson、Bouncy Castle 等依赖且难以隔离时，优先调用独立 CLI 进程。

Java 项目按需求引用模块：

```xml
<dependency>
  <groupId>org.openofdh</groupId>
  <artifactId>ofdh-core</artifactId>
  <version>0.2.0</version>
</dependency>
```

需要 PDF 输入时，增加 `org.openofdh:ofdh-pdf:0.2.0`；该模块已依赖 core。原生 OFD 接入不需要 PDFBox。

输入根目录、输出目录应事先建立。输出使用新文件名，默认拒绝覆盖。当前发布机制要求本地文件系统支持硬链接；网络共享、FAT/exFAT 等未列为支持环境。需要上传共享存储时，在本地生成并核对摘要后，由宿主上传。

## 2. 先确定来源版本

一次调用对应一份文书的一个来源版本。患者标识、就诊标识和文件标识具有不同用途，不要互相替代。

接入方应先锁定业务版本，再导出 PDF/OFD 和可选临床 JSON。工具会分别建立私有文件快照，校验来源摘要并检测读取期间的文件变化；这只能固定输入字节，不能替代数据库事务或证明两次业务导出来自同一个状态。

建议在业务侧保存 `sourceSystem + sourceRecordId + sourceVersion`、源文件摘要、生成的 `fileId`、最终文件摘要及处理阶段。重试和幂等由宿主负责。相同请求再次生成可能产生不同文件标识、时间和字节摘要。

请求遵循 [document.schema.json](../contracts/document.schema.json)：

| 字段 | 接入方提供的值 |
|---|---|
| `contractVersion` | 固定 `0.1.0` |
| `documentId` | 业务文书标识 |
| `sourceSystem` | 来源系统的稳定标识 |
| `sourceRecordId`、`sourceVersion` | 来源记录及本次导出对应的业务版本 |
| `documentType.code / scheme` | 文书类别及其代码体系 |
| `patient.id / authority` | 患者标识及分配机构或命名空间 |
| `responsibleParty.id / name / authority` | 文书责任者；不自动视为签署人或签署授权 |
| `createdAt` | 来源记录创建时间，必须带时区 |
| `keywords` | 非空关键词数组 |
| `confidentiality.code / scheme` | 适用的密级值及代码体系 |
| `source.format` | `OFD` 或 `PDF` |
| `source.path` | 相对 `inputRoot` 的文件路径 |
| `source.sha256` | 可选预期摘要；提供后必须与输入快照一致 |
| `encounter.id / authority` | 可选就诊关联 |
| `clinicalData.path / schemaId / schemaVersion` | 可选临床 JSON；出现时三项完整 |

`source.path` 和 `clinicalData.path` 使用 `/` 分隔。禁止绝对路径、URL、盘符、反斜杠、空段、`.`、`..`；运行时也拒绝链接或路径重定向。`inputRoot` 必须显式传入。

生成器将 OFD 原生 `DocID` 用作新文件标识，将业务来源信息写入公开的 `openofdh.*` 元数据映射。字段映射见 [设计基线](design.md)。这些扩展键属于项目协议，并非全国统一的医疗 JSON 字段名。

## 3. 选择输入路线

### 原生 OFD

输入应为未签署、未加密的单文档 OFD，入口能解析到 `Doc_0/Document.xml`，页面、资源及附件引用完整。当前实现保留原有页面和资源，补充文档元数据并登记可选临床附件。

已签 OFD 可以执行 `check`，不能执行 `prepare` 改写。多文档、无法识别的结构或无法安全处理的引用会拒绝处理；不会自动剥除签名、丢弃内容或合并文档。

### PDF

PDF 路线适用于受支持的静态导出文件。文字必须具有嵌入的 TrueType 字体和可靠的 Unicode 映射，转换保留可检索文字及其位置。受支持的 URI 和内部页面链接会转换；不会执行这些链接。

以下输入会拒绝或超出当前范围：已签署或加密文件、扫描件/OCR、未嵌入字体、CFF/Type3 字体、竖排文字、ActualText 替代文本、活动脚本、未扁平化表单、附件业务，以及不支持的注释、透明混合、渐变和图案。普通标记内容仍支持；ActualText 会改变可复制文本，当前不通过忽略它来完成转换。以实际错误码及支持边界为准，不应通过整页截图或静默删除对象绕过限制。

页树的类型、父子引用、各层声明页数须与实际页面一致；循环、重复节点和超过 64 层的页树会拒绝。实际页数、页树节点及输出文件大小也受 `ResourceLimits` 约束，避免坏计数导致静默少页，或直接使用适配器时绕过发布文件预算。

源系统应优先导出可控的静态打印文件。遇到不支持特性时，调整其导出配置，或注册另一个经过验证的 `PageSourceAdapter`。一次转换成功不表示全部页面已经通过独立视觉验收。

## 4. 可选临床 JSON 与本地 Schema

不提供 `clinicalData` 时，仍须提供完整的文档元数据。统一 SDK 与 CLI 自动注册三类 [内置 Schema](clinical-schemas.md)；自定义规范和直接使用底层注册表的调用仍需显式注册。支持 Draft 2020-12，引用限于本文件内的 `#` 片段；不会根据 Schema ID 或 `$ref` 下载网络内容。

业务 Schema ID、版本号和字段结构由接入方确定。工具执行真实 Schema 校验、严格 UTF-8 解码、重复键和资源预算检查，再按 OFD 附件机制保存原始 JSON 字节及其摘要。原始 JSON 不会被重新排版后替换。

Java 注册方式：

```java
import org.openofdh.core.validation.ClinicalSchemaRegistry;

ClinicalSchemaRegistry schemas = new ClinicalSchemaRegistry();
schemas.register(schemaId, schemaVersion, localSchemaFile);
```

以上变量取自接入方维护的规范登记和本地文件。每个注册表中，同一 ID 与版本只能注册一次；应按版本升级 Schema，避免运行期间隐式覆盖。

第三方 Schema 即使使用 `head.version`、`patient.id` 等同名字段，工具也不会推断其业务含义。准备阶段验证注册的 Schema；文件检查阶段验证编码、语法、附件引用和摘要，临床语义与来源一致性保持 `NOT_CHECKED`。

需要使用本项目的明确字段映射时，在临床 JSON 内设置：

```text
/head/profile = urn:openofdh:clinical-envelope:0.1.0
```

该值是选择性的项目协议标记，既不是国标字段，也不是文书内容。声明后，要求并核对以下字段：

| 临床 JSON 路径 | 对应含义 |
|---|---|
| `/head/schemaId`、`/head/version` | 请求中的临床 Schema ID、版本 |
| `/head/documentVersion` | `sourceVersion` |
| `/head/createdAt` | `createdAt`；按时间点比较，允许等价时区表示 |
| `/head/modifiedAt` | 修改时间，不得早于创建时间 |
| `/identifier/documentId` | 业务文书标识 |
| `/identifier/documentType/code`、`scheme` | 文书类别及代码体系 |
| `/identifier/confidentiality/code`、`scheme` | 密级及代码体系 |
| `/patient/id`、`authority` | 患者标识及机构 |
| `/meta/creator/id`、`name`、`authority` | 责任者 |
| `/meta/keywords` | 非空关键词数组；与请求按集合比较 |
| `/meta/properties/sourceSystem`、`sourceRecordId` | 来源系统及来源记录 |

请求带 `encounter` 且临床 JSON 同时提供对应字段时，还会核对就诊关联。临床正文结构继续由注册的业务 Schema 约束。明确声明本项目 envelope 却缺少必要字段会失败；未知的本项目 envelope 版本会被准备流程拒绝。

这些比对能发现确定的标识、版本和元数据差异，不能证明诊断内容与页面的全部医学含义一致。

## 5. Java 准备与检查

以下代码直接使用公开 API。`requestFile`、`inputRoot`、`preparedFile` 和可选 `localSchemaFile` 均由现有文书导出流程提供。

```java
import java.util.Collections;
import org.openofdh.core.api.DocumentRequest;
import org.openofdh.core.api.PageSourceAdapter;
import org.openofdh.core.api.ResourceLimits;
import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.check.CheckService;
import org.openofdh.core.prepare.PrepareService;
import org.openofdh.core.prepare.PreparedDocument;
import org.openofdh.core.validation.ClinicalSchemaRegistry;
import org.openofdh.core.validation.RequestReader;
import org.openofdh.pdf.PdfSourceAdapter;

DocumentRequest request = RequestReader.read(requestFile);
ResourceLimits limits = ResourceLimits.defaults();
ClinicalSchemaRegistry schemas =
        new ClinicalSchemaRegistry(limits.getMaxClinicalBytes());

if (request.getClinicalData() != null) {
    schemas.register(request.getClinicalData().getSchemaId(),
            request.getClinicalData().getSchemaVersion(), localSchemaFile);
}

PrepareService service = new PrepareService(
        Collections.<PageSourceAdapter>singletonList(new PdfSourceAdapter()),
        schemas, limits);
PreparedDocument prepared = service.prepare(request, inputRoot, preparedFile);

CheckReport report = new CheckService(limits)
        .check(prepared.getPath(), CheckProfile.PREPARED);
```

仅接收原生 OFD 时，适配器参数可传 `Collections.<PageSourceAdapter>emptyList()`，同时移除 PDF 模块依赖。OFD 原生路径不需要另外注册适配器。

`PreparedDocument` 返回输出路径、`fileId`、生成时间、来源 SHA-256 和准备制品 SHA-256。准备流程发布前已经执行检查；上面的显式 `check` 用于取得可持久化报告。输出文件创建成功后，业务侧保存身份、摘要及报告；任一步失败都应记录实际完成阶段。

报告是普通 Java 对象，可以使用宿主的 JSON 序列化方式。例如：

```java
byte[] reportJson = new com.fasterxml.jackson.databind.ObjectMapper()
        .writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
```

不要在序列化报告时省略契约要求的 `null` 字段。报告字段定义见 [check-report.schema.json](../contracts/check-report.schema.json)。持久化策略由宿主决定；建议先写完整临时文件，再发布为新报告。

默认资源预算是输入 100 MiB、展开 1 GiB、10,000 个成员、500 页、临床 JSON 10 MiB。SDK 可通过 `ResourceLimits` 构造器显式设置预算。它们属于解析防护参数，不是国家标准文件上限。单个任务应有进程级时间和内存限制；Java 线程中断无法保证中止所有第三方解码器。

## 6. 签署接入

### CA 只提供底层签名能力

使用 `Sm2SignProvider.forMessage(certificateChain, signer)` 或 `forDigest(...)`。中间件负责生成 OFD 签名结构及证书链，`Sm2Signer` 只需调用院方 CA 并返回 `Sm2Signature.der(bytes)` 或 `Sm2Signature.raw(bytes)`。

两种模式必须按 CA 的实际运算语义选择：`forMessage` 将 `SM3(Signature.xml 原字节)` 交给 SM3withSM2 原文模式；`forDigest` 交给 CA 的是已经包含 ZA 的最终 SM2 摘要，不能再次计算摘要。固定 SM2 用户标识由请求提供，不使用文书中的患者或医生标识替代。

完整调用、编码及失败处理见 [CA 与底层签名接入](ca-integration.md)。此扩展通过 Java SDK 使用；CLI 当前仍只支持本地 PKCS#12。真实厂商接口、授权流程及独立验签须由接入方完成联调。

### 已有院方签署服务

由接入方实现 `org.openofdh.core.sign.SignProvider`：

```java
void sign(Path preparedFile, Path signedFile, ResourceLimits limits)
        throws IOException;
```

实现方读取给定的准备文件，通过已有签署服务形成实际签署结果，并将完整 OFD 写入指定的新路径。返回前必须完成文件写入；异步签署状态由宿主管理，不应在后台仍写文件时提前返回。

使用宿主已经实现并配置好的提供者：

```java
import org.openofdh.core.sign.SignProvider;
import org.openofdh.core.sign.SignedDocument;
import org.openofdh.core.sign.SigningService;

SignedDocument signed = new SigningService(limits)
        .sign(prepared.getPath(), signedFile, provider);
```

`provider` 是接入方真实的 `SignProvider` 实例。不要将密钥、口令或签署授权写入文档请求 JSON。

签署服务必须保持原文书内容与元数据，仅增加当前签署布局允许的对象。当前编排器会拒绝改写源输入、改变原始成员、替换文书、移除内容，或在 `Doc_0/Signs/` 之外增加成员的结果。它重新固定结果快照，执行最终检查，要求实际密码验签通过后才发布输出。

当前内置验签支持 GB/T 35275 的 SM2/SM3 数字签名。未知签章格式或算法保持 `NOT_CHECKED`，不能被此编排器当作已验证结果发布。适配某家 CA 的调用协议，并不自动表示该 CA 的返回格式已在本工具通过互操作验证。

### 本地 SM2 PKCS#12

接入方具备可本地使用的授权 SM2 身份时，可使用内置提供者：

```java
import java.util.Arrays;
import org.openofdh.core.sign.Pkcs12SignProvider;

Pkcs12SignProvider provider;
try {
    provider = Pkcs12SignProvider.load(keystoreFile, suppliedPassword, keyAlias);
} finally {
    Arrays.fill(suppliedPassword, '\0');
}
SignedDocument signed = new SigningService(limits)
        .sign(prepared.getPath(), signedFile, provider);
```

`suppliedPassword` 是接入方提供的 `char[]`。私钥保持在提供者进程内，不写入输出文档。SM2 使用 `sm2p256v1`；加载时检查证书有效期、签名用途及私钥匹配。PKCS#12 的完整证书链会随签名保存，便于离线验证。文件预算为 1 MiB、链长最多 16 张，属于当前实现限制。

CA/HSM 场景可通过底层 `Sm2Signer` 或整文件 `SignProvider` 接入，不应为适配本地 PKCS#12 而要求导出不可导出的私钥。

### 显式信任验证

```java
import java.security.cert.X509Certificate;
import java.util.Set;

CheckReport finalReport = new CheckService(limits)
        .check(signed.getPath(), CheckProfile.FINAL, trustedCertificates);
```

`trustedCertificates` 是接入方按本机构信任政策配置的 `Set<X509Certificate>`。随文件携带的证书不会被自动提升为信任锚。验证使用本地证书材料，不查询在线证书、OCSP、CRL 或时间戳服务。

`cryptographicVerification=PASS` 说明当前支持范围内的签名值及受保护文件摘要通过验证；`certificateTrust=TRUSTED` 说明按本次显式信任锚及检查时间建立了信任。它们不能替代业务签署授权、吊销证据或可信签署时间。

## 7. CLI 命令

以下 PowerShell 中的变量均指向接入方实际的文件、目录或已配置的身份。`$ofdh` 指向发行包的 `bin/ofdh.cmd`。Linux 使用相同命令参数调用 `bin/ofdh`。

```powershell
& $ofdh validate-request --request $requestFile

& $ofdh prepare --request $requestFile --input-root $inputRoot `
    --out $preparedFile --report $preparedReport

& $ofdh prepare --request $requestFile --input-root $inputRoot `
    --clinical-schema $localSchemaFile --out $preparedFile --report $preparedReport

& $ofdh check --input $preparedFile --profile PREPARED --report $preSignReport

& $ofdh sign --input $preparedFile --out $signedFile --report $signReport `
    --keystore $keystoreFile --alias $keyAlias --password-env OFDH_KEYSTORE_PASSWORD

& $ofdh check --input $signedFile --profile FINAL --report $finalReport `
    --trust $trustCertificateFile
```

两条 `prepare` 命令分别用于不带和带临床 JSON 的调用，选择对应的一条；不能按顺序写入同一个输出路径。

`validate-request` 只校验请求 JSON，不读取版式文件。`check` 默认使用 `FINAL`，不需要原始请求。`--trust` 接收本地 PEM/DER 证书文件，可包含显式信任的多个证书；不要将未知中间证书一并作为信任锚导入。

签署口令由宿主为子进程设置名为 `OFDH_KEYSTORE_PASSWORD` 的环境变量；命令行只传变量名。环境变量仍属于凭据，应限制进程继承范围。CLI 不提供命令行明文口令参数。

`prepare` 和 `sign` 的 stdout 是一条 JSON 操作结果，含实际阶段、输出文件名、摘要和报告状态；`check` 返回检查结论、报告文件名和被检摘要。错误以稳定错误码写入 stderr。不要按自然语言提示判断业务结果，不要把 stdout/stderr 直接显示为病历正文或写入患者日志。

## 8. 处理阶段、报告和退出码

处理阶段与检查结论相互独立：

| 情况 | 含义 |
|---|---|
| `stage=PREPARED` | 已生成待签署文件 |
| `stage=SIGNED` | 已生成并检查签名的文件 |
| `assessment=ISSUES_FOUND` | 本次执行规则存在 `FAIL` |
| `assessment=INCOMPLETE` | 没有已知失败，但仍有未检查范围或未确立的信任证据 |
| `assessment=NO_ISSUES_IN_EXECUTED_CHECKS` | 明确范围内执行的检查未发现问题；仍不是认证结论 |

本版本仍有线性化、临床语义和外部验收等未覆盖项，因此正常生成或签署后，报告通常仍是 `INCOMPLETE`。未签准备制品在 `PREPARED` 下明确等待签署；同一文件以 `FINAL` 检查时会因缺少有效使用所需签名而出现失败。

| 退出码 | 调用端处理 |
|---|---|
| `0` | 操作完成。`prepare`/`sign` 仍需查看 `stage`、报告及后续业务门槛 |
| `2` | 输入内容、当前处理能力或已执行规则失败；读取错误码或具体规则。未注册临床 Schema 为 `CLINICAL_SCHEMA_UNKNOWN`，也返回 `2` |
| `3` | `check` 结果为 `INCOMPLETE`，不能当作完整通过 |
| `4` | I/O、执行、取消、原子发布或报告写入失败；核查实际阶段 |
| `64` | 参数、请求契约、路径/输出配置、`SCHEMA_*` 定义或注册配置错误，以及签署配置问题 |

调用端先识别退出码，再解析成功操作结果、错误码和报告。错误 JSON 在原有 `code`、`message` 之外新增 `issues`、`truncated`；字段错误包含精确 JSON Pointer、约束类别、中文说明、期望及实际类型，最多返回 100 条。签名密码运算、证书信任、吊销和时间戳分别判断，不能折叠成一个“签名有效”布尔值。

文件报告绑定其 `artifact.sha256`。签后修改、重新打包或换文件后必须重新检查；原报告不再代表新字节。

### 文档已生成，但报告写入失败

CLI 在操作前检查输出路径，仍可能遇到磁盘故障、权限变化或并发创建报告。此时 `prepare`/`sign` 可能已成功发布文档，而报告写入失败：stdout 会保留 `stage`、文件名和摘要，标记 `reportStatus=FAILED`；stderr 为 `REPORT_WRITE_FAILED`，退出码为 `4`。

此时应保留已生成文件、核对摘要，并换一个新的报告路径重跑 `check`。待签文件用 `PREPARED`，签后文件用 `FINAL`。不要仅因非零退出码删除文件、重签已有文件，或覆盖历史输出。

如果进程被强制终止或未收到完整操作 JSON，先核查预定输出是否存在，再通过 `check` 和业务台账恢复状态。文件名存在不等于业务归档已经完成。

## 9. 旧 WinForms/.NET 接入

旧 .NET 项目直接启动 `java.exe`，不经 `cmd.exe`、PowerShell 或字符串拼接的 shell 命令。使用独立 Java 进程可以隔离旧客户端运行时和 Java 依赖；不会要求 WinForms 升级到 .NET 新版本。

.NET 4 没有 `ProcessStartInfo.ArgumentList`，需要按 Windows 参数规则转义。下面的函数只处理参数传递，不经过 shell：

```csharp
static string QuoteWindowsArgument(string value)
{
    if (value == null) throw new ArgumentNullException("value");
    var quoted = new StringBuilder("\"");
    int slashes = 0;
    foreach (char c in value)
    {
        if (c == '\\') { slashes++; continue; }
        quoted.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
        quoted.Append(c);
        slashes = 0;
    }
    quoted.Append('\\', slashes * 2);
    quoted.Append('"');
    return quoted.ToString();
}
```

构造进程时，将实际文件路径作为单独参数处理：

```csharp
var arguments = new[] {
    "-Xmx1024m", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true",
    "-cp", Path.Combine(distributionDirectory, "lib", "*"),
    "org.openofdh.cli.OfdhCommand", "check",
    "--input", inputFile, "--profile", "FINAL", "--report", reportFile
};
var start = new ProcessStartInfo {
    FileName = javaExecutable,
    Arguments = String.Join(" ", arguments.Select(QuoteWindowsArgument)),
    WorkingDirectory = taskDirectory,
    UseShellExecute = false,
    CreateNoWindow = true,
    RedirectStandardOutput = true,
    RedirectStandardError = true,
    StandardOutputEncoding = Encoding.UTF8,
    StandardErrorEncoding = Encoding.UTF8
};
```

这些变量来自应用的安装配置和当前归档任务。`taskDirectory` 应为本次任务的独立工作目录，输出采用不含患者姓名等信息的业务文件名。需要 `--trust` 或签署参数时，同样逐项加入参数数组；为签署子进程设置环境变量可使用 `start.EnvironmentVariables`，不要追加明文口令参数。

必须同时消费 stdout 和 stderr。不能先等待进程退出，再依次读取管道，否则任一管道写满都可能使双方等待。读取函数也应限制保留的输出；超限后继续排空管道，再返回失败，避免因提前停止读取卡住子进程：

```csharp
static string ReadBounded(TextReader reader, int maxCharacters)
{
    var text = new StringBuilder();
    var buffer = new char[4096];
    bool exceeded = false;
    int count;
    while ((count = reader.Read(buffer, 0, buffer.Length)) > 0)
    {
        int retained = Math.Min(count, maxCharacters - text.Length);
        text.Append(buffer, 0, retained);
        if (retained != count) exceeded = true;
    }
    if (exceeded) throw new InvalidDataException("OFD-H output exceeded the capture limit.");
    return text.ToString();
}
```

旧框架可以用两个专用读取任务，并由后台工作线程同步等待进程：

```csharp
using (var process = new Process { StartInfo = start })
{
    process.Start();
    Task<string> stdout = Task.Factory.StartNew(
        () => ReadBounded(process.StandardOutput, 1024 * 1024),
        CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
    Task<string> stderr = Task.Factory.StartNew(
        () => ReadBounded(process.StandardError, 1024 * 1024),
        CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);

    // Observe reader failures even when timeout recovery exits this scope.
    stdout.ContinueWith(t => { var error = t.Exception; },
        TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously);
    stderr.ContinueWith(t => { var error = t.Exception; },
        TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously);

    if (!process.WaitForExit(timeoutMilliseconds))
    {
        try { process.Kill(); }
        catch (InvalidOperationException) { }
        process.WaitForExit(5000);
        throw new TimeoutException("OFD-H process exceeded its time budget.");
    }
    if (!Task.WaitAll(new Task[] { stdout, stderr }, 5000))
        throw new IOException("OFD-H output streams did not close.");

    int exitCode = process.ExitCode;
    string operationJson = stdout.Result;
    string diagnosticText = stderr.Result;
    // Parse the operation result and read the report according to exitCode.
}
```

此段使用 `System.Diagnostics`、`System.IO`、`System.Linq`、`System.Text`、`System.Threading` 和 `System.Threading.Tasks`。放在后台任务或 `BackgroundWorker` 中执行，避免阻塞 UI；宿主还应限制并发进程数和保留日志大小。超时后的文件状态按上一节恢复，不把超时当作“没有生成任何文件”的证明。若宿主将来启动会派生子进程的适配工具，应使用 Windows Job Object 管理整个进程组，不能假定旧框架的 `Kill()` 会结束全部后代。

## 10. 业务侧验收和回滚

上线前用本机构授权的来源文件和签署环境验证：页面及文字保真、必要元数据映射、临床数据一致性、CA 返回格式、信任链、独立阅读器呈现，以及归档系统对最终文件和报告的接收。真实材料由接入方保管，不上传到开源仓库。

失败处理以稳定错误码为入口。`SOURCE_HASH_MISMATCH` 或 `SOURCE_VERSION_MISMATCH` 应重新取得一致来源；`SIGNED_INPUT_REQUIRES_DERIVATION` 应进入已签文件检查或另行授权的派生流程；`SOURCE_PROFILE_UNSUPPORTED`、`PDF_TEXT_MAPPING_UNAVAILABLE`、字体及资源错误应修复导出条件；`OUTPUT_EXISTS` 应核查既有任务状态，而不是直接覆盖。

建议先在既有归档链路旁增加独立调用开关。回滚时关闭新增适配入口，保留原导出流程和已形成的签署文件、摘要、报告及审计记录；不要改写或删除历史已签文件。SDK/CLI 不直接修改 HIS 数据库，也不替接入方执行归档状态回写。
