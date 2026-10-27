# 生成第一份 OFD-H 文件

本文用一份接入方提供的文书完成字段校验、生成和检查。先跑通未签署文件，再接入临床 JSON 和 CA。

## 1. 准备程序

从 [Releases](https://github.com/bushizhuan/DocPaw-OFDH/releases) 下载 CLI ZIP 并解压。运行需要 Java 8、17 或 21，程序从 `JAVA_HOME` 或 `PATH` 查找 Java。

也可以使用 JDK 21、Maven 3.9 构建源码：

```sh
git clone https://github.com/bushizhuan/DocPaw-OFDH.git
cd DocPaw-OFDH
mvn -B -ntp clean install
```

构建生成 `ofdh-cli/target/ofdh-0.2.0.zip`，解压后使用方式相同。

## 2. 准备目录

`source.ofd` 是你导出的未签署单文档 OFD 1.0；`output` 先建为空目录：

```text
work/
├── ofdh-0.2.0/
│   ├── bin/ofdh
│   ├── bin/ofdh.cmd
│   └── lib/
├── input/source.ofd
├── output/
└── request.json
```

输入 PDF 时，将文件放为 `input/source.pdf`，并在下一节同时修改 `source.format` 和 `source.path`。PDF 应为静态可检索文件并嵌入所需 TrueType 字体，完整范围见 [接入说明](integration.md)。

英文 Windows 建议使用 `C:/ofdh-work` 等 ASCII 工作路径。Java 原生启动器需要按系统本机编码解析安装和命令行路径；仅设置 `file.encoding=UTF-8` 不能解决启动前的路径字符替换。

## 3. 填写请求

创建 UTF-8 的 `request.json`。下面 `<…>` 标记均须替换为该份文书的实际值；时间只展示格式，应填写来源记录的创建时间。

```json
{
  "contractVersion": "0.1.0",
  "documentId": "<业务文书标识>",
  "sourceSystem": "<来源系统标识>",
  "sourceRecordId": "<来源记录标识>",
  "sourceVersion": "<本次来源版本>",
  "documentType": {
    "code": "<文书类别代码>",
    "scheme": "<文书类别代码体系>"
  },
  "patient": {
    "id": "<患者标识>",
    "authority": "<患者标识分配机构>"
  },
  "responsibleParty": {
    "id": "<责任者标识>",
    "name": "<责任者姓名>",
    "authority": "<责任者标识分配机构>"
  },
  "createdAt": "2026-10-27T08:30:00+08:00",
  "keywords": ["<文书关键词>"],
  "confidentiality": {
    "code": "<密级代码>",
    "scheme": "<密级代码体系>"
  },
  "source": {
    "format": "OFD",
    "path": "source.ofd"
  }
}
```

`source.path` 相对于命令中的输入根目录，使用 `/` 分隔；不要填写绝对路径、URL 或 `..`。`patient.authority` 和代码对象的 `scheme` 用于明确标识体系，应在接入系统内保持稳定。

请求 Schema 检查数据结构，实际的患者、责任者、代码和业务版本由来源系统负责。完整字段定义见 [document.schema.json](../contracts/document.schema.json)。

## 4. 执行生成

在 `work` 目录执行以下命令。

Windows PowerShell：

```powershell
& ./ofdh-0.2.0/bin/ofdh.cmd --version
& ./ofdh-0.2.0/bin/ofdh.cmd validate-request --request ./request.json
& ./ofdh-0.2.0/bin/ofdh.cmd prepare --request ./request.json --input-root ./input --out ./output/record.ofd --report ./output/prepared-report.json
& ./ofdh-0.2.0/bin/ofdh.cmd check --input ./output/record.ofd --profile PREPARED --report ./output/check-report.json
$LASTEXITCODE
```

Linux：

```sh
./ofdh-0.2.0/bin/ofdh --version
./ofdh-0.2.0/bin/ofdh validate-request --request ./request.json
./ofdh-0.2.0/bin/ofdh prepare --request ./request.json --input-root ./input --out ./output/record.ofd --report ./output/prepared-report.json
./ofdh-0.2.0/bin/ofdh check --input ./output/record.ofd --profile PREPARED --report ./output/check-report.json
echo $?
```

`validate-request` 成功返回 `valid=true`。`prepare` 成功返回 `stage=PREPARED`、文件标识、输入及输出摘要。此时 `record.ofd` 是待签文件，保存其摘要和报告。

`check` 通常返回 `3`，报告中的 `assessment` 为 `INCOMPLETE`：签署及部分外部证据仍未完成。返回 `2` 表示本次检查发现失败；返回 `4` 表示读写或运行问题。批处理脚本应分别处理这些状态，见 [错误处理](errors.md)。

所有输出文件都必须使用新文件名。再次执行相同命令会返回 `OUTPUT_EXISTS`，不会覆盖第一次的产物。

## 5. 封装临床 JSON

按 [临床 Schema](clinical-schemas.md) 导出同一业务版本的临床数据，例如保存到 `input/clinical.json`，然后在请求中增加：

```json
{
  "clinicalData": {
    "path": "clinical.json",
    "schemaId": "urn:openofdh:clinical:admission-record",
    "schemaVersion": "0.1.0"
  }
}
```

内置入院记录还要求就诊信息；应同时按 Schema 补全请求和临床 JSON 的对应字段。运行以下命令可先定位数据问题：

```sh
ofdh validate-clinical --request request.json --input-root input
```

通过后再生成一个新的输出文件。工具登记附件、保存 JSON 原字节和 SHA-256；临床字段不要求在页面上逐一出现，但其来源和含义应与这份文书一致。

## 6. 接入签署和归档

已有完整 OFD 签章服务时，实现 `SignProvider`。CA 仅提供签名值时，使用 `Sm2SignProvider.forMessage` 或 `forDigest`；数据模式、用户标识及返回编码需要与厂商接口逐项对应。参见 [CA 接入](ca-integration.md)。

签署完成后按 `FINAL` 阶段检查，按机构配置的信任锚核对证书链，再交给原系统归档。建议保存来源版本、文件标识、最终摘要及检查报告。文档和报告分别落盘，恢复方式见 [完整接入说明](integration.md)。

## 首次运行常见问题

| 现象 | 处理 |
|---|---|
| Java 命令不存在 | 安装运行时，并设置 `JAVA_HOME` 或 `PATH` |
| `REQUEST_INVALID` | 按 `issues[].path` 修正字段，日期要带秒和时区 |
| `PATH_INVALID` | 输入路径改为根目录内的相对路径，并使用 `/` |
| `SOURCE_PROFILE_UNSUPPORTED` | 核对来源版本、签署状态和受支持的 PDF 特性 |
| `FONT_UNRESOLVED` | 从源系统重新导出含嵌入 TrueType 字体的 PDF |
| `OUTPUT_EXISTS` | 核对已有产物，使用新的输出路径 |
| `INCOMPLETE` | 查看未覆盖项，继续签署或取得相应外部验证证据 |
