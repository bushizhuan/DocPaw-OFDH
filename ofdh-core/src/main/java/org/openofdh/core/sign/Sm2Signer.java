package org.openofdh.core.sign;

import java.io.IOException;

/**
 * 使用调用方已获授权的 SM2 身份完成一次签名，不负责 OFD 文件结构。
 *
 * <p>实现应严格按请求声明的输入模式调用 CA 或密码设备，返回签名值即可。服务授权、证书选择、超时及取消由实现方处理；
 * 私钥、访问令牌及服务响应不得写入文档或普通日志。同一实例被并发使用时，实现方须保证线程安全。
 */
@FunctionalInterface
public interface Sm2Signer {
    /**
     * 同步完成签名。异步服务应等待实际结果后再返回，失败时不能返回空值或占位签名。
     *
     * @param request 本次待签字节、输入模式、固定用户标识及签署证书
     * @return 已明确编码的真实 SM2 签名值
     * @throws IOException 签署被拒绝、服务调用失败或操作取消
     */
    Sm2Signature sign(Sm2SigningRequest request) throws IOException;
}
