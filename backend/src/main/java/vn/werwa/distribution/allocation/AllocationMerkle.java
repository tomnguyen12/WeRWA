package vn.werwa.distribution.allocation;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.web3j.crypto.Hash;

/**
 * Lá và cây Merkle của danh sách phân bổ, khớp {@code SeriesLeaves.allocationLeaf} và {@code MerkleProof} của
 * OpenZeppelin (cặp nút sắp xếp rồi keccak256; nút lẻ cuối tầng đưa thẳng lên).
 *
 * <p>Lá: {@code keccak256(bytes.concat(keccak256(abi.encode(seriesController, allocationId, wallet, quantity))))}.
 * UUID sang {@code bytes32}: {@code bytes32(uint256(uint128(uuid)))} (Q43).
 */
public final class AllocationMerkle {

    private static final HexFormat HEX = HexFormat.of();

    private AllocationMerkle() {}

    /** Q43: 16 byte đầu là 0, 16 byte sau là UUID. */
    public static byte[] uuidToBytes32(UUID id) {
        return ByteBuffer.allocate(32).position(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array();
    }

    public static byte[] leaf(String seriesController, UUID allocationId, String wallet, long quantity) {
        byte[] encoded = ByteBuffer.allocate(128)
                .put(address(seriesController))
                .put(uuidToBytes32(allocationId))
                .put(address(wallet))
                .put(uint256(quantity))
                .array();
        return Hash.sha3(Hash.sha3(encoded));
    }

    /**
     * Lá lô xác nhận tiền: {@code keccak256(bytes.concat(keccak256(abi.encode(seriesController, allocationId, amountMinor,
     * bankRefHash))))} với {@code bankRefHash = keccak256(bankRef UTF-8)} (Q53).
     */
    public static byte[] paymentLeaf(String seriesController, UUID allocationId, long amountMinor, String bankRef) {
        byte[] encoded = ByteBuffer.allocate(128)
                .put(address(seriesController))
                .put(uuidToBytes32(allocationId))
                .put(uint256(amountMinor))
                .put(bankRefHash(bankRef))
                .array();
        return Hash.sha3(Hash.sha3(encoded));
    }

    public static byte[] bankRefHash(String bankRef) {
        return Hash.sha3(bankRef.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Gốc của các lá theo thứ tự cho trước. */
    public static byte[] root(List<byte[]> leaves) {
        if (leaves.isEmpty()) {
            throw new IllegalArgumentException("danh sách rỗng");
        }
        List<byte[]> level = leaves;
        while (level.size() > 1) {
            level = next(level);
        }
        return level.getFirst();
    }

    /** Đường chứng minh cho lá ở vị trí {@code index}. */
    public static List<byte[]> proof(List<byte[]> leaves, int index) {
        List<byte[]> out = new ArrayList<>();
        List<byte[]> level = leaves;
        int idx = index;
        while (level.size() > 1) {
            int sibling = idx ^ 1;
            if (sibling < level.size()) {
                out.add(level.get(sibling));
            }
            level = next(level);
            idx /= 2;
        }
        return out;
    }

    /** Kiểm như {@code MerkleProof.verify}. */
    public static boolean verify(List<byte[]> proof, byte[] root, byte[] leaf) {
        byte[] computed = leaf;
        for (byte[] p : proof) {
            computed = hashPair(computed, p);
        }
        return Arrays.equals(computed, root);
    }

    static byte[] hashPair(byte[] a, byte[] b) {
        byte[] concat = new byte[64];
        boolean aFirst = Arrays.compareUnsigned(a, b) < 0;
        System.arraycopy(aFirst ? a : b, 0, concat, 0, 32);
        System.arraycopy(aFirst ? b : a, 0, concat, 32, 32);
        return Hash.sha3(concat);
    }

    private static List<byte[]> next(List<byte[]> level) {
        List<byte[]> up = new ArrayList<>();
        for (int i = 0; i < level.size(); i += 2) {
            up.add(i + 1 < level.size() ? hashPair(level.get(i), level.get(i + 1)) : level.get(i));
        }
        return up;
    }

    public static String hex(byte[] b) {
        return "0x" + HEX.formatHex(b);
    }

    private static byte[] address(String hex0x) {
        byte[] raw = HEX.parseHex(hex0x.substring(2).toLowerCase(java.util.Locale.ROOT));
        if (raw.length != 20) {
            throw new IllegalArgumentException("địa chỉ không hợp lệ: " + hex0x);
        }
        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 12, 20);
        return out;
    }

    private static byte[] uint256(long v) {
        if (v < 0) {
            throw new IllegalArgumentException("số âm");
        }
        return ByteBuffer.allocate(32).position(24).putLong(v).array();
    }
}
