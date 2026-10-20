// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {SeriesFixture} from "./utils/SeriesFixture.sol";
import {ISeriesController} from "../src/ISeriesController.sol";
import {SeriesState} from "../src/interfaces/ISeriesState.sol";

contract SeriesControllerServicingTest is SeriesFixture {
    event SnapshotTaken(uint16 indexed period, uint64 recordBlock, bytes32 registerHash);
    event SnapshotAttested(uint16 indexed period, bytes32 registerHash, address indexed registrar);
    event SeriesMatured(bytes32 payoutHash);
    event Redeemed(address indexed holder, uint64 burned, uint64 frozenRemaining);
    event FrozenPositionRedeemed(address indexed holder, uint64 quantity, bytes32 payoutHash);
    event SeriesClosed();
    event PositionFrozen(address indexed wallet, uint64 quantity, bytes32 orderHash);
    event PositionUnfrozen(address indexed wallet, uint64 quantity, bytes32 orderHash);
    event ForcedTransfer(address indexed from, address indexed to, uint64 quantity, bytes32 orderHash);
    event FrozenRedemptionPolicySet(ISeriesController.FrozenRedemptionPolicy policy, bytes32 basisHash);
    event StateChanged(SeriesState indexed from, SeriesState indexed to);

    bytes32 internal constant ORDER = keccak256("Lenh phong toa so 12/2027 - SHA-256 can cu");
    bytes32 internal constant PAYOUT = keccak256("IPA xac nhan chi goc");

    address internal alice;
    address internal bob;
    address internal carol;
    address internal stranger = makeAddr("stranger");
    address internal unverified = makeAddr("unverified");
    Alloc[] internal allocs;

    function setUp() public {
        _deploySeries();
        alice = _investor("alice", CLASS_FOREIGN_INSTITUTION);
        bob = _investor("bob", CLASS_VIFC_MEMBER);
        carol = _investor("carol", CLASS_FOREIGN_INSTITUTION);
        allocs.push(Alloc(keccak256("alloc-1"), alice, 1000, keccak256("BIDV-REF-0001")));
        allocs.push(Alloc(keccak256("alloc-2"), bob, 2500, keccak256("BIDV-REF-0002")));
        allocs.push(Alloc(keccak256("alloc-3"), carol, 500, keccak256("BIDV-REF-0003")));
        Alloc[] memory a = allocs;
        _toActive(a);
    }

    function _snapshot(uint16 period, bytes32 h) internal {
        vm.roll(block.number + 1);
        vm.prank(holcoOperator);
        controller.takeSnapshot(period, uint64(block.number - 1), h);
    }

    function _attest(uint16 period, bytes32 h) internal {
        vm.prank(vsdc);
        controller.attestSnapshot(period, h);
    }

    function _toMatured() internal {
        _snapshot(PERIODS, keccak256("principal list"));
        _attest(PERIODS, keccak256("principal list"));
        vm.warp(controller.maturityDate());
        vm.prank(holcoOperator);
        controller.mature(PAYOUT);
    }

    function _holders() internal view returns (address[] memory h) {
        h = new address[](3);
        h[0] = alice;
        h[1] = bob;
        h[2] = carol;
    }

    // =====================================================================
    // takeSnapshot / attestSnapshot
    // =====================================================================

    // US-3.09-AC1: cuối ngày T−1 hệ thống chụp sổ thì mã băm danh sách có trên chuỗi.
    function test_US_3_09_AC1_snapshotHashOnchain() public {
        vm.roll(block.number + 5);
        vm.expectEmit(true, false, false, true, address(controller));
        emit SnapshotTaken(1, uint64(block.number - 1), keccak256("coupon 1 list"));
        vm.prank(holcoOperator);
        controller.takeSnapshot(1, uint64(block.number - 1), keccak256("coupon 1 list"));

        ISeriesController.Snapshot memory s = controller.snapshotOf(1);
        assertEq(s.recordBlock, block.number - 1);
        assertEq(s.registerHash, keccak256("coupon 1 list"));
        assertFalse(s.attested);
    }

    // US-3.09-AC2: VSDC xác nhận danh sách đã chụp.
    function test_US_3_09_AC2_registrarAttestsSnapshot() public {
        _snapshot(1, keccak256("coupon 1 list"));
        vm.expectEmit(true, true, false, true, address(controller));
        emit SnapshotAttested(1, keccak256("coupon 1 list"), vsdc);
        _attest(1, keccak256("coupon 1 list"));
        ISeriesController.Snapshot memory s = controller.snapshotOf(1);
        assertTrue(s.attested);
        assertEq(s.attestedBy, vsdc);
        assertGt(s.attestedAt, 0);
    }

    function test_snapshot_checks() public {
        vm.startPrank(holcoOperator);
        vm.expectRevert(ISeriesController.InvalidPeriod.selector);
        controller.takeSnapshot(0, 0, keccak256("x"));
        vm.expectRevert(ISeriesController.InvalidPeriod.selector);
        controller.takeSnapshot(PERIODS + 1, 0, keccak256("x"));
        vm.expectRevert(ISeriesController.InvalidBlock.selector);
        controller.takeSnapshot(1, uint64(block.number + 1), keccak256("x"));
        vm.expectRevert(ISeriesController.HashMismatch.selector);
        controller.takeSnapshot(1, 0, bytes32(0));
        controller.takeSnapshot(1, 0, keccak256("x"));
        vm.expectRevert(ISeriesController.SnapshotExists.selector);
        controller.takeSnapshot(1, 0, keccak256("y"));
        vm.stopPrank();

        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.takeSnapshot(2, 0, keccak256("x"));
    }

    function test_attestSnapshot_checks() public {
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.HashMismatch.selector);
        controller.attestSnapshot(1, keccak256("never taken"));

        _snapshot(1, keccak256("x"));
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.HashMismatch.selector);
        controller.attestSnapshot(1, keccak256("other"));
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.attestSnapshot(1, keccak256("x"));

        _attest(1, keccak256("x"));
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.AlreadyAttested.selector);
        controller.attestSnapshot(1, keccak256("x"));
    }

    function test_snapshot_badStateBeforeActive() public {
        _deploySeries();
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.takeSnapshot(1, 0, keccak256("x"));
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.attestSnapshot(1, keccak256("x"));
    }

    // =====================================================================
    // mature / burnRedeemed / close
    // =====================================================================

    // US-2.10-AC2: IPA xác nhận chi gốc, Holco gọi mature thì series sang MATURED và chặn chuyển nhượng.
    function test_US_2_10_AC2_matureBlocksTransfers() public {
        _snapshot(PERIODS, keccak256("principal list"));
        _attest(PERIODS, keccak256("principal list"));
        vm.warp(controller.maturityDate());
        vm.expectEmit(false, false, false, true, address(controller));
        emit SeriesMatured(PAYOUT);
        vm.expectEmit(true, true, false, false, address(controller));
        emit StateChanged(SeriesState.ACTIVE, SeriesState.MATURED);
        vm.prank(holcoOperator);
        controller.mature(PAYOUT);

        assertEq(uint8(_state()), uint8(SeriesState.MATURED));
        assertEq(controller.payoutHash(), PAYOUT);
        vm.prank(alice);
        vm.expectRevert("Transfer not possible");
        token.transfer(bob, 1);
    }

    function test_mature_checks() public {
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.NotYetMatured.selector);
        controller.mature(PAYOUT);

        vm.warp(controller.maturityDate());
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.SnapshotNotAttested.selector);
        controller.mature(PAYOUT);

        _snapshot(PERIODS, keccak256("principal list")); // chụp nhưng chưa xác nhận
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.SnapshotNotAttested.selector);
        controller.mature(PAYOUT);

        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.mature(PAYOUT);
    }

    // US-2.10-AC3: hủy toàn bộ số dư không bị phong tỏa; tổng cung bằng 0 thì đóng series.
    function test_US_2_10_AC3_burnAllThenClose() public {
        _toMatured();
        vm.expectEmit(true, false, false, true, address(controller));
        emit Redeemed(alice, 1000, 0);
        vm.prank(holcoOperator);
        controller.burnRedeemed(_holders());
        assertEq(token.totalSupply(), 0);
        assertEq(controller.totalRedeemed(), 4000);

        vm.expectEmit(false, false, false, false, address(controller));
        emit SeriesClosed();
        vm.prank(holcoOperator);
        controller.close();
        assertEq(uint8(_state()), uint8(SeriesState.CLOSED));
    }

    // ADR-0018 (a) mặc định: phần phong tỏa ở lại; series chưa đóng được.
    function test_ADR0018_holdUntilReleased_default() public {
        assertEq(uint8(controller.frozenRedemptionPolicy()), 0);
        vm.prank(enforcement2of2);
        controller.freeze(bob, 300, ORDER);
        _toMatured();

        vm.expectEmit(true, false, false, true, address(controller));
        emit Redeemed(bob, 2200, 300);
        vm.prank(holcoOperator);
        controller.burnRedeemed(_holders());
        assertEq(token.balanceOf(bob), 300);
        assertEq(token.getFrozenTokens(bob), 300);
        assertEq(token.totalSupply(), 300);

        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.SupplyNotZero.selector);
        controller.close();

        // Có lệnh giải tỏa mới: giải tỏa ở MATURED, hủy nốt rồi đóng.
        vm.prank(enforcement2of2);
        controller.unfreeze(bob, 300, keccak256("Lenh giai toa"));
        address[] memory onlyBob = new address[](1);
        onlyBob[0] = bob;
        vm.prank(holcoOperator);
        controller.burnRedeemed(onlyBob);
        vm.prank(holcoOperator);
        controller.close();
        assertEq(uint8(_state()), uint8(SeriesState.CLOSED));
        assertEq(controller.totalRedeemed(), controller.totalMinted());
    }

    // ADR-0018 (a): chuyển cưỡng chế ở MATURED tới ví được chỉ định, rồi hủy.
    function test_ADR0018_holdUntilReleased_forcedTransferAtMaturity() public {
        vm.prank(enforcement2of2);
        controller.freeze(bob, 300, ORDER);
        _toMatured();
        vm.prank(holcoOperator);
        controller.burnRedeemed(_holders());

        vm.prank(enforcement2of2);
        controller.forcedTransfer(bob, carol, 300, keccak256("Lenh chuyen cuong che"));
        assertEq(token.getFrozenTokens(bob), 0); // T-REX tự giải tỏa phần chuyển đi
        address[] memory onlyCarol = new address[](1);
        onlyCarol[0] = carol;
        vm.prank(holcoOperator);
        controller.burnRedeemed(onlyCarol);
        assertEq(token.totalSupply(), 0);
    }

    // ADR-0018 (b): bật BURN_AFTER_PAYOUT khi vận hành, hủy cả phần phong tỏa, ghi FrozenPositionRedeemed.
    function test_ADR0018_burnAfterPayout_switchedAtMaturity() public {
        vm.prank(enforcement2of2);
        controller.freeze(bob, 300, ORDER);
        _toMatured();
        vm.prank(holcoOperator);
        controller.burnRedeemed(_holders()); // theo (a): còn 300 phong tỏa

        vm.expectEmit(false, false, false, true, address(controller));
        emit FrozenRedemptionPolicySet(
            ISeriesController.FrozenRedemptionPolicy.BURN_AFTER_PAYOUT, keccak256("Van ban dong y cua co quan")
        );
        vm.prank(governance3of3);
        controller.setFrozenRedemptionPolicy(
            ISeriesController.FrozenRedemptionPolicy.BURN_AFTER_PAYOUT, keccak256("Van ban dong y cua co quan")
        );

        address[] memory onlyBob = new address[](1);
        onlyBob[0] = bob;
        vm.expectEmit(true, false, false, true, address(controller));
        emit Redeemed(bob, 300, 0);
        vm.expectEmit(true, false, false, true, address(controller));
        emit FrozenPositionRedeemed(bob, 300, PAYOUT);
        vm.prank(holcoOperator);
        controller.burnRedeemed(onlyBob);

        assertEq(token.totalSupply(), 0);
        vm.prank(holcoOperator);
        controller.close();
        assertEq(uint8(_state()), uint8(SeriesState.CLOSED));
    }

    // ADR-0018 (b) đặt trước khi đáo hạn: một lần burnRedeemed là đủ.
    function test_ADR0018_burnAfterPayout_setBeforeMaturity() public {
        vm.prank(governance3of3);
        controller.setFrozenRedemptionPolicy(ISeriesController.FrozenRedemptionPolicy.BURN_AFTER_PAYOUT, bytes32(0));
        vm.prank(enforcement2of2);
        controller.freeze(alice, 1000, ORDER);
        _toMatured();
        vm.prank(holcoOperator);
        controller.burnRedeemed(_holders());
        assertEq(token.totalSupply(), 0);
    }

    function test_setFrozenRedemptionPolicy_rules() public {
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.setFrozenRedemptionPolicy(ISeriesController.FrozenRedemptionPolicy.BURN_AFTER_PAYOUT, bytes32(0));

        _toMatured();
        vm.prank(holcoOperator);
        controller.burnRedeemed(_holders());
        vm.prank(holcoOperator);
        controller.close();
        vm.prank(governance3of3);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.setFrozenRedemptionPolicy(ISeriesController.FrozenRedemptionPolicy.BURN_AFTER_PAYOUT, bytes32(0));
    }

    function test_burnRedeemed_checks() public {
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.burnRedeemed(_holders());

        _toMatured();
        vm.prank(stranger);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.burnRedeemed(_holders());

        vm.prank(enforcement2of2);
        controller.pause();
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.SeriesPaused.selector);
        controller.burnRedeemed(_holders());
    }

    function test_burnRedeemed_emptyAndRepeatHoldersSafe() public {
        _toMatured();
        address[] memory h = new address[](2);
        h[0] = alice;
        h[1] = alice;
        vm.prank(holcoOperator);
        controller.burnRedeemed(h);
        assertEq(token.balanceOf(alice), 0);
        assertEq(controller.totalRedeemed(), 1000);
    }

    function test_close_checks() public {
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.close();
        _toMatured();
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.SupplyNotZero.selector);
        controller.close();
        vm.prank(vsdc);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.close();
    }

    // =====================================================================
    // freeze / unfreeze / forcedTransfer
    // =====================================================================

    // US-4.02-AC2: VSDC và Holco ký 2/2 thì hợp đồng phong tỏa đúng số lượng, ghi mã băm căn cứ.
    function test_US_4_02_AC2_freezeExactQuantityWithBasisHash() public {
        vm.expectEmit(true, false, false, true, address(controller));
        emit PositionFrozen(bob, 700, ORDER);
        vm.prank(enforcement2of2);
        controller.freeze(bob, 700, ORDER);
        assertEq(token.getFrozenTokens(bob), 700);
        assertEq(token.balanceOf(bob), 2500);
    }

    // US-3.08-AC3: phần đang bị phong tỏa không chuyển được.
    function test_US_3_08_AC3_frozenPartCannotTransfer() public {
        vm.prank(enforcement2of2);
        controller.freeze(bob, 2000, ORDER);
        vm.prank(bob);
        vm.expectRevert("Insufficient Balance");
        token.transfer(alice, 501);
        vm.prank(bob);
        token.transfer(alice, 500);
        assertEq(token.balanceOf(bob), 2000);
    }

    // US-4.02-AC4: giải tỏa và chuyển cưỡng chế là lệnh mới, mỗi lệnh một mã băm căn cứ riêng.
    function test_US_4_02_AC4_unfreezeAndForcedTransferAreNewOrders() public {
        vm.prank(enforcement2of2);
        controller.freeze(bob, 1000, ORDER);

        vm.expectEmit(true, false, false, true, address(controller));
        emit PositionUnfrozen(bob, 400, keccak256("Lenh giai toa 1"));
        vm.prank(enforcement2of2);
        controller.unfreeze(bob, 400, keccak256("Lenh giai toa 1"));
        assertEq(token.getFrozenTokens(bob), 600);

        vm.expectEmit(true, true, false, true, address(controller));
        emit ForcedTransfer(bob, carol, 600, keccak256("Lenh chuyen cuong che 1"));
        vm.prank(enforcement2of2);
        controller.forcedTransfer(bob, carol, 600, keccak256("Lenh chuyen cuong che 1"));
        assertEq(token.balanceOf(carol), 1100);
    }

    // I7: chuyển cưỡng chế không tới được ví chưa xác minh.
    function test_I7_forcedTransferToUnverifiedRejected() public {
        vm.prank(enforcement2of2);
        vm.expectRevert(ISeriesController.NotVerified.selector);
        controller.forcedTransfer(bob, unverified, 1, ORDER);
    }

    function test_enforcement_checks() public {
        vm.startPrank(enforcement2of2);
        vm.expectRevert(ISeriesController.ExceedsBalance.selector);
        controller.freeze(bob, 2501, ORDER);
        vm.expectRevert(ISeriesController.ExceedsBalance.selector);
        controller.freeze(bob, 0, ORDER);
        controller.freeze(bob, 2000, ORDER);
        vm.expectRevert(ISeriesController.ExceedsBalance.selector);
        controller.freeze(bob, 501, ORDER); // phần chưa phong tỏa chỉ còn 500
        vm.expectRevert(ISeriesController.ExceedsFrozen.selector);
        controller.unfreeze(bob, 2001, ORDER);
        vm.expectRevert(ISeriesController.ExceedsFrozen.selector);
        controller.unfreeze(bob, 0, ORDER);
        vm.expectRevert(ISeriesController.ExceedsBalance.selector);
        controller.forcedTransfer(bob, carol, 2501, ORDER);
        vm.expectRevert(ISeriesController.ExceedsBalance.selector);
        controller.forcedTransfer(bob, carol, 0, ORDER);
        vm.stopPrank();

        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.freeze(bob, 1, ORDER);
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.unfreeze(bob, 1, ORDER);
        vm.prank(holcoOperator);
        vm.expectRevert(ISeriesController.Unauthorized.selector);
        controller.forcedTransfer(bob, carol, 1, ORDER);
    }

    function test_enforcement_badStateBeforeActiveAndAfterClose() public {
        _deploySeries();
        vm.startPrank(enforcement2of2);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.freeze(bob, 1, ORDER);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.unfreeze(bob, 1, ORDER);
        vm.expectRevert(ISeriesController.BadState.selector);
        controller.forcedTransfer(bob, carol, 1, ORDER);
        vm.stopPrank();
    }

    // C6: phong tỏa vẫn chạy khi series tạm dừng.
    function test_C6_enforcementWorksWhilePaused() public {
        vm.prank(enforcement2of2);
        controller.pause();
        vm.startPrank(enforcement2of2);
        controller.freeze(bob, 100, ORDER);
        controller.unfreeze(bob, 100, ORDER);
        controller.forcedTransfer(bob, carol, 100, ORDER);
        vm.stopPrank();
        assertEq(token.balanceOf(carol), 600);
    }

    // I6: phong tỏa không vượt số dư, kể cả sau chuyển cưỡng chế phần đang phong tỏa.
    function testFuzz_I6_frozenNeverExceedsBalance(uint64 freezeQty, uint64 moveQty) public {
        freezeQty = uint64(bound(freezeQty, 1, 2500));
        moveQty = uint64(bound(moveQty, 1, 2500));
        vm.startPrank(enforcement2of2);
        controller.freeze(bob, freezeQty, ORDER);
        controller.forcedTransfer(bob, carol, moveQty, ORDER);
        vm.stopPrank();
        assertLe(token.getFrozenTokens(bob), token.balanceOf(bob));
    }

    function test_forcedTransfer_tokenReturningFalseReverts() public {
        vm.mockCall(
            address(token), abi.encodeWithSignature("forcedTransfer(address,address,uint256)"), abi.encode(false)
        );
        vm.prank(enforcement2of2);
        vm.expectRevert(ISeriesController.ExceedsBalance.selector);
        controller.forcedTransfer(bob, carol, 1, ORDER);
    }
}
