// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {AbstractModule} from "@trex/compliance/modular/modules/AbstractModule.sol";
import {IModularCompliance} from "@trex/compliance/modular/IModularCompliance.sol";
import {IToken} from "@trex/token/IToken.sol";

/// @notice Phần chung của ba module tuân thủ WeRWA, dựa trên `AbstractModule` của T-REX 4.1.3 (không sửa mã T-REX).
/// @dev Trạng thái của module tách theo địa chỉ compliance, nên một bản module dùng được cho một hoặc nhiều series.
///      Hàm cấu hình chỉ nhận lời gọi từ compliance đã gắn module (`onlyComplianceCall`); compliance thuộc
///      timelock, nên đổi cấu hình phải qua multisig 3/3 (US-4.01-AC3).
///      Module chưa cấu hình thì từ chối mọi giao dịch nó kiểm (an toàn khi lỗi).
///      Không nâng cấp được (docs/open-questions.md, C13). Không nhận tiền.
abstract contract WeRWAModuleBase is AbstractModule {
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

    function _token(address compliance) internal view returns (IToken) {
        return IToken(IModularCompliance(compliance).getTokenBound());
    }
}
