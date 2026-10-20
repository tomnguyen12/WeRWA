// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {Test} from "forge-std/Test.sol";
import {AuditAnchor} from "../src/AuditAnchor.sol";

contract AuditAnchorTest is Test {
    event AuditAnchored(uint32 indexed businessDate, uint64 lastSeq, bytes32 lastEntryHash, address indexed operator);
    event OperatorChanged(address indexed previousOperator, address indexed newOperator);

    address internal governance = makeAddr("governance3of3");
    address internal operator = makeAddr("holcoOperator");
    address internal stranger = makeAddr("stranger");

    AuditAnchor internal anchorContract;

    uint32 internal constant DAY = 20_261_020;
    bytes32 internal constant HASH = keccak256("entry_hash end of day 2026-10-20");

    function setUp() public {
        anchorContract = new AuditAnchor(governance, operator);
    }

    // US-4.08-AC3: cuối ngày neo nhật ký thì mã băm cuối ngày có trên chuỗi và khớp nhật ký.
    function test_US_4_08_AC3_anchorStoresEndOfDayHash() public {
        vm.warp(1_792_454_400);
        vm.expectEmit(true, true, false, true, address(anchorContract));
        emit AuditAnchored(DAY, 4812, HASH, operator);

        vm.prank(operator);
        anchorContract.anchor(DAY, 4812, HASH);

        AuditAnchor.Anchor memory a = anchorContract.anchorOf(DAY);
        assertEq(a.lastSeq, 4812);
        assertEq(a.lastEntryHash, HASH);
        assertEq(a.anchoredAt, 1_792_454_400);
        assertTrue(anchorContract.isAnchored(DAY));
        assertEq(anchorContract.latestBusinessDate(), DAY);
    }

    function test_anchor_onlyOncePerDay() public {
        vm.startPrank(operator);
        anchorContract.anchor(DAY, 1, HASH);
        vm.expectRevert(abi.encodeWithSelector(AuditAnchor.AlreadyAnchored.selector, DAY));
        anchorContract.anchor(DAY, 2, keccak256("other"));
        vm.stopPrank();
        assertEq(anchorContract.anchorOf(DAY).lastSeq, 1);
    }

    function test_anchor_rejectsNonOperator() public {
        vm.prank(stranger);
        vm.expectRevert(AuditAnchor.Unauthorized.selector);
        anchorContract.anchor(DAY, 1, HASH);

        vm.prank(governance);
        vm.expectRevert(AuditAnchor.Unauthorized.selector);
        anchorContract.anchor(DAY, 1, HASH);
    }

    function test_anchor_latestDateOnlyMovesForward() public {
        vm.startPrank(operator);
        anchorContract.anchor(DAY, 10, HASH);
        anchorContract.anchor(DAY - 1, 5, HASH); // neo bù ngày trước
        vm.stopPrank();
        assertEq(anchorContract.latestBusinessDate(), DAY);
        assertTrue(anchorContract.isAnchored(DAY - 1));
    }

    function test_anchorOf_unanchoredDayIsEmpty() public view {
        AuditAnchor.Anchor memory a = anchorContract.anchorOf(DAY);
        assertEq(a.anchoredAt, 0);
        assertFalse(anchorContract.isAnchored(DAY));
        assertEq(anchorContract.latestBusinessDate(), 0);
    }

    function test_setOperator_governanceRotatesKey() public {
        address next = makeAddr("holcoOperatorRotated");
        vm.expectEmit(true, true, false, false, address(anchorContract));
        emit OperatorChanged(operator, next);
        vm.prank(governance);
        anchorContract.setOperator(next);
        assertEq(anchorContract.operator(), next);

        vm.prank(operator);
        vm.expectRevert(AuditAnchor.Unauthorized.selector);
        anchorContract.anchor(DAY, 1, HASH);

        vm.prank(next);
        anchorContract.anchor(DAY, 1, HASH);
        assertTrue(anchorContract.isAnchored(DAY));
    }

    function test_setOperator_rejectsNonGovernance() public {
        vm.prank(operator);
        vm.expectRevert(AuditAnchor.Unauthorized.selector);
        anchorContract.setOperator(stranger);
    }

    function test_setOperator_rejectsZeroAddress() public {
        vm.prank(governance);
        vm.expectRevert(AuditAnchor.ZeroAddress.selector);
        anchorContract.setOperator(address(0));
    }

    function test_constructor_rejectsZeroAddresses() public {
        vm.expectRevert(AuditAnchor.ZeroAddress.selector);
        new AuditAnchor(address(0), operator);
        vm.expectRevert(AuditAnchor.ZeroAddress.selector);
        new AuditAnchor(governance, address(0));
    }

    function test_constructor_emitsInitialOperator() public {
        vm.expectEmit(true, true, false, false);
        emit OperatorChanged(address(0), operator);
        new AuditAnchor(governance, operator);
    }

    function test_rejectsEther() public {
        vm.deal(stranger, 1 ether);
        vm.prank(stranger);
        (bool ok,) = address(anchorContract).call{value: 1 wei}("");
        assertFalse(ok);
        assertEq(address(anchorContract).balance, 0);
    }

    function testFuzz_anchor_anyDayOnce(uint32 day, uint64 seq, bytes32 h) public {
        vm.startPrank(operator);
        anchorContract.anchor(day, seq, h);
        vm.expectRevert(abi.encodeWithSelector(AuditAnchor.AlreadyAnchored.selector, day));
        anchorContract.anchor(day, seq, h);
        vm.stopPrank();
        assertEq(anchorContract.anchorOf(day).lastEntryHash, h);
    }
}
