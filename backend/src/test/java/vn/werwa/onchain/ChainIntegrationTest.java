package vn.werwa.onchain;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.utils.Numeric;
import tools.jackson.databind.json.JsonMapper;
import vn.werwa.IntegrationTest;

/**
 * Lớp nền kiểm thử có chuỗi thật (D9): Besu một node QBFT (cùng ảnh devnet, genesis từ mẫu của infra), triển khai nền
 * tảng bằng {@code DeployPlatform} với Foundry như {@code infra/platform/deploy.sh}. Chạy một lần cho mọi lớp kiểm thử.
 *
 * <p>Khóa trong lớp này sinh ngẫu nhiên lúc chạy, chỉ sống trong kiểm thử (Q15). Danh sách đọc và gửi có một địa chỉ
 * chết đứng trước, để mọi lời gọi đi qua đường chuyển sang node kế tiếp (O1-a sang O1-b, V1 sang V2).
 */
public abstract class ChainIntegrationTest extends IntegrationTest {

    public static final long CHAIN_ID = 1337;
    static final String DEAD_READER = "http://127.0.0.1:1";
    static final String DEAD_VALIDATOR = "http://127.0.0.1:2";

    public static final Credentials OPERATOR = Credentials.create(newKey());
    static final Credentials DEPLOYER = Credentials.create(newKey());
    static final ECKeyPair VALIDATOR = newKey();

