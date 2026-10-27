package vn.werwa.distribution.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lá và cây Merkle khớp hợp đồng (SeriesLeaves, MerkleProof của OpenZeppelin). Véc-tơ tính độc lập bằng keccak Python. */
class AllocationMerkleTest {

    static final String CONTROLLER = "0x1111111111111111111111111111111111111111";

    @Test
    void uuidGoesIntoTheLow16BytesOfBytes32() {
        byte[] b = AllocationMerkle.uuidToBytes32(UUID.fromString("01a15bc1-0000-7000-8000-000000000001"));
        assertThat(HexFormat.of().formatHex(b))
                .isEqualTo("0000000000000000000000000000000001a15bc1000070008000000000000001");
    }

    @Test
    void leavesAndRootMatchIndependentVectors() {
        byte[] l1 = AllocationMerkle.leaf(CONTROLLER, UUID.fromString("01a15bc1-0000-7000-8000-000000000001"),
                "0x2222222222222222222222222222222222222222", 100);
        byte[] l2 = AllocationMerkle.leaf(CONTROLLER, UUID.fromString("01a15bc1-0000-7000-8000-000000000002"),
                "0x3333333333333333333333333333333333333333", 250);
        byte[] l3 = AllocationMerkle.leaf(CONTROLLER, UUID.fromString("01a15bc1-0000-7000-8000-000000000003"),
                "0x2222222222222222222222222222222222222222", 5);
        assertThat(AllocationMerkle.hex(l1)).isEqualTo("0x182128813cc0c29f3d7070777221840d2dfaff742f3b6b3b2a2d0606869be3a7");
        assertThat(AllocationMerkle.hex(l2)).isEqualTo("0x9735c01c66b6bbdaecd2d3fcd78b90ff116c481e7704c24e6d4b526a35bba982");
        assertThat(AllocationMerkle.hex(l3)).isEqualTo("0x79c5c23015e2908c805f6eef85ad985bd179140647afe94fab33a0636ed2ceed");
        assertThat(AllocationMerkle.hex(AllocationMerkle.root(List.of(l1, l2, l3))))
                .isEqualTo("0x2bd5124af5bd85cf5fb1064ba54254c683259b4f15cee04d5c822462a325ce45");
    }

    @Test
    void everyLeafHasAProofForTreesOfAnySize() {
        for (int n = 1; n <= 9; n++) {
            List<byte[]> leaves = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                leaves.add(AllocationMerkle.leaf(CONTROLLER, UUID.randomUUID(), CONTROLLER, i + 1));
            }
            byte[] root = AllocationMerkle.root(leaves);
            for (int i = 0; i < n; i++) {
                assertThat(AllocationMerkle.verify(AllocationMerkle.proof(leaves, i), root, leaves.get(i)))
                        .as("n=%d i=%d", n, i).isTrue();
            }
            byte[] stranger = AllocationMerkle.leaf(CONTROLLER, UUID.randomUUID(), CONTROLLER, 1);
            assertThat(AllocationMerkle.verify(AllocationMerkle.proof(leaves, 0), root, stranger)).isFalse();
        }
    }
}
