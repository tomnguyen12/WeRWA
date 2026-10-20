// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {ISeriesState, SeriesState} from "../../src/interfaces/ISeriesState.sol";

/// @notice Điều khiển series giả cho kiểm thử module, đặt thẳng trạng thái và cờ xác nhận sổ.
contract MockSeriesState is ISeriesState {
    SeriesState public state;
    bool public isRegisterAttested;

    function set(SeriesState s, bool attested) external {
        state = s;
        isRegisterAttested = attested;
    }
}
