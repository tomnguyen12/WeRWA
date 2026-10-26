package vn.werwa.onchain;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import vn.werwa.onchain.AbiCatalog.Revert;
import vn.werwa.onchain.ChainNodes.ChainUnavailable;
import vn.werwa.onchain.api.ChainCallRejected;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.signing.SignerUnavailable;
import vn.werwa.onchain.signing.TransactionSigner;
import vn.werwa.platform.id.Ids;
import vn.werwa.platform.web.ApiException;

/**
 * Lập, ký và ghi giao dịch chuỗi (B3; Q14, Q16). Trong giao dịch DB của bên gọi:
 *
 * <ol>
 *   <li>Chạy thử {@code eth_call} và ước lượng gas trên O1; hợp đồng từ chối thì ném {@link ChainCallRejected}.
 *   <li>Giữ khóa tư vấn theo địa chỉ gửi, chọn nonce: lớn hơn nonce lớn nhất đã ghi và không nhỏ hơn số giao dịch trên chuỗi.
 *   <li>Nhờ bộ ký ký (không gửi), tính hash, ghi {@code chain_tx} ở BUILT.
 * </ol>
 *
 * Commit xong mới gửi ({@link TxSender}); bên gọi rollback thì giao dịch chuỗi không bao giờ được gửi.
 */
public class ChainTxService implements ChainTransactions {

    private final ChainNodes nodes;
    private final TransactionSigner signer;
    private final AbiCatalog abi;
    private final JdbcTemplate jdbc;
    private final TxSender sender;
    private final OnchainProperties props;
    private final Clock clock;

    public ChainTxService(
            ChainNodes nodes,
            TransactionSigner signer,
            AbiCatalog abi,
            JdbcTemplate jdbc,
            TxSender sender,
            OnchainProperties props,
            Clock clock) {
        this.nodes = nodes;
        this.signer = signer;
        this.abi = abi;
        this.jdbc = jdbc;
        this.sender = sender;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public UUID submit(TxRequest request) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("gửi giao dịch chuỗi phải nằm trong giao dịch DB: " + request.purpose());
        }
        try {
            return doSubmit(request);
        } catch (SignerUnavailable e) {
            throw new ApiException(OnchainError.CHAIN_SIGNER_UNAVAILABLE, e.getMessage());
        } catch (ChainUnavailable e) {
            throw new ApiException(OnchainError.CHAIN_UNAVAILABLE, e.getMessage());
        }
    }

    private UUID doSubmit(TxRequest request) {
        String from = signer.address(request.signer());
        String to = ContractRegistry.normalize(request.to());
        Transaction call = Transaction.createEthCallTransaction(from, to, request.data());

        EthCall result = nodes.read("eth_call", w -> w.ethCall(call, DefaultBlockParameterName.LATEST).send());
        if (result.hasError() || result.isReverted()) {
            throw rejected(result.getError(), result.getRevertReason());
        }
        EthEstimateGas estimate = nodes.read("eth_estimateGas", w -> w.ethEstimateGas(call).send());
        if (estimate.hasError()) {
            throw rejected(estimate.getError(), null);
        }
        BigInteger gas = estimate.getAmountUsed()
                .multiply(BigInteger.valueOf(100L + props.gasMarginPercent()))
                .divide(BigInteger.valueOf(100));

        // Khóa tư vấn theo (chain, địa chỉ gửi) tới hết giao dịch DB: hai lệnh song song không lấy trùng nonce.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class,
                nodes.chainId() + ":" + from);
        Long lastNonce = jdbc.queryForObject(
                "SELECT max(nonce) FROM onchain.chain_tx WHERE chain_id = ? AND from_address = ?",
                Long.class, nodes.chainId(), from);
        BigInteger onChain = nodes.read("eth_getTransactionCount",
                w -> w.ethGetTransactionCount(from, DefaultBlockParameterName.LATEST).send().getTransactionCount());
        BigInteger nonce = lastNonce == null ? onChain : onChain.max(BigInteger.valueOf(lastNonce + 1));

        RawTransaction tx = RawTransaction.createTransaction(nonce, BigInteger.ZERO, gas, to, BigInteger.ZERO, request.data());
        String raw = signer.sign(request.signer(), tx, nodes.chainId());
        String hash = Hash.sha3(raw);

        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO onchain.chain_tx
                    (id, chain_id, from_address, nonce, purpose, subject_ref, to_address, raw_tx, tx_hash, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'BUILT', ?)
                """, id, nodes.chainId(), from, nonce.longValueExact(), request.purpose(), request.subjectRef(), to, raw,
                hash, Timestamp.from(clock.instant()));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sender.sendQuietly(id);
            }
        });
        return id;
    }

    private ChainCallRejected rejected(Response.Error error, String revertReason) {
        String data = error == null ? null : dataOf(error);
        if (data != null && data.startsWith("0x") && data.length() >= 10) {
            Revert revert = abi.decodeRevert(data);
            return new ChainCallRejected(revert.name(), revert.args().isEmpty() ? null : revert.args().toString());
        }
        String message = revertReason != null ? revertReason : error == null ? "không rõ" : error.getMessage();
        return new ChainCallRejected("UNKNOWN", message);
    }

    private static String dataOf(Response.Error error) {
        Object data = error.getData();
        if (data instanceof Map<?, ?> m && m.get("data") != null) {
            return String.valueOf(m.get("data"));
        }
        return data == null ? null : String.valueOf(data).replace("\"", "");
    }
}
