package org.openofdh.core.sign;

import java.security.cert.X509Certificate;

/** 一次 SM2 签署的不可变输入；字节数组访问器均返回副本。 */
public final class Sm2SigningRequest {
    /** 输入字节在密码运算中的含义，不能仅根据长度或厂商参数名称推断。 */
    public enum InputType {
        /**
         * 对消息 M 执行 SM3withSM2。当前 M 是 Signature.xml 原字节的 32 字节 SM3 摘要； 签署端仍须计算 SM2 的 ZA 及 SM3(ZA ||
         * M)，不可将 M 直接当作最终摘要 e。
         */
        MESSAGE,

        /** 已计算的最终摘要 e = SM3(ZA || M)，交给接受预摘要的 SM2 运算接口。 签署端不得再次执行 SM3，也不得再次拼接 ZA。 */
        SM2_DIGEST
    }

    private final InputType inputType;
    private final byte[] data;
    private final byte[] userId;
    private final X509Certificate certificate;

    Sm2SigningRequest(
            InputType inputType, byte[] data, byte[] userId, X509Certificate certificate) {
        this.inputType = inputType;
        this.data = data.clone();
        this.userId = userId.clone();
        this.certificate = certificate;
    }

    public InputType getInputType() {
        return inputType;
    }

    /** 返回应提交给本次签署接口的原始字节；Base64 只用于传输，不参与签名。 */
    public byte[] getData() {
        return data.clone();
    }

    /** 返回固定 ASCII 用户标识 1234567812345678 的副本，不是医生或患者的业务标识。 */
    public byte[] getUserId() {
        return userId.clone();
    }

    /** 返回本次签名必须匹配的签署证书；证书由中间件从 DER 固定，不携带私钥。 */
    public X509Certificate getCertificate() {
        return certificate;
    }
}
