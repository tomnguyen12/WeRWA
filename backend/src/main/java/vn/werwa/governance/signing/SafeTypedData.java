package vn.werwa.governance.signing;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * EIP-712 của Safe 1.4.1 (Q108): {@code SafeTx} của Safe thực hiện, {@code SafeMessage} của Safe tổ chức (xác thực qua
 * {@code CompatibilityFallbackHandler}, EIP-1271), và cách ghép chữ ký cho {@code execTransaction}. Giao dịch luôn là CALL,
 * giá trị 0, không hoàn phí.
 */
public final class SafeTypedData {

    static final String ZERO = "0x0000000000000000000000000000000000000000";
    static final byte[] DOMAIN_TYPEHASH = keccak("EIP712Domain(uint256 chainId,address verifyingContract)");
    static final byte[] SAFE_TX_TYPEHASH = keccak("SafeTx(address to,uint256 value,bytes data,uint8 operation,"
            + "uint256 safeTxGas,uint256 baseGas,uint256 gasPrice,address gasToken,address refundReceiver,uint256 nonce)");
    static final byte[] SAFE_MSG_TYPEHASH = keccak("SafeMessage(bytes message)");

    private SafeTypedData() {}

    /** {@code encodeTransactionData}: 0x1901 ‖ domainSeparator ‖ structHash (66 byte). */
    public static byte[] safeTxData(long chainId, String safe, String to, String data, long nonce) {
        byte[] struct = Hash.sha3(concat(SAFE_TX_TYPEHASH, address(to), uint(BigInteger.ZERO),
                Hash.sha3(Numeric.hexStringToByteArray(data)), uint(BigInteger.ZERO), uint(BigInteger.ZERO),
                uint(BigInteger.ZERO), uint(BigInteger.ZERO), address(ZERO), address(ZERO), uint(BigInteger.valueOf(nonce))));
        return concat(new byte[] {0x19, 0x01}, domainSeparator(chainId, safe), struct);
    }

    /** Mã băm SafeTx mà Safe kiểm chữ ký. */
    public static byte[] safeTxHash(long chainId, String safe, String to, String data, long nonce) {
        return Hash.sha3(safeTxData(chainId, safe, to, data, nonce));
    }

    /**
     * Mã băm người ký của Safe tổ chức ký khi Safe đó là owner của Safe liên bên: {@code SafeMessage} của Safe tổ chức với
     * {@code message} là dữ liệu SafeTx liên bên ({@code getMessageHashForSafe} của fallback handler).
     */
    public static byte[] safeMessageHash(long chainId, String orgSafe, byte[] message) {
        byte[] struct = Hash.sha3(concat(SAFE_MSG_TYPEHASH, Hash.sha3(message)));
        return Hash.sha3(concat(new byte[] {0x19, 0x01}, domainSeparator(chainId, orgSafe), struct));
    }

    static byte[] domainSeparator(long chainId, String safe) {
        return Hash.sha3(concat(DOMAIN_TYPEHASH, uint(BigInteger.valueOf(chainId)), address(safe)));
    }

