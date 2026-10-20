// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {IERC3643Module, IERC3643Compliance, IERC3643Token} from "../erc3643/IERC3643.sol";

/// @notice Phần chung của ba module tuân thủ WeRWA, cài thẳng giao diện module của ERC-3643 (ADR-0019, bước chung:
///         không kế thừa mã của bản tham chiếu).
/// @dev Trạng thái của module tách theo địa chỉ compliance, nên một bản module dùng được cho một hoặc nhiều series.
///      Hàm cấu hình chỉ nhận lời gọi từ compliance đã gắn module (`onlyComplianceCall`); compliance thuộc
///      timelock, nên đổi cấu hình phải qua multisig 3/3 (US-4.01-AC3).
///      Module chưa cấu hình thì từ chối mọi giao dịch nó kiểm (an toàn khi lỗi).
///      Không nâng cấp được (docs/open-questions.md, C13). Không nhận tiền.
abstract contract WeRWAModuleBase is IERC3643Module {
    mapping(address => bool) private _complianceBound;

    error OnlyBoundCompliance();
    error OnlyComplianceItself();
    error AlreadyBound();
    error ZeroCompliance();

    modifier onlyComplianceCall() {
        if (!_complianceBound[msg.sender]) revert OnlyBoundCompliance();
        _;
    }

    /// @notice Compliance gọi khi gắn module (trong addModule của compliance).
    function bindCompliance(address compliance) external override {
        if (compliance == address(0)) revert ZeroCompliance();
        if (msg.sender != compliance) revert OnlyComplianceItself();
        if (_complianceBound[compliance]) revert AlreadyBound();
        _complianceBound[compliance] = true;
        emit ComplianceBound(compliance);
    }

    /// @notice Compliance gọi khi gỡ module (trong removeModule của compliance).
    function unbindCompliance(address compliance) external override onlyComplianceCall {
        if (msg.sender != compliance) revert OnlyComplianceItself();
        _complianceBound[compliance] = false;
        emit ComplianceUnbound(compliance);
    }

    function isComplianceBound(address compliance) external view override returns (bool) {
        return _complianceBound[compliance];
    }

    /// @dev Module không cập nhật trạng thái khi chuyển, mint, burn.
    function moduleTransferAction(address, address, uint256) external override onlyComplianceCall {}

    function moduleMintAction(address, uint256) external override onlyComplianceCall {}

    function moduleBurnAction(address, uint256) external override onlyComplianceCall {}

    function canComplianceBind(address) external pure override returns (bool) {
        return true;
    }

    function isPlugAndPlay() external pure override returns (bool) {
        return true;
    }

    function _token(address compliance) internal view returns (IERC3643Token) {
        return IERC3643Token(IERC3643Compliance(compliance).getTokenBound());
    }
}
