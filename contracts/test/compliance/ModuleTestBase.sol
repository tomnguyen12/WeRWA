// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {TREXSuite} from "../utils/TREXSuite.sol";
import {Token} from "@trex/token/Token.sol";
import {ModularCompliance} from "@trex/compliance/modular/ModularCompliance.sol";
import {Identity} from "@onchain-id/solidity/contracts/Identity.sol";

/// @notice Nền chung cho kiểm thử module: một series (token + compliance) với trexOwner đóng vai timelock
///         làm chủ compliance, và `agent` đóng vai điều khiển series (agent duy nhất của token).
abstract contract ModuleTestBase is TREXSuite {
    uint16 internal constant CLASS_FOREIGN_INSTITUTION = 1;
    uint16 internal constant CLASS_VIFC_MEMBER = 2;
    uint16 internal constant CLASS_NOT_ELIGIBLE = 9;

    address internal agent = makeAddr("seriesControllerAgent");
    address internal timelock; // = trexOwner, chủ compliance

    Token internal token;
    ModularCompliance internal compliance;

    function _setUpSeries() internal {
        _deployTREXSuite();
        timelock = trexOwner;
        (token, compliance) = _deployToken("WeRWA Series 2027A", "HCMC27A", agent);
        vm.prank(agent);
        token.unpause();
    }

    function _addModule(address module) internal {
        vm.prank(timelock);
        compliance.addModule(module);
    }

    function _callModule(address module, bytes memory data) internal {
        vm.prank(timelock);
        compliance.callModuleFunction(data, module);
    }

    function _kyc(uint256 validUntil) internal pure returns (bytes memory) {
        return abi.encode(uint64(validUntil));
    }

    function _class(uint16 classCode, uint256 validUntil) internal pure returns (bytes memory) {
        return abi.encode(classCode, uint64(validUntil));
    }

    function _investor(string memory label, uint16 classCode) internal returns (address wallet, Identity identity) {
        wallet = makeAddr(label);
        identity =
            _onboardInvestor(wallet, _kyc(block.timestamp + 365 days), _class(classCode, block.timestamp + 365 days));
    }

    function _mint(address to, uint256 qty) internal {
        vm.prank(agent);
        token.mint(to, qty);
    }
}
