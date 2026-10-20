// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {TimelockController} from "@openzeppelin/contracts/governance/TimelockController.sol";

/// @title WeRWATimelock
/// @notice Timelock nâng cấp và đổi tham số (ADR-05, 6.05; docs/design/04-hop-dong-thong-minh.md, "Quyết định ngày 19/10").
///         - Lên lịch: chỉ multisig 3/3 (UBND + VSDC + Holco).
///         - Hủy lịch: Safe của từng tổ chức (UBND, VSDC, Holco), cùng multisig 3/3.
///         - Thực thi: ai cũng thực thi được sau khi hết thời gian chờ (C15).
///         - Không có quản trị viên: đổi vai hay thời gian chờ phải tự đi qua timelock.
/// @dev Thời gian chờ ở bản XD đề xuất 1 giờ (C15); trước đợt đầu đề xuất 7 ngày.
contract WeRWATimelock is TimelockController {
    error ZeroAddress();

    constructor(uint256 minDelay, address proposer3of3, address[] memory cancellers)
        TimelockController(minDelay, _single(proposer3of3), _single(address(0)), address(0))
    {
        if (proposer3of3 == address(0)) revert ZeroAddress();
        for (uint256 i = 0; i < cancellers.length; i++) {
            if (cancellers[i] == address(0)) revert ZeroAddress();
            _grantRole(CANCELLER_ROLE, cancellers[i]);
        }
    }

    function _single(address a) private pure returns (address[] memory arr) {
        arr = new address[](1);
        arr[0] = a;
    }
}
