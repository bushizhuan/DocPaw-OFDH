package org.openofdh.core.sign;

import org.openofdh.core.api.ResourceLimits;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 连接已授权签署能力的同步接口，调用方在发布前独立验证返回文件。
 *
 * <p>实现负责业务授权、凭据获取和服务超时。私钥、口令及访问令牌不得写入文档请求、临床附件或诊断日志。 此接口本身不授予签署权限，也不会因为服务返回成功就跳过验签。
 *
 * <p>同一实现被并发调用时须自行保证安全；异步签署流程应由宿主等待结果后再返回。
 */
public interface SignProvider {
    /**
     * 读取待签快照并将完整签署结果写入指定新文件，返回前须完成写入并关闭文件。 当前编排器允许增加签名入口与 Doc_0/Signs/ 内的对象，不允许改写原文书成员。
     *
     * @param preparedFile 只读的未签署 OFD-H 快照，不得修改或删除
     * @param signedFile 尚不存在的签署结果路径，父目录已存在；不得覆盖其他文件
     * @param limits 本次资源预算；远端调用还须有超时和取消策略
     * @throws IOException 签署拒绝、调用失败、写入失败或操作取消
     */
    void sign(Path preparedFile, Path signedFile, ResourceLimits limits) throws IOException;
}
