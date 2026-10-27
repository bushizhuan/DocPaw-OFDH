package org.openofdh.core.api;

import org.openofdh.core.check.CheckProfile;
import org.openofdh.core.check.CheckReport;
import org.openofdh.core.check.CheckService;
import org.openofdh.core.internal.PageSourceAdapters;
import org.openofdh.core.prepare.PrepareService;
import org.openofdh.core.prepare.PreparedDocument;
import org.openofdh.core.sign.SignProvider;
import org.openofdh.core.sign.SignedDocument;
import org.openofdh.core.sign.SigningService;
import org.openofdh.core.validation.ClinicalSchemaRegistry;
import org.openofdh.core.validation.ClinicalSchemas;
import org.openofdh.core.validation.RequestReader;
import org.openofdh.core.validation.ValidationReport;

import java.io.IOException;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 文档准备、校验和签署的统一入口。
 *
 * <p>实例可重复使用。并发处理不同文件时，注册的转换器及传入的签署提供者也必须支持并发， 每次调用应使用独立输出路径。构建器不支持并发修改。临床 Schema
 * 在构建时读取，后续处理不下载网络规则。
 *
 * <p>文件处理会检查线程中断，但不能保证第三方转换器或签署服务立即停止；宿主仍需设置操作期限和进程资源预算。
 */
public final class Ofdh {
    private final PrepareService preparation;
    private final CheckService inspection;
    private final SigningService signing;
    private final ClinicalSchemaRegistry schemas;

    private Ofdh(
            List<PageSourceAdapter> adapters,
            ClinicalSchemaRegistry schemas,
            ResourceLimits limits,
            boolean optimizePageOrder) {
        this.schemas = schemas;
        this.preparation = new PrepareService(adapters, schemas, limits, optimizePageOrder);
        this.inspection = new CheckService(limits);
        this.signing = new SigningService(limits, optimizePageOrder);
    }

    /**
     * 使用默认资源预算和内置临床 Schema 创建实例，只接收原生 OFD 来源。
     *
     * @return 可重复使用的文件处理入口
     * @throws IOException 内置 Schema 无法读取或编译
     */
    public static Ofdh create() throws IOException {
        return builder().build();
    }

    /**
     * 创建独立的配置构建器；默认不开启页面顺序优化，也不注册 PDF 转换器。
     *
     * @return 尚未读取业务文件的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 校验请求字段，不打开请求引用的版式文件或临床数据文件。
     *
     * @param request 待检请求，允许字段暂时缺失
     * @return 字段校验报告；字段不合法时返回无效报告，不以异常代替问题列表
     * @throws OfdhException 校验规则无法执行
     */
    public ValidationReport validate(DocumentRequest request) throws OfdhException {
        return RequestReader.inspect(request);
    }

    /**
     * 从 UTF-8 JSON 文件校验请求字段，不读取其中引用的业务文件。
     *
     * @param requestFile 请求 JSON 文件
     * @return 字段校验报告，错误定位使用相对于请求根节点的 JSON Pointer
     * @throws IOException 文件读取失败、JSON 语法不合法，或校验无法执行
     */
    public ValidationReport validate(Path requestFile) throws IOException {
        return RequestReader.inspect(Objects.requireNonNull(requestFile, "请求文件不能为空"));
    }

    /**
     * 按请求声明的 Schema 校验临床 JSON，并检查已选项目封套与请求元数据的一致性。 此方法不读取页面内容，也不判断诊断正确性或页面正文与临床正文是否一致。
     *
     * @param request 包含临床 Schema 标识和版本的请求
     * @param clinicalFile 实际临床 JSON 文件路径，不通过请求中的相对路径自动定位
     * @return 字段报告；请求错误的路径相对于请求，临床错误的路径相对于临床 JSON
     * @throws IOException 文件读取、JSON 解析或校验执行失败
     */
    public ValidationReport validateClinical(DocumentRequest request, Path clinicalFile)
            throws IOException {
        return schemas.inspect(request, Objects.requireNonNull(clinicalFile, "临床数据文件不能为空"));
    }

