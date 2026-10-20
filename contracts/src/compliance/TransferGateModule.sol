// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {WeRWAModuleBase} from "./WeRWAModuleBase.sol";
import {ISeriesState, SeriesState} from "../interfaces/ISeriesState.sol";

/// @title TransferGateModule
/// @notice Chỉ cho chuyển nhượng khi series ở ACTIVE hoặc DEFAULTED và VSDC đã xác nhận sổ.
///         Mint (from = 0) đi qua; burn không qua kiểm tra tuân thủ của T-REX.
///         docs/design/04-hop-dong-thong-minh.md, mục Module tuân thủ; bất biến I4; giả định G3.
contract TransferGateModule is WeRWAModuleBase {
    mapping(address => ISeriesState) private _controller; // compliance → điều khiển series

    event SeriesControllerSet(address indexed compliance, address indexed seriesController);

    error ZeroAddress();

    /// @notice Gắn điều khiển series để module đọc trạng thái. Gọi qua compliance.
    function setSeriesController(address seriesController) external onlyComplianceCall {
        if (seriesController == address(0)) revert ZeroAddress();
        _controller[msg.sender] = ISeriesState(seriesController);
        emit SeriesControllerSet(msg.sender, seriesController);
    }

    function seriesControllerOf(address compliance) external view returns (address) {
        return address(_controller[compliance]);
    }

    function moduleCheck(address from, address, uint256, address compliance) external view override returns (bool) {
        if (from == address(0)) return true;
        ISeriesState controller = _controller[compliance];
        if (address(controller) == address(0)) return false;
        SeriesState s = controller.state();
        if (s != SeriesState.ACTIVE && s != SeriesState.DEFAULTED) return false;
        return controller.isRegisterAttested();
    }

    function name() public pure override returns (string memory) {
        return "TransferGateModule";
    }
}
