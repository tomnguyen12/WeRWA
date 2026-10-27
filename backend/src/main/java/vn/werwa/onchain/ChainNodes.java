package vn.werwa.onchain;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigInteger;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;

/**
 * Kết nối JSON-RPC chuẩn EVM (ADR-01: không dùng tính năng riêng của Besu). Đọc chỉ qua O1-a rồi O1-b (ADR-12); gửi raw
 * tx qua V1 rồi V2 (ADR-02). Ứng dụng không bao giờ đọc từ validator.
 */
public class ChainNodes {

    private static final Logger log = LoggerFactory.getLogger(ChainNodes.class);

    private final long chainId;
    private final List<Node> readers;
    private final List<Node> submitters;

    public ChainNodes(long chainId, List<URI> readUrls, List<URI> submitUrls, okhttp3.OkHttpClient http) {
        this.chainId = chainId;
        this.readers = readUrls.stream().map(u -> node(u, http)).toList();
        this.submitters = submitUrls.stream().map(u -> node(u, http)).toList();
    }

    public long chainId() {
        return chainId;
    }

    @FunctionalInterface
    public interface Call<T> {
        T call(Web3j web3j) throws IOException;
    }

    /** Đọc qua node chỉ đọc đầu tiên trả lời được. */
    public <T> T read(String what, Call<T> call) {
        return firstAvailable(readers, what, call);
    }

    /**
     * Gửi raw tx qua validator đầu tiên trả lời được. Node trả "đã biết giao dịch" thì coi là đã gửi, vì raw tx giống hệt
     * (gửi lại an toàn, Q14).
     */
    public void sendRaw(String rawHex) {
        firstAvailable(submitters, "eth_sendRawTransaction", web3j -> {
            EthSendTransaction r = web3j.ethSendRawTransaction(rawHex).send();
            if (r.hasError() && !isAlreadyKnown(r.getError().getMessage())) {
                throw new RejectedByNode(r.getError().getMessage());
            }
            return r.getTransactionHash();
        });
    }

    static boolean isAlreadyKnown(String message) {
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("already known") || m.contains("known transaction") || m.contains("already imported");
    }

    private <T> T firstAvailable(List<Node> nodes, String what, Call<T> call) {
        IOException last = null;
        for (Node node : nodes) {
            try {
                return call.call(node.web3j());
            } catch (IOException e) {
                log.warn("{} lỗi ở {}: {}", what, node.url(), e.toString());
                last = e;
            }
        }
        throw new ChainUnavailable(what + ": không node nào trả lời", last);
    }

    private static Node node(URI url, okhttp3.OkHttpClient http) {
        HttpService service = new HttpService(url.toString(), http);
        return new Node(url, service, Web3j.build(service));
    }

    /**
     * Log của mọi hợp đồng trong khoảng khối, qua O1. Không lọc theo địa chỉ ở node: hợp đồng series mới triển khai trong
     * cùng khoảng vẫn được đọc; lọc theo registry ở indexer.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Log> logs(long fromBlock, long toBlock) {
        java.util.Map<String, Object> filter = java.util.Map.of(
                "fromBlock", Numeric.encodeQuantity(BigInteger.valueOf(fromBlock)),
                "toBlock", Numeric.encodeQuantity(BigInteger.valueOf(toBlock)));
        IOException last = null;
        for (Node node : readers) {
            try {
                EthLog response = new Request<>("eth_getLogs", List.of(filter), node.service(), EthLog.class).send();
                if (response.hasError()) {
                    throw new IOException(response.getError().getMessage());
                }
                List<Log> out = new java.util.ArrayList<>();
                for (EthLog.LogResult r : (List<EthLog.LogResult>) (List) response.getLogs()) {
                    out.add((Log) r.get());
                }
                return out;
            } catch (IOException e) {
                log.warn("eth_getLogs lỗi ở {}: {}", node.url(), e.toString());
                last = e;
            }
        }
        throw new ChainUnavailable("eth_getLogs: không node nào trả lời", last);
    }

    record Node(URI url, HttpService service, Web3j web3j) {}

    /** Mọi node trong danh sách đều không trả lời. */
    public static class ChainUnavailable extends RuntimeException {
        ChainUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Node nhận được nhưng từ chối raw tx (không phải lỗi mạng): không thử node khác. */
    public static class RejectedByNode extends RuntimeException {
        RejectedByNode(String message) {
            super(message);
        }
    }
}
