// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {TREXSuite} from "./utils/TREXSuite.sol";
import {Token} from "@trex/token/Token.sol";
import {ModularCompliance} from "@trex/compliance/modular/ModularCompliance.sol";

/// @notice Kiểm thử khung: bộ T-REX 4.1.3 và ONCHAINID 2.2.1 đã ghim dựng và chạy được với sổ định danh dùng chung.
///         Không kiểm hợp đồng WeRWA; các hợp đồng đó có kiểm thử riêng theo user story.
contract BootstrapTest is TREXSuite {
    address internal agent = makeAddr("agent");
    address internal investorA = makeAddr("investorA");
    address internal investorB = makeAddr("investorB");
    address internal stranger = makeAddr("stranger");

    Token internal token;
    ModularCompliance internal compliance;

    function setUp() public {
        _deployTREXSuite();
        (token, compliance) = _deployToken("WeRWA Test Series", "WRTS", agent);
        _onboardInvestor(investorA, abi.encode(uint64(block.timestamp + 365 days)), abi.encode(uint16(1)));
        _onboardInvestor(investorB, abi.encode(uint64(block.timestamp + 365 days)), abi.encode(uint16(1)));
    }

    function test_pinnedVersions_tokenUsesDecimalsZero() public view {
        assertEq(token.decimals(), 0);
        assertEq(token.version(), "4.1.3");
        assertEq(compliance.getTokenBound(), address(token));
    }

    function test_identityRegistry_verifiesOnlyOnboardedWallets() public view {
        assertTrue(identityRegistry.isVerified(investorA));
        assertFalse(identityRegistry.isVerified(stranger));
    }

    function test_agent_mintsToVerifiedWallet() public {
        vm.prank(agent);
        token.mint(investorA, 10);
        assertEq(token.balanceOf(investorA), 10);
        assertEq(token.totalSupply(), 10);
    }

    function test_agent_cannotMintToUnverifiedWallet() public {
        vm.prank(agent);
        vm.expectRevert("Identity is not verified.");
        token.mint(stranger, 1);
    }

    function test_sharedRegistry_secondTokenSeesSameIdentities() public {
        (Token second,) = _deployToken("WeRWA Test Series 2", "WRT2", agent);
        assertEq(address(second.identityRegistry()), address(token.identityRegistry()));
        vm.prank(agent);
        second.mint(investorB, 5);
        assertEq(second.balanceOf(investorB), 5);
    }

    /// @dev Token T-REX khởi tạo ở trạng thái tạm dừng; ghi lại để thiết kế SeriesController tính tới (PR 4).
    function test_tokenStartsPaused() public {
        assertTrue(token.paused());
        vm.prank(agent);
        token.mint(investorA, 1);
        vm.prank(investorA);
        vm.expectRevert("Pausable: paused");
        token.transfer(investorB, 1);
    }
}
