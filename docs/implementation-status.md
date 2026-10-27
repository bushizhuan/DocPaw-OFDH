# 能力与兼容性

本文对应 DocPaw-OFDH 0.2.0 工程预览版。请求、报告、内置临床 Schema 及元数据映射版本为 0.1.0，分别管理。

## 支持能力

| 范围 | 当前能力 | 采用方需要确认 |
|---|---|---|
| SDK / CLI | 校验、生成、签署、检查和结构化报告 | 宿主依赖及调用生命周期 |
| 原生 OFD | 未签、未加密、单文档 OFD 1.0；页面与资源保留 | 不同导出器和阅读器 |
| PDF | 受限静态页面、可检索文字、嵌入 TrueType 字体、坐标和受支持链接 | 来源特性及逐页视觉核对 |
| 临床 JSON | 通用封套、入院记录、检验报告及自定义本地 Schema | 来源真实性、代码字典和医学语义 |
| 字段报告 | JSON Pointer、中文约束、排序、去重和 100 条上限 | 接入方展示和业务错误处理 |
| 签署 | 整文件 SPI、SM2 原文/预摘要、DER/raw、内容保持和密码验签 | 真实 CA、医生授权及独立验签 |
| 信任 | 显式信任锚下的离线证书链检查 | 吊销、可信时间及机构策略 |
| 页面布局 | 真实页序及页面依赖前置，签后重排与重新验签 | 完整线性化和阅读端互操作 |

PDF 不支持扫描件/OCR、已签或加密文件、ActualText、CFF/Type3、竖排文字及部分复杂图形和注释。未知能力应按返回错误处理，不能直接套用另一份文件的转换结论。

## 运行环境

| 项目 | 要求 |
|---|---|
| 源码构建 | JDK 21、Maven 3.9 |
| SDK / CLI 运行 | Java 8、17、21；编译目标 Java 8 |
| 契约与发行工具 | Python 3.12 及 `tools/requirements-validation.txt` |
| 输出文件系统 | 支持硬链接的本地文件系统 |
| 进程参数 | CLI 默认最大堆 1 GiB，可通过 `OFDH_MAX_HEAP` 调整 |

网络共享及 FAT/exFAT 未列为已支持输出环境；可先本地生成、核对摘要，再由宿主上传。Java 发行版、字体和依赖组合仍需在目标系统核对。

## 验证方式

```sh
mvn -B -ntp clean install
mvn -B -ntp -f examples/java-sdk/pom.xml clean verify
python tools/validate-design.py
python -m unittest discover -s tools/tests -p 'test_*.py'
python tools/check-publication.py
```

2026-10-27 的 Windows 验证记录：主工程 202 项、独立消费者 10 项，在 Java 8、17、21 测试进程下均无失败、错误或跳过；发行工具 17 项、契约 31 个正例和 110 个反例通过。构建由 JDK 21 完成。GitHub 的 Windows/Linux 运行时矩阵结果以 [Actions](https://github.com/bushizhuan/DocPaw-OFDH/actions) 中对应提交为准。

测试创建实际格式文件和临时签署身份，覆盖 XML/ZIP/路径与预算、字段及来源一致性、PDF 字体与文字、资源引用、签名与证书链、错误身份和摘要模式、输入不变及失败不发布。独立消费者测试打包后的 JAR、相对依赖路径和空格目录；本机编码可表示中文时同时使用中文安装目录。

Windows 原生 Java 启动器的命令行路径受系统本机编码影响。英文系统建议把 CLI、接入 JAR 及命令行输入输出放在 ASCII 路径；`-Dfile.encoding=UTF-8` 控制 Java 文本处理，不能恢复启动器已替换的路径字符。该限制发生在应用启动前，SDK 进程内通过 Java 文件 API 处理 UTF-8 数据不受此命令行转换影响。

原生入口行为可参见 [OpenJDK 的 Windows 启动器说明](https://github.com/openjdk/jdk/pull/20428)。

这些测试不构成院方 CA 联调、独立产品验签或临床文书逐页视觉验收。目标系统的外部验证方式见 [接入验收](acceptance-and-release.md)。

## 默认资源预算

单个输入和发布文件 100 MiB、ZIP 累计展开 1 GiB、10,000 个成员、500 页、临床 JSON 10 MiB。SDK 可通过 `ResourceLimits` 调整。底层解码器可能先分配内存，预算检查不能替代进程堆限制、超时和并发控制。

## 已签文件与失败恢复

`prepare` 只接收未签来源，签署接口当前不支持追加签名。输出拒绝覆盖，生成和报告分别落盘。业务系统应记录来源版本、文件标识、摘要和阶段；关闭接入调用即可回退，既有文书和签署记录保留。具体恢复条件见 [错误处理](errors.md)。
