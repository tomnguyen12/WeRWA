// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {ModuleTestBase} from "./ModuleTestBase.sol";
import {TransferGateModule} from "../../src/compliance/TransferGateModule.sol";
import {SeriesState} from "../../src/interfaces/ISeriesState.sol";
import {MockSeriesState} from "../mocks/MockSeriesState.sol";

contract TransferGateModuleTest is ModuleTestBase {
    TransferGateModule internal gate;
    MockSeriesState internal series;
    address internal alice;
    address internal bob;

    function setUp() public {
        _setUpSeries();
        gate = new TransferGateModule();
        series = new MockSeriesState();
        _addModule(address(gate));
        _callModule(address(gate), abi.encodeCall(TransferGateModule.setSeriesController, (address(series))));
        (alice,) = _investor("alice", CLASS_FOREIGN_INSTITUTION);
        (bob,) = _investor("bob", CLASS_FOREIGN_INSTITUTION);
        series.set(SeriesState.ISSUING, false);
        _mint(alice, 10);
    }

    // US-3.05-AC3: trái phiếu mint ra bị khóa chuyển nhượng.
    function test_US_3_05_AC3_mintedBondsLockedWhileIssuing() public {
        assertEq(token.balanceOf(alice), 10);
        vm.prank(alice);
        vm.expectRevert("Transfer not possible");
        token.transfer(bob, 1);
    }

    // US-3.05-AC5: VSDC ký xác nhận sổ thì series sang Đang lưu hành, mở chuyển nhượng.
    function test_US_3_05_AC5_transferOpensAfterRegisterAttested() public {
        series.set(SeriesState.ACTIVE, true);
        vm.prank(alice);
        token.transfer(bob, 4);
        assertEq(token.balanceOf(bob), 4);
    }

    // US-3.08-AC1: series Đang lưu hành, VSDC đã xác nhận sổ thì chuyển được.
    function test_US_3_08_AC1_transferWhenActiveAndAttested() public {
        series.set(SeriesState.ACTIVE, true);
        vm.prank(alice);
        token.transfer(bob, 10);
        assertEq(token.balanceOf(alice), 0);
        assertEq(token.balanceOf(bob), 10);
    }

    // Giả định G3: DEFAULTED vẫn cho chuyển nhượng OTC.
    function test_G3_defaultedStillTransfers() public {
        series.set(SeriesState.DEFAULTED, true);
        vm.prank(alice);
        token.transfer(bob, 1);
        assertEq(token.balanceOf(bob), 1);
    }

    function test_activeWithoutAttestation_blocksTransfer() public {
        series.set(SeriesState.ACTIVE, false);
        assertFalse(gate.moduleCheck(alice, bob, 1, address(compliance)));
        vm.prank(alice);
        vm.expectRevert("Transfer not possible");
        token.transfer(bob, 1);
    }

    /// I4 theo từng trạng thái: chỉ ACTIVE và DEFAULTED cùng cờ xác nhận sổ cho chuyển nhượng.
    function testFuzz_I4_onlyActiveOrDefaultedWithAttestation(uint8 rawState, bool attested) public {
        SeriesState s = SeriesState(bound(rawState, 0, uint8(type(SeriesState).max)));
        series.set(s, attested);
        bool expected = attested && (s == SeriesState.ACTIVE || s == SeriesState.DEFAULTED);
        assertEq(gate.moduleCheck(alice, bob, 1, address(compliance)), expected);
    }

    function testFuzz_mintAlwaysPasses(uint8 rawState, bool attested) public {
        SeriesState s = SeriesState(bound(rawState, 0, uint8(type(SeriesState).max)));
        series.set(s, attested);
        assertTrue(gate.moduleCheck(address(0), bob, 1, address(compliance)));
    }

    function test_transferFrom_alsoGated() public {
        vm.prank(alice);
        token.approve(bob, 5);
        vm.prank(bob);
        vm.expectRevert("Transfer not possible");
        token.transferFrom(alice, bob, 5);

        series.set(SeriesState.ACTIVE, true);
        vm.prank(bob);
        token.transferFrom(alice, bob, 5);
        assertEq(token.balanceOf(bob), 5);
    }

    function test_unconfiguredCompliance_failsClosed() public {
        assertFalse(gate.moduleCheck(alice, bob, 1, makeAddr("otherCompliance")));
        assertEq(gate.seriesControllerOf(makeAddr("otherCompliance")), address(0));
        assertEq(gate.seriesControllerOf(address(compliance)), address(series));
    }

    // US-4.01-AC3: đổi tham số chỉ qua multisig 3 bên (chủ compliance).
    function test_US_4_01_AC3_configOnlyThroughCompliance() public {
        vm.expectRevert("only bound compliance can call");
        gate.setSeriesController(address(1));

        vm.prank(alice);
        vm.expectRevert("Ownable: caller is not the owner");
        compliance.callModuleFunction(
            abi.encodeCall(TransferGateModule.setSeriesController, (address(1))), address(gate)
        );
    }

    function test_setSeriesController_rejectsZero() public {
        vm.prank(timelock);
        vm.expectRevert(TransferGateModule.ZeroAddress.selector);
        compliance.callModuleFunction(
            abi.encodeCall(TransferGateModule.setSeriesController, (address(0))), address(gate)
        );
    }

    function test_burnNotGated() public {
        vm.prank(agent);
        token.burn(alice, 3);
        assertEq(token.balanceOf(alice), 7);
    }

    function test_metadata() public view {
        assertEq(gate.name(), "TransferGateModule");
        assertTrue(gate.isPlugAndPlay());
        assertTrue(gate.canComplianceBind(address(compliance)));
        assertTrue(gate.isComplianceBound(address(compliance)));
    }

    function test_actionsOnlyFromCompliance() public {
        vm.expectRevert("only bound compliance can call");
        gate.moduleTransferAction(alice, bob, 1);
        vm.expectRevert("only bound compliance can call");
        gate.moduleMintAction(alice, 1);
        vm.expectRevert("only bound compliance can call");
        gate.moduleBurnAction(alice, 1);
    }
}
