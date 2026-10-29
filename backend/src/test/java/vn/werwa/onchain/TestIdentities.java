package vn.werwa.onchain;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.utils.Numeric;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.onchain.api.ChainTransactions;
import vn.werwa.onchain.api.ContractCalls;
import vn.werwa.onchain.api.SignerKey;
import vn.werwa.onchain.api.TxRequest;
import vn.werwa.onchain.api.contracts.IdentityRegistry;

/**
 * Định danh ONCHAINID thử cho ví nhà đầu tư (Q136). Chỉ dùng trong kiểm thử: luồng thật (IdFactory, claim ký trong HSM)
 * thuộc B5b. Định danh do một khóa quản trị thử triển khai và giữ; claim KYC_INSTITUTION và INVESTOR_CLASS ký bằng
 * {@link ChainIntegrationTest#CLAIM_SIGNER}; đăng ký vào sổ định danh qua ứng dụng bằng khóa vận hành (agent).
 */
public final class TestIdentities {

    static final BigInteger KYC_TOPIC = Numeric.toBigInt(Hash.sha3("WERWA.CLAIM.KYC_INSTITUTION".getBytes(StandardCharsets.UTF_8)));
    static final BigInteger CLASS_TOPIC = Numeric.toBigInt(Hash.sha3("WERWA.CLAIM.INVESTOR_CLASS".getBytes(StandardCharsets.UTF_8)));
    static final int COUNTRY_SG = 702;
    private static final BigInteger GAS = BigInteger.valueOf(8_000_000);

    private static Credentials admin;
    private static String identityBytecode;

    private TestIdentities() {}

    private static synchronized Credentials admin() {
        if (admin == null) {
            try {
                admin = Credentials.create(Keys.createEcKeyPair());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return admin;
    }

    /** Mã bytecode của Identity (ONCHAINID) từ kết quả biên dịch forge của bước triển khai nền tảng. */
    @SuppressWarnings("unchecked")
    private static synchronized String bytecode() {
        if (identityBytecode == null) {
            Path out = Path.of("..", "contracts", "out", "Identity.sol", "Identity.json");
            try {
                Map<String, Object> json = JsonMapper.builder().build().readValue(Files.readString(out), Map.class);
                identityBytecode = (String) ((Map<String, Object>) json.get("bytecode")).get("object");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("chưa có " + out + " (forge build)", e);
            }
        }
        return identityBytecode;
    }

    /**
     * Ví {@code wallet} có định danh đã đăng ký, claim KYC còn hạn và loại nhà đầu tư {@code classCode}. Chờ tới khi
     * {@code isVerified(wallet)} đúng.
     */
    public static String onboard(ChainTransactions chain, ContractCalls calls, TransactionTemplate tx, String wallet,
            int classCode) {
        Web3j web3j = Web3j.build(new HttpService(ChainIntegrationTest.rpcUrl()));
        try {
            RawTransactionManager tm = new RawTransactionManager(web3j, admin(), ChainIntegrationTest.CHAIN_ID);
            String ctor = FunctionEncoder.encodeConstructor(List.of(new Address(admin().getAddress()), new Bool(false)));
            String identity = receipt(web3j, tm.sendTransaction(BigInteger.ZERO, GAS, null, bytecode() + ctor,
                    BigInteger.ZERO, true)).getContractAddress();
            long until = Instant.now().plus(Duration.ofDays(365)).getEpochSecond();
            addClaim(web3j, tm, identity, KYC_TOPIC, Numeric.hexStringToByteArray(
                    FunctionEncoder.encodeConstructor(List.of(new Uint256(until)))));
            addClaim(web3j, tm, identity, CLASS_TOPIC, Numeric.hexStringToByteArray(
                    FunctionEncoder.encodeConstructor(List.of(new Uint256(classCode), new Uint256(until)))));
            String registry = ChainIntegrationTest.contract("identityRegistry");
            String data = FunctionEncoder.encode(new Function("registerIdentity", List.of(new Address(wallet),
                    new Address(identity), new Uint16(COUNTRY_SG)), List.of()));
            tx.executeWithoutResult(s -> chain.submit(new TxRequest("TEST_IDENTITY", "identity/" + wallet,
                    SignerKey.HOLCO_OPERATOR, registry, data)));
            IdentityRegistry ir = calls.load(IdentityRegistry.class, registry);
            long deadline = System.currentTimeMillis() + 60_000;
            while (!Boolean.TRUE.equals(ir.isVerified(wallet).send())) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("ví " + wallet + " chưa xác minh sau 60 giây");
                }
                Thread.sleep(500);
            }
            return identity;
        } catch (Exception e) {
            throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
        } finally {
            web3j.shutdown();
        }
    }

    private static void addClaim(Web3j web3j, RawTransactionManager tm, String identity, BigInteger topic, byte[] data)
            throws Exception {
        String issuer = ChainIntegrationTest.contract("holcoClaimIssuer");
        byte[] dataHash = Hash.sha3(Numeric.hexStringToByteArray(FunctionEncoder.encodeConstructor(List.of(
                new Address(identity), new Uint256(topic), new DynamicBytes(data)))));
        Sign.SignatureData sig = Sign.signPrefixedMessage(dataHash, ChainIntegrationTest.CLAIM_SIGNER.getEcKeyPair());
        byte[] signature = new byte[65];
        System.arraycopy(sig.getR(), 0, signature, 0, 32);
        System.arraycopy(sig.getS(), 0, signature, 32, 32);
        signature[64] = sig.getV()[0];
        String call = FunctionEncoder.encode(new Function("addClaim", List.of(new Uint256(topic), new Uint256(1),
                new Address(issuer), new DynamicBytes(signature), new DynamicBytes(data), new Utf8String("")), List.of()));
        TransactionReceipt r = receipt(web3j, tm.sendTransaction(BigInteger.ZERO, GAS, identity, call, BigInteger.ZERO));
        if (!r.isStatusOK()) {
            throw new IllegalStateException("addClaim chủ đề " + topic + " thất bại: " + r.getRevertReason());
        }
    }

    private static TransactionReceipt receipt(Web3j web3j, EthSendTransaction sent) throws Exception {
        if (sent.hasError()) {
            throw new IllegalStateException(sent.getError().getMessage());
        }
        for (int i = 0; i < 120; i++) {
            var r = web3j.ethGetTransactionReceipt(sent.getTransactionHash()).send().getTransactionReceipt();
            if (r.isPresent()) {
                return r.get();
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("không có receipt " + sent.getTransactionHash());
    }
}
