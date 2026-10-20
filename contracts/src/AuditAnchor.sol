// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

/// @title AuditAnchor
/// @notice Neo mã băm cuối ngày của nhật ký kiểm toán lên chuỗi (ADR-13, chức năng 4.08).
///         Mỗi ngày nghiệp vụ một bản ghi, chỉ khóa vận hành Holco (OPERATOR) ghi.
/// @dev Không nâng cấp được (docs/design/04-hop-dong-thong-minh.md). Không nhận tiền.
///      Giao diện theo docs/open-questions.md, C3. `businessDate` dạng YYYYMMDD, ví dụ 20261020.
contract AuditAnchor {
    struct Anchor {
        uint64 lastSeq; // seq của dòng nhật ký cuối ngày (audit_log.seq)
        bytes32 lastEntryHash; // entry_hash của dòng đó
        uint64 anchoredAt; // block.timestamp lúc neo
    }

    /// @notice Bên được đổi khóa vận hành (multisig 3/3). Cố định khi triển khai.
    address public immutable governance;

    /// @notice Khóa vận hành Holco, bên duy nhất được neo.
    address public operator;

    /// @notice Ngày nghiệp vụ mới nhất đã neo (0 nếu chưa có).
    uint32 public latestBusinessDate;

    mapping(uint32 => Anchor) private _anchors; // businessDate → Anchor

    event AuditAnchored(uint32 indexed businessDate, uint64 lastSeq, bytes32 lastEntryHash, address indexed operator);
    event OperatorChanged(address indexed previousOperator, address indexed newOperator);

    error Unauthorized();
    error AlreadyAnchored(uint32 businessDate);
    error ZeroAddress();

    constructor(address governance_, address operator_) {
        if (governance_ == address(0) || operator_ == address(0)) revert ZeroAddress();
        governance = governance_;
        operator = operator_;
        emit OperatorChanged(address(0), operator_);
    }

    /// @notice Neo mã băm cuối của nhật ký kiểm toán cho một ngày nghiệp vụ.
    function anchor(uint32 businessDate, uint64 lastSeq, bytes32 lastEntryHash) external {
        if (msg.sender != operator) revert Unauthorized();
        if (_anchors[businessDate].anchoredAt != 0) revert AlreadyAnchored(businessDate);

        // casting to 'uint64' is safe because block.timestamp stays below 2^64 for billions of years
        // forge-lint: disable-next-line(unsafe-typecast)
        uint64 nowTs = uint64(block.timestamp);
        _anchors[businessDate] = Anchor({lastSeq: lastSeq, lastEntryHash: lastEntryHash, anchoredAt: nowTs});
        if (businessDate > latestBusinessDate) latestBusinessDate = businessDate;

        emit AuditAnchored(businessDate, lastSeq, lastEntryHash, msg.sender);
    }

    /// @notice Đổi khóa vận hành khi xoay khóa HSM. Chỉ GOVERNANCE.
    function setOperator(address newOperator) external {
        if (msg.sender != governance) revert Unauthorized();
        if (newOperator == address(0)) revert ZeroAddress();
        emit OperatorChanged(operator, newOperator);
        operator = newOperator;
    }

    /// @notice Bản neo của một ngày; `anchoredAt = 0` nghĩa là chưa neo.
    function anchorOf(uint32 businessDate) external view returns (Anchor memory) {
        return _anchors[businessDate];
    }

    function isAnchored(uint32 businessDate) external view returns (bool) {
        return _anchors[businessDate].anchoredAt != 0;
    }
}
