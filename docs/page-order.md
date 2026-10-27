# 页面布局与渐进读取

此功能使用现有 OFD 文件组织和标准 ZIP 容器，将页面及其显示依赖按阅读顺序写入。适用于生成待签文档，以及签署适配器返回文件后的重新整理。它不会增加私有索引，也不要求阅读器采用 DocPaw-OFDH 的扩展协议。

GB/T 48666—2026 的 3.13 描述头部内容索引及优先内容前置，6.1.3 要求支持线性化；现有材料没有给出可直接实现的索引字节协议。国家标准修订计划已提出增加该章节，尚待取得具体协议。因此此功能命名为“页面布局优化”，检查项 `OFDH-015` 继续返回 `NOT_CHECKED`，不会将一次成功生成解释为完整标准验收。依据见 [标准待决问题](standard-open-questions.md)。

## 接入

Java SDK 显式启用，默认行为保持兼容：

```java
Ofdh ofdh = Ofdh.builder()
        .optimizePageOrder(true)
        .build();
PreparedDocument prepared = ofdh.prepare(request, inputRoot, preparedFile);
SignedDocument signed = ofdh.sign(prepared.getPath(), signedFile, signProvider);
```

PDF 来源在构建时照常注册 `PdfSourceAdapter`。同一 `Ofdh` 实例的生成与签署均采用所选布局；如果分别调用两个进程或创建不同实例，两次操作都需要明确启用。

```sh
ofdh prepare --request request.json --input-root input --out prepared.ofd --report prepared-report.json --optimize-page-order
ofdh sign --input prepared.ofd --out signed.ofd --report signed-report.json --keystore signer.p12 --alias signer --password-env OFDH_SIGN_PASSWORD --optimize-page-order
```

签署命令其余选项及签署适配器配置沿用 [接入说明](integration.md)。这是文件生成选项，不写入业务请求 JSON，不要求修改已有请求契约。旧的 SDK 构造器和没有此选项的命令继续使用原有打包方式。

底层调用可使用 `PageDependencyPlanner.plan(archive, limits)` 查看布局，或 `PageOrderOptimizer.publish(archive, output, limits)` 写入并取得实际布局。两者属于 `org.openofdh.core.layout`；文件包继续使用 `SafePackage` 的资源限额、路径检查及拒绝覆盖发布机制。

## 写入规则

1. 前置 `OFD.xml`、文档目录及启动所需的资源和附件目录。
2. 按 `Document.xml/Pages` 的登记顺序排列页面，不按 `Page_1`、`Page_10`、`Page_2` 的文件名排序。
3. 每页紧跟显示所需的模板、公共或页面资源、字体、图片、绘制参数、色彩配置、复合对象和批注外观依赖。共享资源只写入一次，放在首次使用的位置。
4. 附件正文、签名数据及其他剩余成员按路径排序置后；一个成员也不删除，不重写成员内容。

分析发现缺失引用、重复资源标识或循环依赖时返回 `PAGE_LAYOUT_INVALID`；无法可靠分析的绘制扩展、文档扩展或加密等输入返回 `PAGE_LAYOUT_UNSUPPORTED`；超限仍返回 `LIMIT_EXCEEDED`。开启优化后失败会中止生成，不静默改为普通布局。当前支持单文档 OFD 1.0；基础生成器本身的支持边界继续适用。

签署后重排会改变整个 ZIP 文件的摘要。签署服务先核对提供方返回的成员内容，再按布局重包，最后重新执行完整性与密码验签；报告和返回值绑定最终发布文件的摘要。不能拿签署适配器中间文件的摘要登记最终归档文件。此能力验证的是当前支持的 OFD 按成员摘要签名，不能推断任意第三方签章封装都兼容。

## 阅读端要求

标准 ZIP 的中央目录在文件尾部。支持随机读取的阅读器先读取文件尾及中央目录，再根据成员偏移读取所需页面；页面前置不会使只接收一个文件前缀的阅读器自动具备随机读取能力，也没有消除文件尾请求。普通 OFD 同样可以按中央目录随机读取；此优化让同页依赖集中靠前，便于连续预读或合并范围请求，不承诺所有阅读器的请求数和下载量自动减少。

网络服务仍需支持 HTTP Range、正确的 `206` 和 `Content-Range`，并在多次读取间保持文件版本稳定。中间件不提供 HTTP 服务。机读 JSON 的正文仍是按需读取的附件，阅读器不必为展示第一页下载全部临床数据。

2026-10-26 的 19 页标准 PDF 文件实验中，同一份输出的第一页依赖所覆盖的文件前缀由默认排序的 4,492,098 字节缩短为 161,869 字节，成员内容完全一致。使用独立阅读端代码按标准 ZIP 随机读取首屏共需 227,426 字节，约占 4,501,988 字节全包的 5.05%。这是特定输入的文件读取及结构/文字解析证据，未覆盖浏览器视觉渲染或 HTTP 部署；原始实验文件不随仓库分发，详见 [测试记录](implementation-status.md)。

回退时去掉 `optimizePageOrder(true)` 或命令行选项即可。已生成文件仍是普通 OFD/ZIP；保留原始输入及已归档版本，不覆盖或删除历史签署文件。