    @SuppressWarnings("resource")
    public static final GenericContainer<?> BESU = new GenericContainer<>("hyperledger/besu:26.10.0")
            .withExposedPorts(8545)
            .withCopyToContainer(Transferable.of(genesis()), "/config/genesis.json")
            .withCopyToContainer(Transferable.of(Numeric.toHexStringWithPrefixZeroPadded(VALIDATOR.getPrivateKey(), 64)),
                    "/config/key")
            .withCommand(
                    "--genesis-file=/config/genesis.json",
                    "--node-private-key-file=/config/key",
                    "--data-path=/tmp/besu",
                    "--rpc-http-enabled",
                    "--rpc-http-host=0.0.0.0",
                    "--rpc-http-port=8545",
                    "--rpc-http-api=ETH,NET,WEB3,QBFT",
                    "--host-allowlist=*",
                    "--min-gas-price=0",
                    "--discovery-enabled=false")
            .waitingFor(Wait.forLogMessage(".*Ethereum main loop is up.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    static final Map<String, String> CONTRACTS;
    static final long DEPLOYED_AT_BLOCK;

    /** Tổ chức thử (orgs.json) của Safe tổ chức (Q107). */
    public static final Map<String, String> SAFE_ORGS = Map.of(
            "ubnd", "01a15bc1-f400-7c28-a7a8-d5d4cc19328b",
            "vsdc", "01a15bc1-f403-7f0b-8fb9-a75cdc4b5b00",
            "bidv", "01a15bc1-f404-72f3-86c3-4f3f7c24f440",
            "holco", "01a15bc1-f409-7589-aef1-774ceddccf3f");

    /** Thư mục kết quả của {@code safe.sh}: safes.json và khóa người ký thử. */
    static final Path SAFE_OUT;

    /** Tên Safe ({@code ubnd}, {@code governance3of3}...) sang địa chỉ chữ thường. */
    public static final Map<String, String> SAFES = new java.util.HashMap<>();

    static {
        BESU.start();
        awaitBlocks();
        // Q113, Q120: Safe 1.4.1 thật dựng bằng chính infra/safe/safe.sh, trước khi triển khai nền tảng với Safe đó
        SAFE_OUT = deploySafes();
        Deployment d = deployPlatform();
        CONTRACTS = d.contracts();
        DEPLOYED_AT_BLOCK = d.lastBlock();
    }

    /** Khóa người ký thử thứ {@code i} (1–3) của Safe tổ chức {@code org}, do safe.sh sinh. */
    public static Credentials safeSigner(String org, int i) {
        try {
            return Credentials.create(Files.readString(SAFE_OUT.resolve("safe/signers/" + org + "-" + i + "/key")).trim());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Người ký 1, 2 của mỗi Safe tổ chức là người ký được ủy quyền của tổ chức đó (B5a); người ký 3 thì chưa. */
    public static void authorizeSafeSigners(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        for (Map.Entry<String, String> org : SAFE_ORGS.entrySet()) {
            for (int i = 1; i <= 2; i++) {
                jdbc.update("""
                        INSERT INTO onboarding.authorized_signer (id, organization_id, user_id, signing_address, scopes,
                            valid_from, authorization_doc_id, created_at)
                        VALUES (?, ?::uuid, ?, ?, ARRAY['ALL'], current_date - 1, ?, now())
                        ON CONFLICT (signing_address) DO NOTHING
                        """, java.util.UUID.randomUUID(), org.getValue(), java.util.UUID.randomUUID(),
                        safeSigner(org.getKey(), i).getAddress().toLowerCase(java.util.Locale.ROOT), java.util.UUID.randomUUID());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Path deploySafes() {
        try {
            Path out = Files.createTempDirectory("werwa-safe-");
            Path bootstrap = out.resolve("accounts/bootstrap/key");
            Files.createDirectories(bootstrap.getParent());
            Files.writeString(bootstrap, Numeric.toHexStringWithPrefixZeroPadded(DEPLOYER.getEcKeyPair().getPrivateKey(), 64));
            Path repo = Path.of("..").toAbsolutePath().normalize();
            run(repo.toFile(), Map.of("DEVNET_OUT", out.toString(), "O1A_RPC", rpcUrl(), "V1_RPC", rpcUrl()),
                    "bash", "infra/safe/safe.sh", "up");
            Map<String, Object> json = JsonMapper.builder().build().readValue(Files.readString(out.resolve("safe/safes.json")),
                    Map.class);
            ((Map<String, Map<String, Object>>) json.get("safes")).forEach((name, v) ->
                    SAFES.put(name, ((String) v.get("address")).toLowerCase(java.util.Locale.ROOT)));
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String rpcUrl() {
        return "http://" + BESU.getHost() + ":" + BESU.getMappedPort(8545);
    }

    @DynamicPropertySource
    static void chainProperties(DynamicPropertyRegistry registry) {
        // Đọc và gửi cùng một node thật nhưng khác dạng địa chỉ, vì cấu hình cấm trùng (chỉ đọc qua O1, ADR-12).
        String read = "http://127.0.0.1:" + BESU.getMappedPort(8545);
        String submit = "http://localhost:" + BESU.getMappedPort(8545);
        registry.add("werwa.chain.id", () -> CHAIN_ID);
        registry.add("werwa.chain.read-urls", () -> DEAD_READER + "," + read);
        registry.add("werwa.chain.submit-urls", () -> DEAD_VALIDATOR + "," + submit);
        registry.add("werwa.chain.deployed-at-block", () -> DEPLOYED_AT_BLOCK);
        registry.add("werwa.chain.signing-mode", () -> "devnet");
        registry.add("werwa.onchain.workers-enabled", () -> "true");
        registry.add("werwa.onchain.poll-interval", () -> "PT0.5S");
        registry.add("werwa.onchain.resend-after", () -> "PT3S");
        registry.add("werwa.onchain.rpc-timeout", () -> "PT2S");
        CONTRACTS.forEach((name, address) -> registry.add("werwa.contracts." + name, () -> address));
        SAFES.forEach((name, address) -> registry.add("werwa.safes." + name, () -> address));
        SAFE_ORGS.forEach((name, org) -> registry.add("werwa.signing.safe-organizations." + name, () -> org));
    }

    private static ECKeyPair newKey() {
        try {
            return Keys.createEcKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Genesis QBFT một validator: mẫu của infra, extraData = RLP[vanity 32 byte, [validator], [], round 0, []]. */
    private static String genesis() {
        try (var in = ChainIntegrationTest.class.getResourceAsStream("/chain/genesis.template.json")) {
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            byte[] validator = Numeric.hexStringToByteArray(Keys.getAddress(VALIDATOR));
            String extra = Numeric.toHexString(RlpEncoder.encode(new RlpList(
                    RlpString.create(new byte[32]),
                    new RlpList(RlpString.create(validator)),
                    new RlpList(),
                    RlpString.create(new byte[0]),
                    new RlpList())));
            return template
                    .replace("\"chainId\": 0", "\"chainId\": " + CHAIN_ID)
                    .replace("\"extraData\": \"\"", "\"extraData\": \"" + extra + "\"");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitBlocks() {
        Web3j web3j = Web3j.build(new HttpService(rpcUrl()));
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                if (web3j.ethBlockNumber().send().getBlockNumber().compareTo(BigInteger.ONE) >= 0) {
                    return;
                }
                Thread.sleep(500);
            } catch (Exception e) {
                // node chưa sẵn sàng
            }
        }
        throw new IllegalStateException("Besu không tạo khối");
    }

    record Deployment(Map<String, String> contracts, long lastBlock) {}

    /** Như {@code infra/platform/deploy.sh} chế độ devnet: forge script, rồi ghép tên trường bằng {@code read-run.sh}. */
    @SuppressWarnings("unchecked")
    private static Deployment deployPlatform() {
        Path repo = Path.of("..").toAbsolutePath().normalize();
        File contracts = repo.resolve("contracts").toFile();
        Map<String, String> env = Map.of(
                "WERWA_GOVERNANCE_3OF3", SAFES.get("governance3of3"),
                "WERWA_CANCELLERS", SAFES.get("ubnd") + "," + SAFES.get("vsdc") + "," + SAFES.get("holco"),
                "WERWA_HOLCO_SAFE", SAFES.get("holco"),
                "WERWA_HOLCO_CLAIM_SIGNER", "0x00000000000000000000000000000000000000C2",
                "WERWA_HOLCO_OPERATOR", OPERATOR.getAddress(),
                "WERWA_TIMELOCK_DELAY", "3600");
        run(contracts, env, "forge", "script", "script/DeployPlatform.s.sol",
                "--rpc-url", rpcUrl(),
                "--private-key", Numeric.toHexStringWithPrefixZeroPadded(DEPLOYER.getEcKeyPair().getPrivateKey(), 64),
                "--legacy", "--with-gas-price", "0", "--broadcast", "--slow");
        Path runFile = repo.resolve("contracts/broadcast/DeployPlatform.s.sol/" + CHAIN_ID + "/run-latest.json");
        String json = run(repo.toFile(), Map.of(), "bash", "infra/platform/read-run.sh",
                "contracts/out/DeployPlatform.s.sol/DeployPlatform.json", runFile.toString());
        JsonMapper mapper = JsonMapper.builder().build();
        Map<String, String> addresses = mapper.readValue(json, Map.class);
        Map<String, Object> runLatest;
        try {
            runLatest = mapper.readValue(Files.readString(runFile), Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) runLatest.get("receipts");
        long last = Numeric.toBigInt((String) receipts.getLast().get("blockNumber")).longValueExact();
        return new Deployment(addresses, last);
    }

    private static String run(File dir, Map<String, String> env, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(dir).redirectErrorStream(true);
            pb.environment().putAll(env);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IllegalStateException(String.join(" ", command[0], command[1]) + " thất bại:\n"
                        + out.substring(Math.max(0, out.length() - 4000)));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("không chạy được " + command[0] + " (cần Foundry 1.8.5 và jq)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
