package org.openofdh.core.sign;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.openofdh.core.api.OfdhException;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * 经过结构和取值范围检查的 SM2 签名值，不接收完整 CMS、SignedData 或电子印章文件。
 *
 * <p>调用方必须按厂商协议选择 DER 或固定 64 字节 r||s；中间件不根据首字节猜测编码。
 */
public final class Sm2Signature {
    private static final BigInteger ORDER =
            GMNamedCurves.getByOID(GMObjectIdentifiers.sm2p256v1).getN();
    private final byte[] encoded;

    private Sm2Signature(byte[] encoded) {
        this.encoded = encoded.clone();
    }

    /**
     * 接收规范 DER SEQUENCE(INTEGER r, INTEGER s)。
     *
     * @param value 已解码的二进制 DER，不是 Base64 或十六进制文本
     * @return 不可变的签名值
     * @throws OfdhException DER 不规范、包含尾随数据或 r/s 不在合法范围内
     */
    public static Sm2Signature der(byte[] value) throws OfdhException {
        if (value == null || value.length < 8 || value.length > 72) {
            throw invalid();
        }
        byte[] snapshot = value.clone();
        try {
            ASN1Primitive primitive = ASN1Primitive.fromByteArray(snapshot);
            if (!(primitive instanceof ASN1Sequence)) throw invalid();
            ASN1Sequence sequence = (ASN1Sequence) primitive;
            if (sequence.size() != 2
                    || !(sequence.getObjectAt(0) instanceof ASN1Integer)
                    || !(sequence.getObjectAt(1) instanceof ASN1Integer)) {
                throw invalid();
            }
            BigInteger r = ((ASN1Integer) sequence.getObjectAt(0)).getValue();
            BigInteger s = ((ASN1Integer) sequence.getObjectAt(1)).getValue();
            byte[] canonical = encode(r, s);
            if (!Arrays.equals(snapshot, canonical)) throw invalid();
            return new Sm2Signature(canonical);
        } catch (OfdhException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new OfdhException("SIGNATURE_VALUE_INVALID", "SM2 签名值的 DER 编码不正确", e);
        }
    }

    /**
     * 接收固定 64 字节 r||s，每个分量为 32 字节无符号大端数。
     *
     * @param value 已解码的二进制签名，不含设备专用头部或长度字段
     * @return 规范化为 DER 的不可变签名值
     * @throws OfdhException 长度不为 64 或 r/s 不在合法范围内
     */
    public static Sm2Signature raw(byte[] value) throws OfdhException {
        if (value == null || value.length != 64) throw invalid();
        byte[] snapshot = value.clone();
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(snapshot, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(snapshot, 32, 64));
        return new Sm2Signature(encode(r, s));
    }

    public byte[] getDerEncoded() {
        return encoded.clone();
    }

    private static byte[] encode(BigInteger r, BigInteger s) throws OfdhException {
        if (r.signum() <= 0
                || s.signum() <= 0
                || r.compareTo(ORDER) >= 0
                || s.compareTo(ORDER) >= 0) {
            throw invalid();
        }
        try {
            return new DERSequence(new ASN1Encodable[] {new ASN1Integer(r), new ASN1Integer(s)})
                    .getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            throw new OfdhException("SIGNATURE_VALUE_INVALID", "无法编码 SM2 签名值", e);
        }
    }

    private static OfdhException invalid() {
        return new OfdhException("SIGNATURE_VALUE_INVALID", "SM2 签名值的编码、长度或 r/s 范围不正确");
    }
}
