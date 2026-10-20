// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {ISeriesState, SeriesState} from "./interfaces/ISeriesState.sol";

/// @notice Kiểu dữ liệu, sự kiện và lỗi của SeriesController (docs/design/04-hop-dong-thong-minh.md).
///         Tham số sự kiện theo docs/open-questions.md, C4 (duyệt trong PR 4).
interface ISeriesController is ISeriesState {
    struct SeriesTerms {
        bytes32 seriesId; // từ UUID của series
        bytes32 termsHash; // SHA-256 của term sheet đã duyệt
        bytes3 currency; // "USD"
        uint256 faceValueMinor; // mệnh giá, đơn vị nhỏ nhất
        uint32 issuePricePpm; // giá phát hành, 1 000 000 = 100%
        uint64 maxQuantity; // khối lượng tối đa
        uint32 rateFloorPpm; // khung lãi suất của Đề án
        uint32 rateCapPpm;
        uint16 periodCount; // số kỳ coupon
        uint64 maturityDate; // unix time, ngày đáo hạn đã điều chỉnh
        uint16[] investorClasses; // loại nhà đầu tư được phép
        uint64 holdingLimit; // 0 = không giới hạn
        address issuer;
        address registrar;
        address payingAgent;
        address operator;
        address enforcement;
        address governance;
    }

    struct MintItem {
        bytes32 allocationId; // khớp lá phân bổ và lá lô BIDV
        address wallet;
        uint64 quantity;
        uint256 amountMinor; // tiền đã nhận theo lô BIDV
        bytes32 bankRefHash; // keccak256 mã tham chiếu ngân hàng
    }

    struct MintRecord {
        address wallet;
        uint64 quantity;
        bool minted;
    }

    /// @notice Chính sách với vị thế đang phong tỏa khi đáo hạn (ADR-0018). Thứ tự là một phần của ABI.
    enum FrozenRedemptionPolicy {
        HOLD_UNTIL_RELEASED, // (a) mặc định: giữ phần phong tỏa tới khi giải tỏa hoặc chuyển cưỡng chế (G6)
        BURN_AFTER_PAYOUT // (b) hủy cả phần phong tỏa sau khi IPA xác nhận chi gốc
    }

    struct Snapshot {
        uint64 recordBlock;
        bytes32 registerHash;
        bool attested;
        address attestedBy;
        uint64 attestedAt;
    }

    struct RegistrarAttestation {
        bytes32 registerHash;
        uint64 atBlock;
        address registrar;
        uint64 attestedAt;
    }

    // ---- Sự kiện ----
    event SeriesDeployed(
        bytes32 indexed seriesId, address indexed token, bytes32 termsHash, uint64 maxQuantity, uint16 periodCount
    );
    event StateChanged(SeriesState indexed from, SeriesState indexed to);
    event IsinAssigned(bytes12 isin);
    event AllocationSet(bytes32 root, bytes32 listHash, uint64 totalQuantity, uint32[] periodRatePpm);
    event PaymentBatchAdded(bytes32 indexed root, bytes32 batchHash);
    event Minted(
        bytes32 indexed allocationId, address indexed wallet, uint64 quantity, uint256 amountMinor, bytes32 paymentRoot
    );
    event CorrectionBurned(bytes32 indexed allocationId, address indexed wallet, uint64 quantity);
    event RegisterAttested(bytes32 registerHash, uint64 atBlock, address indexed registrar);
    event SeriesCancelled(bytes32 decisionHash);
    event Paused(address indexed by);
    event Unpaused(address indexed by);
    event PaymentAttestationToggled(bool required);
    event SnapshotTaken(uint16 indexed period, uint64 recordBlock, bytes32 registerHash);
    event SnapshotAttested(uint16 indexed period, bytes32 registerHash, address indexed registrar);
    event SeriesMatured(bytes32 payoutHash);
    event Redeemed(address indexed holder, uint64 burned, uint64 frozenRemaining);
    event FrozenPositionRedeemed(address indexed holder, uint64 quantity, bytes32 payoutHash);
    event SeriesClosed();
    event PositionFrozen(address indexed wallet, uint64 quantity, bytes32 orderHash);
    event PositionUnfrozen(address indexed wallet, uint64 quantity, bytes32 orderHash);
    event ForcedTransfer(address indexed from, address indexed to, uint64 quantity, bytes32 orderHash);
    event FrozenRedemptionPolicySet(FrozenRedemptionPolicy policy, bytes32 basisHash);
    event RedemptionConsentRequirementSet(bool required, bytes32 basisHash);
    event RedemptionConsentRecorded(address indexed wallet, uint64 quantity, bytes32 orderHash, bytes32 consentHash);

    // ---- Lỗi theo đặc tả ----
    error AlreadyInitialized();
    error IsinAlreadySet();
    error ExceedsMaxQuantity();
    error RateOutOfRange();
    error PeriodCountMismatch();
    error PaymentRootExists();
    error AlreadyMinted();
    error InvalidAllocationProof();
    error InvalidPaymentProof();
    error AmountMismatch();
    error ExceedsAllocated();
    error NotVerified();
    error NotMinted();
    error MismatchedItem();
    error BadState();
    error SnapshotExists();
    error HashMismatch();
    error NotYetMatured();
    error SnapshotNotAttested();
    error SupplyNotZero();
    error ExceedsBalance();
    error ExceedsFrozen();

    // ---- Lỗi bổ sung (C17, chờ duyệt) ----
    error Unauthorized();
    error ZeroAddress();
    error InvalidTerms();
    error InvalidIsin();
    error InvalidBlock();
    error SeriesPaused();
    error InvalidPeriod();
    error AlreadyAttested();
}
