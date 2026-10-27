# 构建与发布

## 本地构建

使用 JDK 21、Maven 3.9 和 Python 3.12。在仓库根目录执行：

```sh
mvn -B -ntp clean install
mvn -B -ntp -f examples/java-sdk/pom.xml clean verify
python -m pip install -r tools/requirements-validation.txt
python tools/validate-design.py
python -m unittest discover -s tools/tests -p 'test_*.py'
python tools/check-publication.py
```

根目录安装 SDK 后，独立消费者才能解析对应版本。该消费者没有父 POM 继承或 reactor 注入，验证打包后的 JAR 及实际依赖路径。

在 PowerShell 7 执行 `./tools/audit-dependencies.ps1`，按当前 SBOM 查询第三方组件公告；只发送 Maven 坐标和版本。结果保存在 `target/dependency-audit.json`。需要联网，失败或响应不完整时停止发行。

## 收集候选

```sh
python tools/collect-release.py
```

结果位于 `dist/ofdh-0.2.0/`，包含 CLI ZIP、三个模块的运行/源码/Javadoc JAR、四个 POM、JSON/XML SBOM、ZIP 摘要及 `SHA256SUMS`。

收集器核对模块坐标、归档成员、许可原文、ZIP 内外本项目制品一致性，以及 SBOM 中第三方运行 JAR 的 SHA-256。任何不一致都不会生成成功摘要清单。

已有版本目录拒绝覆盖。修订候选可用 `--output dist/candidate-2` 指定新父目录；公开版本的后续修订应使用新版本号。`dist/` 不受 Maven clean 影响，且不提交到源码仓库。

Windows 可通过 `tools/release.ps1` 顺序执行构建、消费者、契约、发行工具及依赖检查后收集。需要指定 Python 时使用 `-PythonCommand`。

## 公开仓库

项目仓库为 [bushizhuan/DocPaw-OFDH](https://github.com/bushizhuan/DocPaw-OFDH)。Java/Maven 坐标使用 `org.openofdh`，命令和 ZIP 使用 `ofdh`，项目显示名为 DocPaw-OFDH。

提交前检查实际暂存区：

```sh
python tools/check-publication.py --staged
git diff --cached --stat
```

同时人工核对文件清单、资源授权和敏感内容。发布检查只是有限的文件类型、路径、大小、常见凭据及链接检查，不能替代内容审阅。

GitHub Actions 执行 Windows/Linux × Java 8/17/21、独立消费者、契约和发行工具验证，并上传对应制品。以目标提交的实际任务结果为准。

## GitHub Release

工程预览使用预发布标记。上传通过验证的 CLI ZIP、模块 JAR/POM、双格式 SBOM 和摘要清单，说明版本、支持范围及兼容性。发布前确认标签对应验证过的提交，并保留上游 LICENSE/NOTICE 与补充许可。

SHA-256 用于核对下载字节，不代表发行者身份认证。源代码归档由 GitHub 为标签生成；真实文书、签署身份、本地日志和内部开发记录不进入仓库或发行包。