    /**
     * 生成一个新的待签署版本，固定输入快照、校验请求和临床数据，并在发布前检查文件。 不修改来源文件，不覆盖已有输出；成功不表示已经签署或通过完整标准符合性验收。
     *
     * @param request 同一来源版本的业务标识、元数据及文件引用
     * @param inputRoot 已存在的输入根目录；请求中的版式和临床路径均相对此目录解析，不能越界
     * @param output 尚不存在的输出文件，父目录须已存在且支持本地硬链接发布
     * @return 已发布待签文件的路径、文件标识及来源和产物摘要
     * @throws OfdhException 输入不合法、不支持当前来源、资源超限、处理取消或输出无法安全发布
     * @throws IOException 文件读取、转换或写入失败
     */
    public PreparedDocument prepare(DocumentRequest request, Path inputRoot, Path output)
            throws IOException {
        return preparation.prepare(
                request,
                Objects.requireNonNull(inputRoot, "输入根目录不能为空"),
                Objects.requireNonNull(output, "输出文件不能为空"));
    }

    /**
     * 读取请求 JSON 后生成待签署文件；文件行为与请求对象重载一致。
     *
     * @param requestFile UTF-8 请求 JSON 文件
     * @param inputRoot 请求引用文件所在的已存在根目录
     * @param output 尚不存在的输出文件，父目录须已存在
     * @return 新发布的待签文件及摘要
     * @throws IOException 请求读取、字段校验、文件处理或发布失败
     */
    public PreparedDocument prepare(Path requestFile, Path inputRoot, Path output)
            throws IOException {
        return prepare(
                RequestReader.read(Objects.requireNonNull(requestFile, "请求文件不能为空")),
                inputRoot,
                output);
    }

    /**
     * 按最终阶段检查文件，不修改输入，也不自动信任文档携带的证书。 待签文件应使用显式指定 {@link CheckProfile#PREPARED} 的重载。
     *
     * @param input 待检 OFD/OFD-H 文件
     * @return 绑定实际文件摘要的报告；可判定的内容问题记录为规则失败，不必然抛异常
     * @throws IOException 无法固定或读取文件快照，或操作被取消
     */
    public CheckReport check(Path input) throws IOException {
        return check(input, CheckProfile.FINAL);
    }

    /**
     * 按指定阶段检查文件，未提供信任锚时证书信任状态保持未知。
     *
     * @param input 待检文件
     * @param profile PREPARED 检查待签文件，FINAL 检查签署后的文件
     * @return 含逐条结论及未覆盖范围的检查报告
     * @throws IOException 无法读取输入或操作被取消
     */
    public CheckReport check(Path input, CheckProfile profile) throws IOException {
        return inspection.check(
                Objects.requireNonNull(input, "待检查文件不能为空"),
                Objects.requireNonNull(profile, "检查阶段不能为空"));
    }

    /**
     * 使用调用方明确选择的信任锚执行本次检查，不修改全局信任配置。 离线证书链检查不替代签署授权、在线吊销查询或可信时间验证。
     *
     * @param input 待检文件
     * @param profile 检查阶段
     * @param trustRoots 本次受信任的根证书集合，可为空；调用期间不得修改
     * @return 分别列明密码验签和证书信任状态的报告
     * @throws IOException 无法读取输入或操作被取消
     */
    public CheckReport check(Path input, CheckProfile profile, Set<X509Certificate> trustRoots)
            throws IOException {
        return inspection.check(
                Objects.requireNonNull(input, "待检查文件不能为空"),
                Objects.requireNonNull(profile, "检查阶段不能为空"),
                Objects.requireNonNull(trustRoots, "信任锚集合不能为空"));
    }

