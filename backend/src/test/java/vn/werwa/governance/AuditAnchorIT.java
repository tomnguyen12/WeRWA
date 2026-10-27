package vn.werwa.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.utils.Numeric;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.AuditVerification;
import vn.werwa.governance.audit.AuditAnchorService;
import vn.werwa.onchain.ChainIntegrationTest;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.contracts.AuditAnchor;
import vn.werwa.onchain.signing.TransactionSigner;

/** Neo nhật ký lên chuỗi (US-4.08-AC3; Q21) với Besu thật và AuditAnchor đã triển khai. */
@Import(AuditAnchorIT.Signer.class)
class AuditAnchorIT extends ChainIntegrationTest {

    @Autowired
    AuditLog audit;

    @Autowired
    AuditAnchorService anchors;

    @Autowired
    AuditVerification verification;

    @Autowired
    ContractCalls calls;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("US-4.08-AC3 neo cuối ngày: mã băm cuối ngày có trên chuỗi và khớp nhật ký")
    void US_4_08_AC3_dailyAnchorIsOnChainAndMatchesTheLog() throws Exception {
        tx.executeWithoutResult(s -> {
            audit.record("TEST_ANCHOR_1", "test/anchor", Map.of("v", 1));
            audit.record("TEST_ANCHOR_2", "test/anchor", Map.of("v", 2));
        });
        LocalDate day = LocalDate.now(AuditAnchorService.BUSINESS_ZONE).plusDays(1); // gồm mọi dòng đến giờ
        tx.executeWithoutResult(s -> anchors.anchor(day));

        await().atMost(Duration.ofSeconds(60)).until(() -> "ANCHORED".equals(jdbc.queryForObject(
                "SELECT status FROM governance.audit_anchor WHERE business_date = ?", String.class, day)));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT last_seq, last_entry_hash FROM governance.audit_anchor WHERE business_date = ?", day);
        Map<String, Object> last = jdbc.queryForMap(
                "SELECT seq, entry_hash FROM governance.audit_log ORDER BY seq DESC LIMIT 1");
        assertThat(row.get("last_seq")).isEqualTo(last.get("seq"));
        assertThat(row.get("last_entry_hash")).isEqualTo(last.get("entry_hash"));

        AuditAnchor.Anchor onChain = calls.load(AuditAnchor.class, calls.address("auditAnchor"))
                .anchorOf(new BigInteger(day.format(DateTimeFormatter.BASIC_ISO_DATE))).send();
        assertThat(onChain.lastSeq.longValue()).isEqualTo(((Number) last.get("seq")).longValue());
        assertThat(Numeric.toHexString(onChain.lastEntryHash)).isEqualTo(last.get("entry_hash"));
        assertThat(verification.verify().intact()).isTrue();
    }

    @Test
    void anchoringTheSameDayTwiceDoesNothing() {
        LocalDate day = LocalDate.now(AuditAnchorService.BUSINESS_ZONE).plusDays(2);
        tx.executeWithoutResult(s -> anchors.anchor(day));
        tx.executeWithoutResult(s -> anchors.anchor(day));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM onchain.chain_tx WHERE subject_ref = ?", Integer.class, "audit-anchor/" + day))
                .isEqualTo(1);
    }

    @TestConfiguration
    static class Signer {
        @Bean
        @Primary
        TransactionSigner testSigner() {
            return vn.werwa.onchain.TestSigners.operator();
        }
    }
}
