# Java SDK 接入工程

这是一个独立 Maven 消费者工程，读取调用者提供的请求，检查字段，生成待签署 OFD-H，再输出 `PREPARED` 检查报告。运行时只处理指定文件，不附带患者数据、预置来源文档或签署身份。

工程不继承仓库父 POM，也不属于仓库 reactor。它通过 Maven 坐标依赖 `org.openofdh:ofdh-pdf:0.2.0`，使用 Java 8 字节码；同时支持原生 OFD 和受支持的 PDF。PDF 转换范围见 [接入说明](../../docs/integration.md)。

本工程使用 Apache-2.0，许可原文随源码位于 [META-INF/LICENSE](src/main/resources/META-INF/LICENSE)，也会进入主 JAR。SDK 的补充依赖许可位于 SDK JAR 的 `META-INF/licenses/`；复制或分发运行程序时保留依赖 JAR 中的许可与声明。

## 构建

仓库 SDK 源码使用 JDK 21 和 Maven 3.9 构建。独立工程以 Java 8 为编译目标；CI 使用 JDK 21 编译，并在 Java 8、17、21 上运行。首次使用源码发行包时，先在仓库根目录安装 SDK：

```shell
mvn -B -ntp install
```

然后进入本目录，单独解析已安装的 SDK 并构建：

```shell
cd examples/java-sdk
mvn -B -ntp clean verify
```

接入工程不使用根目录的 `target/classes`、相对 `systemPath` 或未发布的测试工具。`verify` 在打包后执行集成测试：把 JAR 和依赖复制到包含中文与空格的临时目录，从另一工作目录以 `java -jar` 启动。测试临时创建实际 PDF/OFD、请求和临床 JSON，验证转换、机读附件原字节保留、字段错误、非法 JSON、输出保护及退出码；这些材料不进入运行包。

构建产物如下：

```text
target/
├── java-sdk-consumer-0.2.0.jar
└── dependency/
    └── 各运行依赖 JAR
```

`package` 生成上述文件；`verify` 还会验证打包后的完整调用流程，报告位于 `target/failsafe-reports/`。`test` 不执行这些打包后测试。复制运行程序时，应同时保留主 JAR 和同级 `dependency/`，其中的依赖通过 JAR 清单加载，不需要手写 classpath，也不需要运行机器安装 Maven。

整个 `java-sdk` 源码目录也可以单独复制出去构建；前提是本机或组织 Maven 仓库中已有对应 SDK 版本。首次从源码接入时需要先执行上面的安装步骤，再由本地或组织 Maven 仓库解析 SDK。

## 运行

主类为 `org.openofdh.example.PrepareDocument`。在本工程目录先查看中文帮助：

```shell
java -jar target/java-sdk-consumer-0.2.0.jar --help
```

`--help`（或 `-h`）返回 UTF-8 JSON 帮助和退出码 `0`，不读取请求或创建文档。业务参数依次为：

```text
request.json  inputRoot  output.ofd  report.json  [--optimize-page-order]
```

请求结构见 [SDK 使用说明](../../docs/sdk.md)；请求内 `source.path`、`clinicalData.path` 使用相对于 `inputRoot` 的路径。输出及报告的父目录必须已存在，文件必须不存在；两个输出不能使用同一路径。

Windows PowerShell，在本工程目录中执行：

```powershell
java -Dfile.encoding=UTF-8 -Djava.awt.headless=true -jar target/java-sdk-consumer-0.2.0.jar 'C:\ofd-input\request.json' 'C:\ofd-input' 'C:\ofd-output\record.ofd' 'C:\ofd-output\record-report.json' --optimize-page-order
$LASTEXITCODE
```

Linux：

```bash
java -Dfile.encoding=UTF-8 -Djava.awt.headless=true -jar target/java-sdk-consumer-0.2.0.jar /srv/ofd-input/request.json /srv/ofd-input /srv/ofd-output/record.ofd /srv/ofd-output/record-report.json --optimize-page-order
echo $?
```

上面的路径需要替换为自己的请求、输入目录和新输出文件。省略 `--optimize-page-order` 使用默认 ZIP 排列；开启后按文档页序前置页面和依赖，含义及限制见 [页面顺序优化](../../docs/page-order.md)。

附带机读数据时，在请求中声明 `clinicalData.path`、`schemaId` 和 `schemaVersion`，将临床 JSON 放在输入根目录下。入口会通过 SDK 执行本地 Schema 校验并保存附件原字节；不会从 PDF 文字中推断诊断编码或代填临床事实。三类内置规范和字段说明见 [临床 Schema](../../docs/clinical-schemas.md)。

## 结果与错误

成功摘要写入标准输出，失败摘要写入标准错误，编码为 UTF-8 JSON。不会输出异常堆栈、解析器原始错误或输入字段值。字段错误通过 `issues` 返回 JSON Pointer 和校验约束。

| 退出码 | 含义 |
| --- | --- |
| `0` | 业务调用已生成待签署文件和报告，检查仍可能为 `INCOMPLETE`；`--help` 则表示帮助输出成功 |
| `2` | 字段、来源文档或业务处理未通过，或者输出路径已有文件 |
| `4` | 文件读写、运行环境、取消或报告写入失败 |
| `64` | 参数数量、选项或路径参数不正确 |

这是接入工程自身的退出码约定，正式 CLI 的详细映射见 [错误处理](../../docs/errors.md)。

报告包含 `stage`、`code`、`message`、`artifactCreated`、`reportStatus`、`issues` 和 `truncated`。校验执行后有 `validation`，生成并复核后有 `inspection` 和 `sha256`。`artifactCreated` 仅表示**本次调用**是否已生成文档，不代表同名旧文件是否存在。

报告写入发生在文档发布之后。若返回 `REPORT_WRITE_FAILED` 且 `artifactCreated=true`，文档已经存在：保留它，使用正式 CLI 的 `check --profile PREPARED` 补报告；不要将这次调用视为可以原样重试的失败。字段失败时若报告路径可用，也会保存错误报告。报告路径本身不合法或已有文件时，只输出失败摘要。

`PREPARED` 表示待签署阶段；本工程不签署、不判断证书信任，也不把 `INCOMPLETE` 当成国家标准符合性证明。报告包含包内资源路径及输出文件名，应按本系统的文档权限保存。

## 改为自己的业务调用

保留 `Ofdh.builder().sourceAdapter(new PdfSourceAdapter()).build()`、`validate`、`prepare` 和 `check(..., CheckProfile.PREPARED)` 这条主线即可；批量调度、数据库状态、归档存储和签署对接由宿主系统管理。示例中的报告发布复用了 SDK 公共文件工具，采用同目录临时文件与不覆盖发布。

生产接入应保存输出摘要，并区分请求不合法、文档已生成但报告失败、外部签署未完成这几类状态。自定义临床 Schema 可在构建 `Ofdh` 时通过 `clinicalSchema(...)` 注册，本工程命令行只使用内置 Schema。
