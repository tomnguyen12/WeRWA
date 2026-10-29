package vn.werwa.issuance.series;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicStruct;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes12;
import org.web3j.abi.datatypes.generated.Bytes3;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.utils.Numeric;
import vn.werwa.governance.api.AuditLog;
import vn.werwa.governance.api.SigningProposals;
import vn.werwa.issuance.IssuanceError;
import vn.werwa.issuance.program.ProgramService;
import vn.werwa.onboarding.api.InvestorClasses;
import vn.werwa.onboarding.api.Wallets;
import vn.werwa.onchain.api.Accounts;
import vn.werwa.platform.config.WerwaProperties;
import vn.werwa.platform.events.Events;
import vn.werwa.platform.web.ApiException;

/**
 * Triển khai series qua Safe 3/3 và gắn ISIN qua Safe VSDC (US-2.07; Q114–Q119); đồng bộ địa chỉ, ISIN và trạng thái
 * từ sự kiện indexer (Q117). Trạng thái ngoài chuỗi chỉ đổi sau khi indexer đã đọc sự kiện trên chuỗi.
 */
public class SeriesDeployment {

    static final String DEPLOY_PURPOSE = "SERIES_DEPLOY";
    static final String ISIN_PURPOSE = "SERIES_ISIN";
    static final String DEPLOYED_EVENT = "werwa.issuance.series.deployed.v1";
    static final String ISIN_EVENT = "werwa.issuance.series.isin_assigned.v1";
    static final String STATE_EVENT = "werwa.issuance.series.state_changed.v1";
    /** Thứ tự {@code SeriesState} trong hợp đồng. */
    static final List<String> CHAIN_STATES = List.of("DEPLOYED", "READY", "ALLOCATED", "ISSUING", "ACTIVE", "DEFAULTED",
            "MATURED", "CLOSED", "CANCELLED");
    /** ADR-0017: mặc định chỉ miễn ví omnibus của lưu ký ({@code 1 << CUSTODIAN_OMNIBUS}). */
    static final int DEFAULT_EXEMPT_KINDS = 1 << 1;
    static final int CUSTODIAN_OMNIBUS = 1;
    private static final Set<String> OPEN = Set.of("QUEUED", "COLLECTING", "THRESHOLD_REACHED", "SENT");
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final SeriesService series;
    private final ProgramService programs;
    private final SigningProposals proposals;
    private final InvestorClasses investorClasses;
    private final Wallets wallets;
    private final Accounts accounts;
    private final WerwaProperties werwa;
    private final Events events;
    private final AuditLog audit;
    private final Clock clock;

