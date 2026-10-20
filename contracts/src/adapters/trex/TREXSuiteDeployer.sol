// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {TokenProxy} from "@trex/proxy/TokenProxy.sol";
import {ModularComplianceProxy} from "@trex/proxy/ModularComplianceProxy.sol";
import {IERC3643SuiteDeployer, IERC3643Administrable} from "../../erc3643/IERC3643.sol";

/// @title TREXSuiteDeployer
/// @notice Phương án A của ADR-0019: tạo token và modular compliance bằng bản tham chiếu T-REX 4.1.3
///         (proxy theo Implementation Authority dùng chung). Đây là chỗ duy nhất trong src/ dùng mã T-REX;
///         thay bằng một bản cài khác của IERC3643SuiteDeployer là chuyển sang phương án B.
/// @dev Không giữ trạng thái. Ai gọi cũng chỉ nhận về bộ token mới thuộc chính mình, nên không cần giới hạn người gọi.
contract TREXSuiteDeployer is IERC3643SuiteDeployer {
    /// @notice Implementation Authority của T-REX (thuộc timelock).
    address public immutable implementationAuthority;

    error ZeroAddress();

    constructor(address implementationAuthority_) {
        if (implementationAuthority_ == address(0)) revert ZeroAddress();
        implementationAuthority = implementationAuthority_;
    }

    function deployTokenSet(address identityRegistry, string calldata name, string calldata symbol)
        external
        returns (address token, address compliance)
    {
        compliance = address(new ModularComplianceProxy(implementationAuthority));
        token =
            address(new TokenProxy(implementationAuthority, identityRegistry, compliance, name, symbol, 0, address(0)));
        IERC3643Administrable(token).transferOwnership(msg.sender);
        IERC3643Administrable(compliance).transferOwnership(msg.sender);
    }
}