    /** Dữ liệu cho {@code eth_signTypedData_v4}: SafeTx của Safe tổ chức. */
    public static Map<String, Object> safeTxTypedData(long chainId, String safe, String to, String data, long nonce) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("to", to);
        message.put("value", "0");
        message.put("data", data);
        message.put("operation", 0);
        message.put("safeTxGas", "0");
        message.put("baseGas", "0");
        message.put("gasPrice", "0");
        message.put("gasToken", ZERO);
        message.put("refundReceiver", ZERO);
        message.put("nonce", Long.toString(nonce));
        return typed("SafeTx", List.of(field("to", "address"), field("value", "uint256"), field("data", "bytes"),
                field("operation", "uint8"), field("safeTxGas", "uint256"), field("baseGas", "uint256"),
                field("gasPrice", "uint256"), field("gasToken", "address"), field("refundReceiver", "address"),
                field("nonce", "uint256")), chainId, safe, message);
    }

    /** Dữ liệu cho {@code eth_signTypedData_v4}: SafeMessage của Safe tổ chức bọc SafeTx liên bên. */
    public static Map<String, Object> safeMessageTypedData(long chainId, String orgSafe, byte[] message) {
        return typed("SafeMessage", List.of(field("message", "bytes")), chainId, orgSafe,
                Map.of("message", Numeric.toHexString(message)));
    }

    /** Địa chỉ ký ra chữ ký 65 byte trên mã băm; null nếu chữ ký hỏng. */
    public static String recover(byte[] hash, String signatureHex) {
        try {
            byte[] sig = Numeric.hexStringToByteArray(signatureHex);
            if (sig.length != 65) {
                return null;
            }
            byte v = sig[64];
            if (v < 27) {
                v += 27;
            }
            Sign.SignatureData data = new Sign.SignatureData(v, java.util.Arrays.copyOfRange(sig, 0, 32),
                    java.util.Arrays.copyOfRange(sig, 32, 64));
            return "0x" + Keys.getAddress(Sign.signedMessageHashToKey(hash, data)).toLowerCase(Locale.ROOT);
        } catch (RuntimeException | java.security.SignatureException e) {
            return null;
        }
    }

    /** Chữ ký EOA của một Safe: sắp tăng theo địa chỉ người ký, lấy đủ ngưỡng, v chuẩn 27/28. */
    public static byte[] ownerSignatures(Map<String, String> signatureBySigner, int threshold) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        signatureBySigner.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(threshold).forEach(e -> {
            byte[] sig = Numeric.hexStringToByteArray(e.getValue());
            if (sig[64] < 27) {
                sig[64] += 27;
            }
            out.writeBytes(sig);
        });
        return out.toByteArray();
    }

    /**
     * Chữ ký hợp đồng (v = 0) cho Safe liên bên: phần tĩnh {@code r = owner}, {@code s = vị trí phần động}; phần động là
     * độ dài ‖ chữ ký của Safe tổ chức. Owner sắp tăng theo địa chỉ.
     */
    public static byte[] contractSignatures(Map<String, byte[]> signatureByOwnerSafe) {
        List<Map.Entry<String, byte[]>> sorted = new ArrayList<>(signatureByOwnerSafe.entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey));
        ByteArrayOutputStream statics = new ByteArrayOutputStream();
        ByteArrayOutputStream dynamics = new ByteArrayOutputStream();
        long offset = 65L * sorted.size();
        for (Map.Entry<String, byte[]> e : sorted) {
            statics.writeBytes(address(e.getKey()));
            statics.writeBytes(uint(BigInteger.valueOf(offset + dynamics.size())));
            statics.write(0);
            dynamics.writeBytes(uint(BigInteger.valueOf(e.getValue().length)));
            dynamics.writeBytes(e.getValue());
        }
        statics.writeBytes(dynamics.toByteArray());
        return statics.toByteArray();
    }

    private static Map<String, Object> typed(String primary, List<Map<String, String>> fields, long chainId, String contract,
            Map<String, Object> message) {
        Map<String, Object> types = new LinkedHashMap<>();
        types.put("EIP712Domain", List.of(field("chainId", "uint256"), field("verifyingContract", "address")));
        types.put(primary, fields);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("types", types);
        out.put("primaryType", primary);
        out.put("domain", Map.of("chainId", chainId, "verifyingContract", contract));
        out.put("message", message);
        return out;
    }

    private static Map<String, String> field(String name, String type) {
        return Map.of("name", name, "type", type);
    }

    static byte[] keccak(String s) {
        return Hash.sha3(s.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] uint(BigInteger v) {
        return Numeric.toBytesPadded(v, 32);
    }

    static byte[] address(String a) {
        return Numeric.toBytesPadded(Numeric.toBigInt(a), 32);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