    public SeriesDeployment(JdbcTemplate jdbc, SeriesService series, ProgramService programs, SigningProposals proposals,
            InvestorClasses investorClasses, Wallets wallets, Accounts accounts, WerwaProperties werwa, Events events,
            AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.series = series;
        this.programs = programs;
        this.proposals = proposals;
        this.investorClasses = investorClasses;
        this.wallets = wallets;
        this.accounts = accounts;
        this.werwa = werwa;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------- triển khai 3/3 (cạnh 4) ----------

    /**
     * Q115: tạo đề nghị ký {@code governance3of3} gọi {@code deploySeries}. Series phải ở Chờ ký 3 bên và không có đề nghị
     * triển khai đang mở.
     */
    @Transactional
    public SigningProposals.Ref proposeDeploy(UUID seriesId) {
        SeriesService.Series s = lockSeries(seriesId);
        if (!"PENDING_SIGNATURES".equals(s.status())) {
            throw new ApiException(IssuanceError.SERIES_NOT_READY, "cần PENDING_SIGNATURES");
        }
        if (s.signingProposalId() != null && proposals.status(s.signingProposalId()).filter(OPEN::contains).isPresent()) {
            throw new ApiException(IssuanceError.SERIES_DEPLOY_PENDING, s.signingProposalId().toString());
        }
        String calldata = deployCalldata(s);
        SigningProposals.Ref ref = proposals.create(new SigningProposals.Request(DEPLOY_PURPOSE, "series/" + seriesId,
                s.approvalRequestId(), "governance3of3", werwa.contract("seriesFactory").toLowerCase(Locale.ROOT),
                "deploySeries", calldata, s.termsHash()));
        jdbc.update("UPDATE issuance.series SET signing_proposal_id = ?, updated_at = ? WHERE id = ?", ref.id(),
                Timestamp.from(clock.instant()), seriesId);
        audit.record("SERIES_DEPLOY_PROPOSED", "series/" + seriesId, Map.of("signingProposalId", ref.id().toString()));
        return ref;
    }

    /** Calldata {@code SeriesFactory.deploySeries(SeriesTerms, TokenConfig)} theo Q116. */
    String deployCalldata(SeriesService.Series s) {
        SeriesService.Terms t = s.terms();
        ProgramService.Program basis = programs.effectiveOf(s.programId())
                .orElseThrow(() -> new ApiException(IssuanceError.PROGRAM_NOT_EFFECTIVE));
        long periods = s.events().stream().filter(e -> "COUPON".equals(e.eventType())).count();
        java.time.LocalDate maturity = s.events().stream().filter(e -> "MATURITY".equals(e.eventType()))
                .map(ScheduleGenerator.Event::paymentDate).findFirst().orElse(t.maturityDate());
        List<Uint16> classes = investorClasses.codes(t.investorClasses()).values().stream()
                .map(c -> new Uint16(BigInteger.valueOf(c))).toList();
        DynamicStruct terms = new DynamicStruct(
                new Bytes32(bytes32(s.id())),
                new Bytes32(Numeric.hexStringToByteArray(s.termsHash())),
                new Bytes3(t.currency().getBytes(StandardCharsets.US_ASCII)),
                new Uint256(BigInteger.valueOf(t.faceValue().amountMinor())),
                new Uint32(BigInteger.valueOf(t.issuePricePpm())),
                new Uint64(BigInteger.valueOf(t.maxQuantity())),
                new Uint32(BigInteger.valueOf(basis.rateFloorPpm())),
                new Uint32(BigInteger.valueOf(basis.rateCapPpm())),
                new Uint16(BigInteger.valueOf(periods)),
                new Uint64(BigInteger.valueOf(maturity.atStartOfDay(VN).toEpochSecond())),
                new DynamicArray<>(Uint16.class, classes),
                new Uint64(BigInteger.valueOf(t.holdingLimit() == null ? 0 : t.holdingLimit())),
                new Address(werwa.safe("ubnd")),
                new Address(werwa.safe("vsdc")),
                new Address(werwa.safe("bidv")),
                new Address(accounts.operatorAddress()),
                new Address(werwa.safe("enforcement2of2")),
                new Address(werwa.safe("governance3of3")));
        List<String> omnibus = wallets.registeredOmnibus(werwa.chain().id());
        DynamicStruct config = new DynamicStruct(
                new Utf8String("WeRWA " + t.seriesCode()),
                new Utf8String(t.seriesCode()),
                new Uint8(BigInteger.valueOf(DEFAULT_EXEMPT_KINDS)),
                new DynamicArray<>(Address.class, omnibus.stream().map(Address::new).toList()),
                new DynamicArray<>(Uint8.class, omnibus.stream().map(a -> new Uint8(BigInteger.valueOf(CUSTODIAN_OMNIBUS)))
                        .toList()));
        return FunctionEncoder.encode(new Function("deploySeries", List.of(terms, config), List.of()));
    }

    // ---------- ISIN (cạnh 5) ----------

    /** Q118: VSDC gắn ISIN qua Safe VSDC; kiểm ISO 6166 ngoài chuỗi trước (US-2.07-AC2, AC3). */
    @Transactional
    public SigningProposals.Ref assignIsin(UUID seriesId, String isin) {
        if (!isValidIsin(isin)) {
            throw new ApiException(IssuanceError.ISIN_INVALID, isin);
        }
        SeriesService.Series s = lockSeries(seriesId);
        if (s.isin() != null) {
            throw new ApiException(IssuanceError.ISIN_ALREADY_ASSIGNED, s.isin());
        }
        UUID pending = jdbc.queryForObject("SELECT isin_proposal_id FROM issuance.series WHERE id = ?", UUID.class, seriesId);
        if (pending != null && proposals.status(pending).filter(OPEN::contains).isPresent()) {
            throw new ApiException(IssuanceError.ISIN_ALREADY_ASSIGNED, "đề nghị " + pending + " đang mở");
        }
        if (!"DEPLOYED".equals(s.status()) || s.controllerAddress() == null) {
            throw new ApiException(IssuanceError.SERIES_NOT_READY, "cần DEPLOYED");
        }
        String calldata = FunctionEncoder.encode(new Function("setIsin",
                List.of(new Bytes12(isin.getBytes(StandardCharsets.US_ASCII))), List.of()));
        SigningProposals.Ref ref = proposals.create(new SigningProposals.Request(ISIN_PURPOSE, "series/" + seriesId, null,
                "vsdc", s.controllerAddress().toLowerCase(Locale.ROOT), "setIsin", calldata));
        jdbc.update("UPDATE issuance.series SET isin_proposal_id = ?, updated_at = ? WHERE id = ?", ref.id(),
                Timestamp.from(clock.instant()), seriesId);
        audit.record("SERIES_ISIN_PROPOSED", "series/" + seriesId, Map.of("isin", isin, "signingProposalId",
                ref.id().toString()));
        return ref;
    }

    /** ISO 6166: 2 chữ cái, 9 ký tự A–Z0–9, chữ số kiểm tra Luhn trên chuỗi chữ số đã đổi chữ cái (A=10…Z=35). */
    public static boolean isValidIsin(String isin) {
        if (isin == null || !isin.matches("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")) {
            return false;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : isin.substring(0, 11).toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        int sum = 0;
        boolean twice = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            twice = !twice;
        }
        return (10 - sum % 10) % 10 == isin.charAt(11) - '0';
    }

    // ---------- đồng bộ từ chuỗi (Q117) ----------

    /** Một sự kiện indexer đã đọc của factory hoặc controller series. */
    @Transactional
    public void onIndexed(Map<String, Object> data) {
        String kind = String.valueOf(data.get("contractKind"));
        String name = String.valueOf(data.get("eventName"));
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) data.getOrDefault("args", Map.of());
        String txHash = String.valueOf(data.get("txHash"));
        if ("seriesfactory".equals(kind) && "SeriesCreated".equals(name)) {
            UUID seriesId = uuidOf(String.valueOf(args.get("seriesId")));
            String controller = lower(args.get("controller"));
            String token = lower(args.get("token"));
            int n = jdbc.update("""
                    UPDATE issuance.series SET controller_address = ?, token_address = ?, chain_id = ?, updated_at = ?
                    WHERE id = ? AND controller_address IS NULL
                    """, controller, token, ((Number) data.get("chainId")).longValue(), Timestamp.from(clock.instant()),
                    seriesId);
            if (n > 0) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("seriesId", seriesId.toString());
                ev.put("controllerAddress", controller);
                ev.put("tokenAddress", token);
                events.publish(DEPLOYED_EVENT, "series/" + seriesId, ev);
                audit.record("SERIES_CONTRACTS_RECORDED", "series/" + seriesId, ev);
            }
            return;
        }
        if (!"series.controller".equals(kind)) {
            return;
        }
        String contract = lower(data.get("contract"));
        UUID seriesId = jdbc.queryForList("SELECT id FROM issuance.series WHERE controller_address = ?", UUID.class, contract)
                .stream().findFirst()
                // SeriesCreated của factory luôn đứng trước trong cùng giao dịch; chưa thấy thì để JetStream gửi lại
                .orElseThrow(() -> new IllegalStateException("chưa biết series của controller " + contract));
        switch (name) {
            case "IsinAssigned" -> {
                String isin = new String(Numeric.hexStringToByteArray(String.valueOf(args.get("isin"))),
                        StandardCharsets.US_ASCII).trim();
                int n = jdbc.update("UPDATE issuance.series SET isin = ?, updated_at = ? WHERE id = ? AND isin IS NULL", isin,
                        Timestamp.from(clock.instant()), seriesId);
                if (n > 0) {
                    events.publish(ISIN_EVENT, "series/" + seriesId, Map.of("seriesId", seriesId.toString(), "isin", isin));
                    audit.record("SERIES_ISIN_ASSIGNED", "series/" + seriesId, Map.of("isin", isin, "txHash", txHash));
                }
            }
            case "AllocationSet" -> recordRates(seriesId, args.get("periodRatePpm"), txHash);
            case "StateChanged" -> applyState(seriesId, CHAIN_STATES.get(Integer.parseInt(String.valueOf(args.get("to")))),
                    txHash);
            default -> {
                // Sự kiện khác của controller do nhóm khác xử lý
            }
        }
    }

    /**
     * Q117: trạng thái trên chuỗi sang ngoài chuỗi. Trên chuỗi READY mà ngoài chuỗi đã Đang chào bán hoặc Đã đóng sổ thì
     * giữ; trùng trạng thái thì không làm gì. Mỗi lần đổi có nhật ký và {@code series.state_changed} (US-2.07-AC4).
     */
    void applyState(UUID seriesId, String to, String txHash) {
        String from = jdbc.queryForObject("SELECT status FROM issuance.series WHERE id = ? FOR UPDATE", String.class, seriesId);
        if (to.equals(from) || ("READY".equals(to) && Set.of("OFFERING", "BOOK_CLOSED").contains(from))) {
            return;
        }
        jdbc.update("UPDATE issuance.series SET status = ?, version = version + 1, updated_at = ? WHERE id = ?", to,
                Timestamp.from(clock.instant()), seriesId);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("seriesId", seriesId.toString());
        ev.put("from", from);
        ev.put("to", to);
        ev.put("txHash", txHash);
        events.publish(STATE_EVENT, "series/" + seriesId, ev);
        audit.record("SERIES_STATE_CHANGED", "series/" + seriesId, ev);
    }

    /** Q72, Q124: lãi suất từng kỳ coupon từ {@code AllocationSet} UBND đã ký; ghi lại an toàn. */
    void recordRates(UUID seriesId, Object rates, String txHash) {
        if (!(rates instanceof List<?> list)) {
            throw new IllegalStateException("AllocationSet thiếu periodRatePpm");
        }
        for (int i = 0; i < list.size(); i++) {
            jdbc.update("""
                    UPDATE issuance.series_event SET rate_ppm = ?, rate_source = 'FIXED_AT_PRICING'
                    WHERE series_id = ? AND event_type = 'COUPON' AND period_no = ?
                    """, Integer.parseInt(String.valueOf(list.get(i))), seriesId, i + 1);
        }
        audit.record("SERIES_RATES_RECORDED", "series/" + seriesId, Map.of("periods", list.size(), "txHash", txHash));
    }

    // ---------- tiện ích ----------

    private SeriesService.Series lockSeries(UUID id) {
        jdbc.queryForList("SELECT id FROM issuance.series WHERE id = ? FOR UPDATE", UUID.class, id).stream().findFirst()
                .orElseThrow(() -> new ApiException(IssuanceError.SERIES_NOT_FOUND));
        return series.get(id);
    }

    /** Q43: {@code bytes32(uint256(uint128(uuid)))}. */
    static byte[] bytes32(UUID id) {
        byte[] out = new byte[32];
        java.nio.ByteBuffer.wrap(out, 16, 16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        return out;
    }

    static UUID uuidOf(String bytes32Hex) {
        byte[] b = Numeric.hexStringToByteArray(bytes32Hex);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(b, 16, 16);
        return new UUID(buf.getLong(), buf.getLong());
    }

    private static String lower(Object o) {
        return String.valueOf(o).toLowerCase(Locale.ROOT);
    }
}
