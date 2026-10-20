// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {ISeriesController} from "./ISeriesController.sol";
import {SeriesState} from "./interfaces/ISeriesState.sol";
import {SeriesLeaves} from "./libraries/SeriesLeaves.sol";
import {IsinLib} from "./libraries/IsinLib.sol";
import {IERC3643Token, IERC3643IdentityRegistry} from "./erc3643/IERC3643.sol";
import {MerkleProof} from "@openzeppelin/contracts/utils/cryptography/MerkleProof.sol";

/// @title SeriesController
/// @notice Điều khiển một series trái phiếu: máy trạng thái, danh sách phân bổ, lô BIDV, mint hai đường chứng minh,
///         xác nhận sổ, tạm dừng. Agent duy nhất của token T-REX của series.
///         Đặc tả: docs/design/04-hop-dong-thong-minh.md; máy trạng thái: docs/design/03-may-trang-thai.md.
/// @dev Chạy sau BeaconProxy (C13); nâng cấp qua beacon do timelock sở hữu. Giữ `__gap` cho nâng cấp.
///      Không nhận tiền, không delegatecall, không selfdestruct.
///      markDefault, markCured và recoverWallet thuộc Đ1 (C9, C11), chưa có ở bản này.
contract SeriesController is ISeriesController {
    uint256 private constant _PPM = 1_000_000;

    // ---- Khởi tạo ----
    bool private _initialized;

    // ---- Hợp đồng gắn kèm ----
    IERC3643Token public token;
    IERC3643IdentityRegistry public identityRegistry;

    // ---- Điều khoản (khóa sau khi triển khai) ----
    bytes32 public seriesId;
    bytes32 public termsHash;
    bytes3 public currency;
    uint256 public faceValueMinor;
    uint32 public issuePricePpm;
    uint64 public maxQuantity;
    uint32 public rateFloorPpm;
    uint32 public rateCapPpm;
    uint16 public periodCount;
    uint64 public maturityDate;

    // ---- Vai ----
    address public issuer;
    address public registrar;
    address public payingAgent;
    address public operator;
    address public enforcement;
    address public governance;

    // ---- Trạng thái ----
    SeriesState public state;
    bool public paused;
    bool public paymentAttestationRequired;
    bytes12 public isin;

    // ---- Phân bổ và mint ----
    bytes32 public allocationRoot;
    bytes32 public allocationListHash;
    uint64 public totalAllocated;
    uint64 public totalMinted;
    uint32[] private _periodRatePpm;
    mapping(bytes32 => bytes32) private _paymentBatchHash; // gốc lô BIDV → mã băm lô (0 = chưa có)
    mapping(bytes32 => MintRecord) private _minted; // allocationId → mục đã mint

    RegistrarAttestation private _registerAttestation;

    // ---- Lưu hành và đáo hạn (PR 5) ----
    mapping(uint16 => Snapshot) private _snapshots; // kỳ → danh sách chốt
    uint64 public totalRedeemed;
    FrozenRedemptionPolicy public frozenRedemptionPolicy;
    bytes32 public payoutHash;

    /// @dev Khoảng trống lưu trữ cho các bản nâng cấp. Thêm biến mới ngay trên dòng này và bớt __gap tương ứng.
    // slither-disable-next-line naming-convention,unused-state
    uint256[27] private __gap;

    modifier only(address role) {
        if (msg.sender != role) revert Unauthorized();
        _;
    }

    modifier whenNotPaused() {
        if (paused) revert SeriesPaused();
        _;
    }

    /// @dev Khóa bản triển khai gốc (implementation) để không ai khởi tạo được nó.
    constructor() {
        _initialized = true;
    }

    // =====================================================================
    // Phát hành
    // =====================================================================

    /// @notice Gắn token, lưu điều khoản, gán vai. Factory gọi ngay sau khi triển khai proxy.
    /// @dev Token phải đã có hợp đồng này làm agent. Compliance và sổ định danh lấy từ token.
    ///      Mở tạm dừng của token T-REX (C16): khóa chuyển nhượng do TransferGateModule giữ.
    function initialize(SeriesTerms calldata terms, address token_) external {
        if (_initialized) revert AlreadyInitialized();
        _initialized = true;

        if (
            token_ == address(0) || terms.issuer == address(0) || terms.registrar == address(0)
                || terms.payingAgent == address(0) || terms.operator == address(0) || terms.enforcement == address(0)
                || terms.governance == address(0)
        ) revert ZeroAddress();
        if (
            terms.maxQuantity == 0 || terms.faceValueMinor == 0 || terms.issuePricePpm == 0 || terms.periodCount == 0
                || terms.rateFloorPpm > terms.rateCapPpm || terms.currency == bytes3(0)
                || IERC3643Token(token_).decimals() != 0
        ) revert InvalidTerms();

        token = IERC3643Token(token_);
        identityRegistry = IERC3643Token(token_).identityRegistry();

        seriesId = terms.seriesId;
        termsHash = terms.termsHash;
        currency = terms.currency;
        faceValueMinor = terms.faceValueMinor;
        issuePricePpm = terms.issuePricePpm;
        maxQuantity = terms.maxQuantity;
        rateFloorPpm = terms.rateFloorPpm;
        rateCapPpm = terms.rateCapPpm;
        periodCount = terms.periodCount;
        maturityDate = terms.maturityDate;

        issuer = terms.issuer;
        registrar = terms.registrar;
        payingAgent = terms.payingAgent;
        operator = terms.operator;
        enforcement = terms.enforcement;
        governance = terms.governance;

        paymentAttestationRequired = true;
        state = SeriesState.DEPLOYED;

        emit SeriesDeployed(terms.seriesId, token_, terms.termsHash, terms.maxQuantity, terms.periodCount);
        emit StateChanged(SeriesState.DEPLOYED, SeriesState.DEPLOYED);

        if (IERC3643Token(token_).paused()) IERC3643Token(token_).unpause();
    }

    /// @notice VSDC gắn ISIN, mỗi series một lần. DEPLOYED → READY.
    function setIsin(bytes12 isin_) external only(registrar) {
        if (isin != bytes12(0)) revert IsinAlreadySet();
        if (state != SeriesState.DEPLOYED) revert BadState();
        if (!IsinLib.isValid(isin_)) revert InvalidIsin();
        isin = isin_;
        emit IsinAssigned(isin_);
        _transition(SeriesState.READY);
    }

    /// @notice UBND ký gốc danh sách phân bổ và lãi suất các kỳ. READY → ALLOCATED.
    function setAllocation(bytes32 root, bytes32 listHash, uint64 totalQuantity, uint32[] calldata periodRatePpm)
        external
        only(issuer)
    {
        if (state != SeriesState.READY) revert BadState();
        if (totalQuantity > maxQuantity) revert ExceedsMaxQuantity();
        if (periodRatePpm.length != periodCount) revert PeriodCountMismatch();
        for (uint256 i = 0; i < periodRatePpm.length; i++) {
            if (periodRatePpm[i] < rateFloorPpm || periodRatePpm[i] > rateCapPpm) revert RateOutOfRange();
        }
        allocationRoot = root;
        allocationListHash = listHash;
        totalAllocated = totalQuantity;
        _periodRatePpm = periodRatePpm;
        emit AllocationSet(root, listHash, totalQuantity, periodRatePpm);
        _transition(SeriesState.ALLOCATED);
    }

    /// @notice BIDV ký gốc một lô xác nhận đã nhận tiền.
    function addPaymentBatch(bytes32 root, bytes32 batchHash) external only(payingAgent) {
        if (state != SeriesState.ALLOCATED && state != SeriesState.ISSUING) revert BadState();
        if (root == bytes32(0) || batchHash == bytes32(0)) revert InvalidPaymentProof();
        if (_paymentBatchHash[root] != bytes32(0)) revert PaymentRootExists();
        _paymentBatchHash[root] = batchHash;
        emit PaymentBatchAdded(root, batchHash);
    }

    /// @notice Holco mint một phân bổ với hai đường chứng minh. Mục đầu tiên: ALLOCATED → ISSUING.
    function mint(
        MintItem calldata item,
        bytes32[] calldata allocProof,
        bytes32 paymentRoot,
        bytes32[] calldata paymentProof
    ) external only(operator) whenNotPaused {
        if (state != SeriesState.ALLOCATED && state != SeriesState.ISSUING) revert BadState();
        if (_minted[item.allocationId].minted) revert AlreadyMinted();

        bytes32 leaf = SeriesLeaves.allocationLeaf(address(this), item.allocationId, item.wallet, item.quantity);
        if (!MerkleProof.verifyCalldata(allocProof, allocationRoot, leaf)) revert InvalidAllocationProof();

        if (paymentAttestationRequired) {
            if (_paymentBatchHash[paymentRoot] == bytes32(0)) revert InvalidPaymentProof();
            bytes32 pLeaf =
                SeriesLeaves.paymentLeaf(address(this), item.allocationId, item.amountMinor, item.bankRefHash);
            if (!MerkleProof.verifyCalldata(paymentProof, paymentRoot, pLeaf)) revert InvalidPaymentProof();
            if (!_amountMatches(item.quantity, item.amountMinor)) revert AmountMismatch();
        }

        if (uint256(totalMinted) + item.quantity > totalAllocated) revert ExceedsAllocated();
        if (!identityRegistry.isVerified(item.wallet)) revert NotVerified();

        _minted[item.allocationId] = MintRecord({wallet: item.wallet, quantity: item.quantity, minted: true});
        totalMinted += item.quantity;

        if (state == SeriesState.ALLOCATED) _transition(SeriesState.ISSUING);
        emit Minted(item.allocationId, item.wallet, item.quantity, item.amountMinor, paymentRoot);

        token.mint(item.wallet, item.quantity);
    }

    /// @notice Holco hủy một mục đã mint sai thực tế (VSDC từ chối xác nhận), mở lại allocationId để mint đúng.
    function correctionBurn(bytes32 allocationId, address wallet, uint64 quantity)
        external
        only(operator)
        whenNotPaused
    {
        if (state != SeriesState.ISSUING) revert BadState();
        MintRecord memory r = _minted[allocationId];
        if (!r.minted) revert NotMinted();
        if (r.wallet != wallet || r.quantity != quantity) revert MismatchedItem();

        delete _minted[allocationId];
        totalMinted -= quantity;
        emit CorrectionBurned(allocationId, wallet, quantity);
        emit StateChanged(SeriesState.ISSUING, SeriesState.ISSUING);

        token.burn(wallet, quantity);
    }

    /// @notice VSDC ký xác nhận sổ sau phát hành; mở chuyển nhượng. ISSUING → ACTIVE.
    function attestRegister(bytes32 registerHash, uint64 atBlock) external only(registrar) {
        if (state != SeriesState.ISSUING) revert BadState();
        if (atBlock > block.number) revert InvalidBlock();
        _registerAttestation = RegistrarAttestation({
            registerHash: registerHash, atBlock: atBlock, registrar: msg.sender, attestedAt: uint64(block.timestamp)
        });
        emit RegisterAttested(registerHash, atBlock, msg.sender);
        _transition(SeriesState.ACTIVE);
    }

    /// @notice Hủy series trước khi mint mục đầu tiên. Multisig 3/3. DEPLOYED, READY, ALLOCATED → CANCELLED.
    function cancel(bytes32 decisionHash) external only(governance) {
        if (state != SeriesState.DEPLOYED && state != SeriesState.READY && state != SeriesState.ALLOCATED) {
            revert BadState();
        }
        if (totalMinted != 0) revert AlreadyMinted();
        emit SeriesCancelled(decisionHash);
        _transition(SeriesState.CANCELLED);
    }

    /// @notice Bật hoặc tắt điều kiện lô BIDV khi mint (khi BIDV không ký). Chỉ trước ACTIVE.
    function setPaymentAttestationRequired(bool required) external only(governance) {
        if (uint8(state) > uint8(SeriesState.ISSUING)) revert BadState();
        paymentAttestationRequired = required;
        emit PaymentAttestationToggled(required);
    }

    // =====================================================================
    // Tạm dừng (G5, C6): dừng do ENFORCEMENT (2/2), mở lại do GOVERNANCE (3/3)
    // =====================================================================

    function pause() external only(enforcement) {
        _requireNotFinal();
        paused = true;
        emit Paused(msg.sender);
        if (!token.paused()) token.pause();
    }

    function unpause() external only(governance) {
        _requireNotFinal();
        paused = false;
        emit Unpaused(msg.sender);
        if (token.paused()) token.unpause();
    }

    // =====================================================================
    // Lưu hành và trả nợ
    // =====================================================================

    /// @notice Holco ghi mã băm danh sách chốt của một kỳ tại khối cuối ngày T−1.
    function takeSnapshot(uint16 period, uint64 recordBlock, bytes32 registerHash) external only(operator) {
        _requireActiveOrDefaulted();
        if (period == 0 || period > periodCount) revert InvalidPeriod();
        if (_snapshots[period].registerHash != bytes32(0)) revert SnapshotExists();
        if (recordBlock > block.number) revert InvalidBlock();
        if (registerHash == bytes32(0)) revert HashMismatch();
        _snapshots[period].recordBlock = recordBlock;
        _snapshots[period].registerHash = registerHash;
        emit SnapshotTaken(period, recordBlock, registerHash);
    }

    /// @notice VSDC xác nhận danh sách chốt của một kỳ; mã băm phải khớp bản Holco đã ghi.
    function attestSnapshot(uint16 period, bytes32 registerHash) external only(registrar) {
        _requireActiveOrDefaulted();
        Snapshot storage snap = _snapshots[period];
        if (snap.registerHash == bytes32(0) || snap.registerHash != registerHash) revert HashMismatch();
        if (snap.attested) revert AlreadyAttested();
        snap.attested = true;
        snap.attestedBy = msg.sender;
        // casting to 'uint64' is safe because block.timestamp stays below 2^64 for billions of years
        // forge-lint: disable-next-line(unsafe-typecast)
        snap.attestedAt = uint64(block.timestamp);
        emit SnapshotAttested(period, registerHash, msg.sender);
    }

    /// @notice Đáo hạn: đã tới ngày đáo hạn và danh sách kỳ gốc (kỳ cuối, C10) đã được VSDC xác nhận.
    ///         ACTIVE, DEFAULTED → MATURED; chặn chuyển nhượng.
    function mature(bytes32 payoutHash_) external only(operator) {
        _requireActiveOrDefaulted();
        // Ngày đáo hạn tính theo ngày; sai lệch vài giây của thời gian khối không ảnh hưởng.
        // slither-disable-next-line timestamp
        if (block.timestamp < maturityDate) revert NotYetMatured();
        if (!_snapshots[periodCount].attested) revert SnapshotNotAttested();
        payoutHash = payoutHash_;
        emit SeriesMatured(payoutHash_);
        _transition(SeriesState.MATURED);
    }

    /// @notice Hủy trái phiếu của các ví sau đáo hạn. Phần phong tỏa xử lý theo frozenRedemptionPolicy (ADR-0018).
    function burnRedeemed(address[] calldata holders) external only(operator) whenNotPaused {
        if (state != SeriesState.MATURED) revert BadState();
        bool burnFrozen = frozenRedemptionPolicy == FrozenRedemptionPolicy.BURN_AFTER_PAYOUT;
        uint256 n = holders.length;
        uint64[] memory amounts = new uint64[](n);
        uint64 total = 0;

        // Bước 1: đọc số dư, ghi trạng thái và sự kiện trước mọi lệnh hủy (checks-effects-interactions).
        // Một ví lặp lại trong danh sách chỉ được tính ở lần đầu.
        for (uint256 i = 0; i < n; i++) {
            address holder = holders[i];
            if (_seenBefore(holders, i)) continue;
            // Số dư trái phiếu luôn ≤ maxQuantity (uint64), nên ép kiểu an toàn.
            // slither-disable-next-line calls-loop
            uint256 balance = token.balanceOf(holder);
            // slither-disable-next-line calls-loop
            uint256 frozen = token.getFrozenTokens(holder);
            uint256 burned = burnFrozen ? balance : balance - frozen;
            uint256 frozenBurned = burnFrozen ? frozen : 0;
            // forge-lint: disable-next-line(unsafe-typecast)
            amounts[i] = uint64(burned);
            // forge-lint: disable-next-line(unsafe-typecast)
            total += uint64(burned);
            // forge-lint: disable-next-line(unsafe-typecast)
            emit Redeemed(holder, uint64(burned), uint64(frozen - frozenBurned));
            // forge-lint: disable-next-line(unsafe-typecast)
            if (frozenBurned > 0) emit FrozenPositionRedeemed(holder, uint64(frozenBurned), payoutHash);
        }
        totalRedeemed += total;

        // Bước 2: hủy trên token (hợp đồng tin cậy của series).
        for (uint256 i = 0; i < n; i++) {
            // slither-disable-next-line calls-loop
            if (amounts[i] > 0) token.burn(holders[i], amounts[i]);
        }
    }

    /// @notice Đóng series khi tổng cung bằng 0. MATURED → CLOSED.
    function close() external only(operator) {
        if (state != SeriesState.MATURED) revert BadState();
        if (token.totalSupply() != 0) revert SupplyNotZero();
        emit SeriesClosed();
        _transition(SeriesState.CLOSED);
    }

    /// @notice Đổi chính sách với vị thế phong tỏa khi đáo hạn (ADR-0018). GOVERNANCE 3/3, kèm mã băm căn cứ.
    function setFrozenRedemptionPolicy(FrozenRedemptionPolicy policy, bytes32 basisHash) external only(governance) {
        _requireNotFinal();
        frozenRedemptionPolicy = policy;
        emit FrozenRedemptionPolicySet(policy, basisHash);
    }

    // =====================================================================
    // Tuân thủ: phong tỏa, giải tỏa, chuyển cưỡng chế (ENFORCEMENT · VSDC + Holco 2/2, G2)
    // Vẫn chạy khi series tạm dừng (C6).
    // =====================================================================

    function freeze(address wallet, uint64 qty, bytes32 orderHash) external only(enforcement) {
        _requireEnforceable();
        uint256 free = token.balanceOf(wallet) - token.getFrozenTokens(wallet);
        if (qty == 0 || qty > free) revert ExceedsBalance();
        emit PositionFrozen(wallet, qty, orderHash);
        token.freezePartialTokens(wallet, qty);
    }

    function unfreeze(address wallet, uint64 qty, bytes32 orderHash) external only(enforcement) {
        _requireEnforceable();
        if (qty == 0 || qty > token.getFrozenTokens(wallet)) revert ExceedsFrozen();
        emit PositionUnfrozen(wallet, qty, orderHash);
        token.unfreezePartialTokens(wallet, qty);
    }

    /// @dev Chuyển cưỡng chế của T-REX tự giải tỏa phần cần thiết của ví chuyển và bỏ qua module tuân thủ;
    ///      ví nhận phải đã xác minh (I7).
    function forcedTransfer(address from, address to, uint64 qty, bytes32 orderHash) external only(enforcement) {
        _requireEnforceable();
        if (!identityRegistry.isVerified(to)) revert NotVerified();
        if (qty == 0 || qty > token.balanceOf(from)) revert ExceedsBalance();
        emit ForcedTransfer(from, to, qty, orderHash);
        if (!token.forcedTransfer(from, to, qty)) revert ExceedsBalance();
    }

    // =====================================================================
    // Đọc
    // =====================================================================

    function snapshotOf(uint16 period) external view returns (Snapshot memory) {
        return _snapshots[period];
    }

    // Slither báo nhầm: so sánh địa chỉ người ký, không dùng thời gian.
    // slither-disable-next-line timestamp
    function isRegisterAttested() external view returns (bool) {
        return _registerAttestation.registrar != address(0);
    }

    function registerAttestation() external view returns (RegistrarAttestation memory) {
        return _registerAttestation;
    }

    /// @notice Lãi suất từng kỳ (ppm) do UBND ký trong setAllocation.
    function periodRates() external view returns (uint32[] memory) {
        return _periodRatePpm;
    }

    function paymentBatchHash(bytes32 root) external view returns (bytes32) {
        return _paymentBatchHash[root];
    }

    function mintRecord(bytes32 allocationId) external view returns (MintRecord memory) {
        return _minted[allocationId];
    }

    function isMinted(bytes32 allocationId) external view returns (bool) {
        return _minted[allocationId].minted;
    }

    function allocationLeaf(bytes32 allocationId, address wallet, uint256 quantity) external view returns (bytes32) {
        return SeriesLeaves.allocationLeaf(address(this), allocationId, wallet, quantity);
    }

    function paymentLeaf(bytes32 allocationId, uint256 amountMinor, bytes32 bankRefHash)
        external
        view
        returns (bytes32)
    {
        return SeriesLeaves.paymentLeaf(address(this), allocationId, amountMinor, bankRefHash);
    }

    /// @notice Số tiền phải nhận cho một số lượng: quantity × faceValueMinor × issuePricePpm / 10⁶.
    ///         `exact = false` khi không chia hết (C5: khi đó mint báo AmountMismatch).
    function expectedAmount(uint64 quantity) public view returns (uint256 amountMinor, bool exact) {
        uint256 gross = uint256(quantity) * faceValueMinor * issuePricePpm;
        return (gross / _PPM, gross % _PPM == 0);
    }

    // =====================================================================
    // Nội bộ
    // =====================================================================

    function _amountMatches(uint64 quantity, uint256 amountMinor) private view returns (bool) {
        (uint256 expected, bool exact) = expectedAmount(quantity);
        return exact && expected == amountMinor;
    }

    function _transition(SeriesState to) private {
        SeriesState from = state;
        state = to;
        emit StateChanged(from, to);
    }

    function _seenBefore(address[] calldata holders, uint256 i) private pure returns (bool) {
        for (uint256 k = 0; k < i; k++) {
            if (holders[k] == holders[i]) return true;
        }
        return false;
    }

    function _requireActiveOrDefaulted() private view {
        if (state != SeriesState.ACTIVE && state != SeriesState.DEFAULTED) revert BadState();
    }

    function _requireEnforceable() private view {
        if (state != SeriesState.ACTIVE && state != SeriesState.DEFAULTED && state != SeriesState.MATURED) {
            revert BadState();
        }
    }

    function _requireNotFinal() private view {
        if (state == SeriesState.CLOSED || state == SeriesState.CANCELLED) revert BadState();
    }
}