    /**
     * 由提供者签署私有输入快照，核对原文内容，再检查最终文件的实际签名后发布。 如启用了页面顺序优化，将对重排后的最终字节重新验签。输入文件不变，已有输出不会被覆盖。
     *
     * @param preparedFile 未签署的待签文件，不支持追加签名
     * @param output 尚不存在的签署输出文件，父目录须已存在且支持本地硬链接发布
     * @param provider 已获业务授权的同步签署实现；凭据由提供者管理，不放入请求元数据
     * @return 已发布签署文件、实际摘要及最终检查报告；不证明业务授权和完整标准符合性
     * @throws OfdhException 签前检查、内容保全、密码验签或安全发布失败
     * @throws IOException 签署服务调用或文件处理失败
     */
    public SignedDocument sign(Path preparedFile, Path output, SignProvider provider)
            throws IOException {
        return signing.sign(
                Objects.requireNonNull(preparedFile, "待签署文件不能为空"),
                Objects.requireNonNull(output, "签署输出文件不能为空"),
                Objects.requireNonNull(provider, "签署适配器不能为空"));
    }

    /** 可变配置构建器；实例只供一个线程配置，构建后可继续创建独立入口。 */
    public static final class Builder {
        private ResourceLimits limits = ResourceLimits.defaults();
        private boolean optimizePageOrder;
        private final Map<String, PageSourceAdapter> adapters = new LinkedHashMap<>();
        private final List<SchemaRegistration> registrations = new ArrayList<>();

        private Builder() {}

        /**
         * 设置单次文件处理的资源预算。
         *
         * @param value 不可变资源限额
         * @return 当前构建器
         */
        public Builder limits(ResourceLimits value) {
            this.limits = Objects.requireNonNull(value, "资源限额不能为空");
            return this;
        }

        /**
         * 按文档页序前置页面及依赖资源；默认关闭，不生成私有索引。
         *
         * @param enabled 是否同时优化该实例生成和签署的文件布局
         * @return 当前构建器
         */
        public Builder optimizePageOrder(boolean enabled) {
            this.optimizePageOrder = enabled;
            return this;
        }

        /**
         * 显式注册 PDF 转换器；原生 OFD 由内置流程处理，不能被替换。
         *
         * @param adapter 格式标识必须精确为 PDF 的转换器
         * @return 当前构建器
         * @throws IllegalArgumentException 格式为空、不受支持或重复注册
         * @throws NullPointerException 转换器为空
         */
        public Builder sourceAdapter(PageSourceAdapter adapter) {
            PageSourceAdapters.register(adapters, adapter);
            return this;
        }

        /**
         * 登记自定义临床 Schema；文件在构建时读取，不立即编译。
         *
         * @param schemaId 请求使用的稳定规范标识，不作为网络下载地址
         * @param version 规范版本，与标识共同确定唯一注册项
         * @param definition 本地 Draft 2020-12 Schema，仅允许文件内引用
         * @return 当前构建器；标识、版本冲突及规则错误在构建时报告
         */
        public Builder clinicalSchema(String schemaId, String version, Path definition) {
            registrations.add(
                    new SchemaRegistration(
                            Objects.requireNonNull(schemaId, "临床 Schema 标识不能为空"),
                            Objects.requireNonNull(version, "临床 Schema 版本不能为空"),
                            Objects.requireNonNull(definition, "临床 Schema 文件不能为空")));
            return this;
        }

        /**
         * 注册内置 Schema 并编译自定义 Schema，后续文件处理复用已编译的规则。
         *
         * @return 独立的处理入口；仍共享调用方提供的转换器实例
         * @throws IOException Schema 文件无法读取、标识版本重复或规则不受支持
         * @throws IllegalArgumentException 配置值不合法，或转换器在登记后改变了格式声明
         */
        public Ofdh build() throws IOException {
            ClinicalSchemaRegistry registry =
                    new ClinicalSchemaRegistry(limits.getMaxClinicalBytes());
            ClinicalSchemas.registerDefaults(registry);
            for (SchemaRegistration registration : registrations) {
                registry.register(registration.id, registration.version, registration.path);
            }
            return new Ofdh(
                    new ArrayList<>(adapters.values()), registry, limits, optimizePageOrder);
        }
    }

    private static final class SchemaRegistration {
        private final String id;
        private final String version;
        private final Path path;

        private SchemaRegistration(String id, String version, Path path) {
            this.id = id;
            this.version = version;
            this.path = path;
        }
    }
}
