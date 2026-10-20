// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {InvestorClassModule} from "../compliance/InvestorClassModule.sol";
import {HoldingLimitModule} from "../compliance/HoldingLimitModule.sol";
import {TransferGateModule} from "../compliance/TransferGateModule.sol";

/// @title ModuleDeployer
/// @notice Tạo bộ ba module tuân thủ riêng cho một series (ADR-04: mỗi series một bộ token và module).
///         Tách khỏi SeriesFactory để mã khởi tạo của module không làm factory vượt giới hạn 24 KB.
/// @dev Không giữ trạng thái, không nâng cấp. Ai gọi cũng chỉ tạo ra module mới chưa gắn vào đâu,
///      nên không cần giới hạn người gọi; module chỉ có hiệu lực khi compliance của series gắn nó.
contract ModuleDeployer {
    struct Modules {
        address investorClass;
        address holdingLimit;
        address transferGate;
    }

    uint256 public immutable kycTopic;
    uint256 public immutable investorClassTopic;

    constructor(uint256 kycTopic_, uint256 investorClassTopic_) {
        kycTopic = kycTopic_;
        investorClassTopic = investorClassTopic_;
    }

    function deploy() external returns (Modules memory m) {
        m.investorClass = address(new InvestorClassModule(kycTopic, investorClassTopic));
        m.holdingLimit = address(new HoldingLimitModule());
        m.transferGate = address(new TransferGateModule());
    }
}
