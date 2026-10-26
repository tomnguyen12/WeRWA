package vn.werwa.onchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.web3j.crypto.Hash;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.onchain.api.ChainCallRejected;

/** ABI đọc từ {@code api/abi}: giải mã custom error (Q16) và tìm sự kiện theo topic (Q17). */
class AbiCatalogTest {

    static AbiCatalog load() throws IOException {
        return AbiCatalog.load(
                new PathMatchingResourcePatternResolver().getResources("classpath:contract/abi/*.json"),
                JsonMapper.builder().build());
    }

    @Test
    void decodesCustomErrorsStringErrorsAndUnknownData() throws IOException {
        AbiCatalog abi = load();
        String alreadyAnchored = Hash.sha3String("AlreadyAnchored(uint32)").substring(0, 10);
        AbiCatalog.Revert revert = abi.decodeRevert(alreadyAnchored + "0".repeat(56) + "01355c38");
        assertThat(revert.name()).isEqualTo("AlreadyAnchored");
        assertThat(revert.args()).containsEntry("businessDate", String.valueOf(0x01355c38));

        String unauthorized = Hash.sha3String("Unauthorized()").substring(0, 10);
        assertThat(abi.decodeRevert(unauthorized).name()).isEqualTo("Unauthorized");

        String errorString = "0x08c379a0"
                + "0000000000000000000000000000000000000000000000000000000000000020"
                + "0000000000000000000000000000000000000000000000000000000000000003"
                + "6c6f690000000000000000000000000000000000000000000000000000000000";
        assertThat(abi.decodeRevert(errorString).args()).containsEntry("reason", "loi");
        assertThat(abi.decodeRevert("0xdeadbeef").name()).isEqualTo("UNKNOWN");
    }

    @Test
    void findsEventsByTopic() throws IOException {
        AbiCatalog abi = load();
        String topic = Hash.sha3String("AuditAnchored(uint32,uint64,bytes32,address)");
        assertThat(abi.event("AuditAnchor", topic)).hasValueSatisfying(e -> assertThat(e.name()).isEqualTo("AuditAnchored"));
        String created = Hash.sha3String("SeriesCreated(bytes32,address,address,address,address,address,address)");
        assertThat(abi.event("SeriesFactory", created)).isPresent();
    }

    @Test
    void contractErrorNameBecomesAnUpperSnakeCode() {
        assertThat(new ChainCallRejected("AlreadyAnchored", null).error().code()).isEqualTo("CHAIN_ALREADY_ANCHORED");
        assertThat(new ChainCallRejected("InvalidAllocationProof", null).error().code())
                .isEqualTo("CHAIN_INVALID_ALLOCATION_PROOF");
        assertThat(new ChainCallRejected("UNKNOWN", null).error().code()).isEqualTo("CHAIN_UNKNOWN");
    }
}
