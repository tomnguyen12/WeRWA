package vn.werwa.onchain;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.onchain.signing.TransactionSigner;
import vn.werwa.onchain.signing.UnavailableSigner;
import vn.werwa.onchain.signing.WeHsmSigner;
import vn.werwa.platform.config.WerwaProperties;
import vn.werwa.platform.events.Events;

/** Bean của mô-đun Onchain (B3). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OnchainProperties.class)
class OnchainConfig {

    @Bean
    ChainNodes chainNodes(WerwaProperties werwa, OnchainProperties props) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(props.rpcTimeout())
                .readTimeout(props.rpcTimeout())
                .callTimeout(props.rpcTimeout())
                .build();
        return new ChainNodes(werwa.chain().id(), werwa.chain().readUrls(), werwa.chain().submitUrls(), http);
    }

    @Bean
    AbiCatalog abiCatalog(JsonMapper json) throws IOException {
        return AbiCatalog.load(new PathMatchingResourcePatternResolver().getResources("classpath:contract/abi/*.json"), json);
    }

    @Bean
    ContractRegistry contractRegistry(JdbcTemplate jdbc, ChainNodes nodes, WerwaProperties werwa, Clock clock) {
        ContractRegistry registry = new ContractRegistry(jdbc, nodes.chainId(), clock);
        registry.seed(werwa.contracts());
        return registry;
    }

    @Bean
    Web3jContractCalls contractCalls(ChainNodes nodes, ContractRegistry registry) {
        return new Web3jContractCalls(nodes, registry);
    }

    /** Chế độ ký pkcs11 (I12): ký qua WeHSM; địa chỉ khóa vận hành đọc từ vai OPERATOR của AuditAnchor. */
    @Bean
    @ConditionalOnProperty(name = "werwa.chain.signing-mode", havingValue = "pkcs11")
    TransactionSigner weHsmSigner(
            WerwaProperties werwa, OnchainProperties props, JsonMapper json, ChainNodes nodes, ContractRegistry registry) {
        return new WeHsmSigner(werwa.signer().url(), werwa.signer().token(), json, props.rpcTimeout(),
                () -> operatorOf(nodes, registry));
    }

    @Bean
    @ConditionalOnMissingBean(TransactionSigner.class)
    TransactionSigner unavailableSigner() {
        return new UnavailableSigner();
    }

    @Bean
    TxSender txSender(ChainNodes nodes, JdbcTemplate jdbc, OnchainProperties props, Clock clock) {
        return new TxSender(nodes, jdbc, props, clock);
    }

    @Bean
    ChainTxService chainTransactions(
            ChainNodes nodes,
            TransactionSigner signer,
            AbiCatalog abi,
            JdbcTemplate jdbc,
            TxSender sender,
            OnchainProperties props,
            Clock clock) {
        return new ChainTxService(nodes, signer, abi, jdbc, sender, props, clock);
    }

    @Bean
    Indexer indexer(
            ChainNodes nodes,
            ContractRegistry registry,
            AbiCatalog abi,
            JdbcTemplate jdbc,
            TransactionTemplate tx,
            Events events,
            JsonMapper json,
            OnchainProperties props,
            WerwaProperties werwa,
            Clock clock) {
        return new Indexer(nodes, registry, abi, jdbc, tx, events, json, props, werwa.chain().deployedAtBlock(), clock);
    }

    @Bean
    TxTracker txTracker(
            ChainNodes nodes, Indexer indexer, JdbcTemplate jdbc, TransactionTemplate tx, Events events, Clock clock) {
        return new TxTracker(nodes, indexer, jdbc, tx, events, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "werwa.onchain.workers-enabled", havingValue = "true", matchIfMissing = true)
    Workers onchainWorkers(Indexer indexer, TxTracker tracker, TxSender sender) {
        return new Workers(indexer, tracker, sender);
    }

    private static String operatorOf(ChainNodes nodes, ContractRegistry registry) {
        String auditAnchor = registry.addressOf("auditAnchor")
                .orElseThrow(() -> new IllegalStateException("thiếu werwa.contracts.auditAnchor"));
        Function operator = new Function("operator", List.of(), List.of(new TypeReference<Address>() {}));
        String result = nodes.read("eth_call", w -> w.ethCall(
                        Transaction.createEthCallTransaction(null, auditAnchor, FunctionEncoder.encode(operator)),
                        DefaultBlockParameterName.LATEST).send().getValue());
        @SuppressWarnings("rawtypes")
        List<Type> decoded = FunctionReturnDecoder.decode(result, operator.getOutputParameters());
        return decoded.getFirst().getValue().toString();
    }

    /** Việc định kỳ: indexer, chốt kết quả giao dịch, gửi lại giao dịch chưa lên khối. */
    static class Workers {

        private final Indexer indexer;
        private final TxTracker tracker;
        private final TxSender sender;

        Workers(Indexer indexer, TxTracker tracker, TxSender sender) {
            this.indexer = indexer;
            this.tracker = tracker;
            this.sender = sender;
        }

        @Scheduled(fixedDelayString = "${werwa.onchain.poll-interval:PT1S}")
        void tick() {
            try {
                indexer.poll();
                tracker.track();
                sender.resendPending();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(Workers.class).warn("Lượt Onchain lỗi: {}", e.toString());
            }
        }
    }
}
