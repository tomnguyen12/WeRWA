// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {Test} from "forge-std/Test.sol";
import {Token} from "@trex/token/Token.sol";
import {IdentityRegistry} from "@trex/registry/implementation/IdentityRegistry.sol";

import {MerkleHelper} from "../utils/MerkleHelper.sol";
import {SeriesController} from "../../src/SeriesController.sol";
import {ISeriesController} from "../../src/ISeriesController.sol";
import {SeriesState} from "../../src/interfaces/ISeriesState.sol";
import {SeriesLeaves} from "../../src/libraries/SeriesLeaves.sol";

/// @notice Handler cho kiểm thử bất biến: gọi ngẫu nhiên mọi thao tác của một series do factory triển khai,
///         bằng đúng vai và bằng vai sai, rồi ghi biến "ghost" để kiểm I1–I8.
contract SeriesHandler is Test {
    struct Roles {
        address ubnd;
        address vsdc;
        address bidv;
        address operator;
        address enforcement;
        address governance;
    }

    SeriesController public controller;
    Token public token;
    IdentityRegistry public registry;
    Roles internal roles;

    address[] public holders; // nhà đầu tư đã xác minh, đúng loại
    address public unverified; // ví chưa có định danh
    address public ineligible; // ví đã xác minh nhưng loại không được phép

    // Danh sách phân bổ cố định của kịch bản
    bytes32[] internal allocIds;
    address[] internal allocWallets;
    uint64[] internal allocQty;
    bytes32[] internal bankRefs;
    bytes32[] internal allocLeaves;
    bytes32[] internal payLeaves;
    bytes32 public paymentRoot;
    bool internal batchAdded;

    // ---- Ghost ----
    mapping(bytes32 => int256) public netMints; // allocationId → số lần mint còn hiệu lực
    uint256 public everMinted;
    bool public badTransfer; // I4: chuyển nhượng thành công khi bị khóa
    bool public badEdge; // I5: chuyển trạng thái ngoài sơ đồ
    bool public unauthorizedSuccess; // I5: vai sai gọi thành công
    bool public unverifiedReceiver; // I7: ví chưa xác minh nhận trái phiếu
    bool public forgedMintSuccess; // mint ngoài danh sách thành công
    mapping(bytes32 => uint256) public calls;

    constructor(
        SeriesController controller_,
        IdentityRegistry registry_,
        Roles memory roles_,
        address[] memory holders_,
        address unverified_,
        address ineligible_
    ) {
        controller = controller_;
        token = Token(address(controller_.token()));
        registry = registry_;
        roles = roles_;
        holders = holders_;
        unverified = unverified_;
        ineligible = ineligible_;

        uint64[6] memory q = [uint64(100), 200, 300, 150, 250, 50];
        for (uint256 i = 0; i < q.length; i++) {
            bytes32 id = keccak256(abi.encode("alloc", i));
            address w = holders_[i % holders_.length];
            bytes32 ref = keccak256(abi.encode("ref", i));
            allocIds.push(id);
            allocWallets.push(w);
            allocQty.push(q[i]);
            bankRefs.push(ref);
            allocLeaves.push(SeriesLeaves.allocationLeaf(address(controller_), id, w, q[i]));
            payLeaves.push(SeriesLeaves.paymentLeaf(address(controller_), id, _amount(q[i]), ref));
        }
        paymentRoot = MerkleHelper.root(payLeaves);
    }

    function holdersLength() external view returns (uint256) {
        return holders.length;
    }

    function allocCount() external view returns (uint256) {
        return allocIds.length;
    }

    function allocIdAt(uint256 i) external view returns (bytes32) {
        return allocIds[i];
    }

    // =====================================================================
    // Bao mỗi thao tác: kiểm cạnh chuyển trạng thái (I5)
    // =====================================================================

    modifier tracked(string memory name) {
        calls[keccak256(bytes(name))]++;
        SeriesState before = controller.state();
        _;
        SeriesState afterS = controller.state();
        if (before != afterS && !_edgeAllowed(before, afterS)) badEdge = true;
    }

    function _edgeAllowed(SeriesState f, SeriesState t) internal pure returns (bool) {
        if (f == SeriesState.DEPLOYED) return t == SeriesState.READY || t == SeriesState.CANCELLED;
        if (f == SeriesState.READY) return t == SeriesState.ALLOCATED || t == SeriesState.CANCELLED;
        if (f == SeriesState.ALLOCATED) return t == SeriesState.ISSUING || t == SeriesState.CANCELLED;
        if (f == SeriesState.ISSUING) return t == SeriesState.ACTIVE;
        if (f == SeriesState.ACTIVE) return t == SeriesState.DEFAULTED || t == SeriesState.MATURED;
        if (f == SeriesState.DEFAULTED) return t == SeriesState.ACTIVE || t == SeriesState.MATURED;
        if (f == SeriesState.MATURED) return t == SeriesState.CLOSED;
        return false; // CLOSED, CANCELLED là trạng thái cuối
    }

    // =====================================================================
    // Thao tác đúng vai
    // =====================================================================

    function setIsin() external tracked("setIsin") {
        vm.prank(roles.vsdc);
        try controller.setIsin("US0378331005") {} catch {}
    }

    function setAllocation(uint8 rateSeed) external tracked("setAllocation") {
        uint32 rate = uint32(bound(rateSeed, 0, 30_000)) + 40_000;
        uint32[] memory rates = new uint32[](controller.periodCount());
        for (uint256 i = 0; i < rates.length; i++) {
            rates[i] = rate;
        }
        uint64 total;
        for (uint256 i = 0; i < allocQty.length; i++) {
            total += allocQty[i];
        }
        vm.prank(roles.ubnd);
        try controller.setAllocation(MerkleHelper.root(allocLeaves), keccak256("list"), total, rates) {} catch {}
    }

    function addBatch() external tracked("addBatch") {
        vm.prank(roles.bidv);
        try controller.addPaymentBatch(paymentRoot, keccak256("batch")) {
            batchAdded = true;
        } catch {}
    }

    function mint(uint256 seed) external tracked("mint") {
        uint256 i = bound(seed, 0, allocIds.length - 1);
        ISeriesController.MintItem memory item =
            ISeriesController.MintItem(allocIds[i], allocWallets[i], allocQty[i], _amount(allocQty[i]), bankRefs[i]);
        bytes32[] memory ap = MerkleHelper.proof(allocLeaves, i);
        bytes32[] memory pp = MerkleHelper.proof(payLeaves, i);
        bool wasVerified = registry.isVerified(item.wallet);
        vm.prank(roles.operator);
        try controller.mint(item, ap, paymentRoot, pp) {
            netMints[allocIds[i]] += 1;
            everMinted += allocQty[i];
            if (!wasVerified) unverifiedReceiver = true;
        } catch {}
    }

    /// @notice Holco cố mint mục giả (đổi ví nhận, tăng số lượng): phải luôn bị từ chối (cảnh demo ⑥).
    function forgedMint(uint256 seed, uint8 kind) external tracked("forgedMint") {
        uint256 i = bound(seed, 0, allocIds.length - 1);
        ISeriesController.MintItem memory item =
            ISeriesController.MintItem(allocIds[i], allocWallets[i], allocQty[i], _amount(allocQty[i]), bankRefs[i]);
        if (kind % 3 == 0) item.wallet = holders[(i + 1) % holders.length];
        else if (kind % 3 == 1) item.quantity += 1;
        else item.amountMinor -= 1;
        bytes32[] memory ap = MerkleHelper.proof(allocLeaves, i);
        bytes32[] memory pp = MerkleHelper.proof(payLeaves, i);
        vm.prank(roles.operator);
        try controller.mint(item, ap, paymentRoot, pp) {
            forgedMintSuccess = true;
        } catch {}
    }

    function correctionBurn(uint256 seed) external tracked("correctionBurn") {
        uint256 i = bound(seed, 0, allocIds.length - 1);
        vm.prank(roles.operator);
        try controller.correctionBurn(allocIds[i], allocWallets[i], allocQty[i]) {
            netMints[allocIds[i]] -= 1;
        } catch {}
    }

    function attestRegister() external tracked("attestRegister") {
        vm.roll(block.number + 1);
        vm.prank(roles.vsdc);
        try controller.attestRegister(keccak256("register"), uint64(block.number - 1)) {} catch {}
    }

    /// @dev Hủy series chỉ thử ở khoảng 1/32 số lần gọi, để phần lớn lượt fuzz đi tiếp tới các trạng thái sau.
    function cancel(uint8 seed) external tracked("cancel") {
        if (seed % 32 != 0) return;
        vm.prank(roles.governance);
        try controller.cancel(keccak256("decision")) {} catch {}
    }

    function transfer(uint256 fromSeed, uint256 toSeed, uint256 amount) external tracked("transfer") {
        address from = holders[bound(fromSeed, 0, holders.length - 1)];
        address to = _anyReceiver(toSeed);
        uint256 free = token.balanceOf(from) - token.getFrozenTokens(from);
        amount = bound(amount, 0, free + 1); // đôi khi vượt phần tự do để thử chặn
        SeriesState s = controller.state();
        bool attested = controller.isRegisterAttested();
        bool wasVerified = registry.isVerified(to);
        vm.prank(from);
        try token.transfer(to, amount) {
            if (amount > 0) {
                if (!((s == SeriesState.ACTIVE || s == SeriesState.DEFAULTED) && attested)) badTransfer = true;
                if (!wasVerified) unverifiedReceiver = true;
            }
        } catch {}
    }

    function freeze(uint256 seed, uint64 qty) external tracked("freeze") {
        address w = holders[bound(seed, 0, holders.length - 1)];
        qty = uint64(bound(qty, 1, 400));
        vm.prank(roles.enforcement);
        try controller.freeze(w, qty, keccak256("order")) {} catch {}
    }

    function unfreeze(uint256 seed, uint64 qty) external tracked("unfreeze") {
        address w = holders[bound(seed, 0, holders.length - 1)];
        qty = uint64(bound(qty, 1, 400));
        vm.prank(roles.enforcement);
        try controller.unfreeze(w, qty, keccak256("order")) {} catch {}
    }

    function forcedTransfer(uint256 fromSeed, uint256 toSeed, uint64 qty) external tracked("forcedTransfer") {
        address from = holders[bound(fromSeed, 0, holders.length - 1)];
        address to = _anyReceiver(toSeed);
        qty = uint64(bound(qty, 1, 400));
        bool wasVerified = registry.isVerified(to);
        vm.prank(roles.enforcement);
        try controller.forcedTransfer(from, to, qty, keccak256("order")) {
            if (!wasVerified) unverifiedReceiver = true;
        } catch {}
    }

    function snapshot(uint16 period, bool alsoAttest) external tracked("snapshot") {
        period = uint16(bound(period, 1, controller.periodCount()));
        bytes32 h = keccak256(abi.encode("list", period));
        vm.roll(block.number + 1);
        vm.prank(roles.operator);
        try controller.takeSnapshot(period, uint64(block.number - 1), h) {} catch {}
        if (alsoAttest) {
            vm.prank(roles.vsdc);
            try controller.attestSnapshot(period, h) {} catch {}
        }
    }

    function mature() external tracked("mature") {
        if (block.timestamp < controller.maturityDate()) vm.warp(controller.maturityDate());
        vm.prank(roles.operator);
        try controller.mature(keccak256("payout")) {} catch {}
    }

    function burnRedeemed() external tracked("burnRedeemed") {
        vm.prank(roles.operator);
        try controller.burnRedeemed(holders) {} catch {}
    }

    function close() external tracked("close") {
        vm.prank(roles.operator);
        try controller.close() {} catch {}
    }

    function pause(bool on) external tracked("pause") {
        if (on) {
            vm.prank(roles.enforcement);
            try controller.pause() {} catch {}
        } else {
            vm.prank(roles.governance);
            try controller.unpause() {} catch {}
        }
    }

    function setPolicy(bool burnFrozen) external tracked("setPolicy") {
        vm.prank(roles.governance);
        try controller.setFrozenRedemptionPolicy(
            burnFrozen
                ? ISeriesController.FrozenRedemptionPolicy.BURN_AFTER_PAYOUT
                : ISeriesController.FrozenRedemptionPolicy.HOLD_UNTIL_RELEASED,
            keccak256("basis")
        ) {}
            catch {}
    }

    /// @notice Đẩy series sang bước kế tiếp theo đúng luồng, để chiến dịch fuzz đi tới các trạng thái sâu
    ///         (ACTIVE, MATURED, CLOSED) thay vì dừng ở đầu máy trạng thái.
    function advance(uint256 seed) external tracked("advance") {
        SeriesState s = controller.state();
        if (s == SeriesState.DEPLOYED) {
            vm.prank(roles.vsdc);
            try controller.setIsin("US0378331005") {} catch {}
        } else if (s == SeriesState.READY) {
            this.setAllocation(uint8(seed));
        } else if (s == SeriesState.ALLOCATED || s == SeriesState.ISSUING) {
            if (!batchAdded) this.addBatch();
            for (uint256 i = 0; i < allocIds.length; i++) {
                if (!controller.isMinted(allocIds[i])) {
                    this.mint(i);
                    return;
                }
            }
            this.attestRegister();
        } else if (s == SeriesState.ACTIVE || s == SeriesState.DEFAULTED) {
            uint16 last = controller.periodCount();
            if (!controller.snapshotOf(last).attested) this.snapshot(last, true);
            else this.mature();
        } else if (s == SeriesState.MATURED) {
            if (token.totalSupply() > 0) this.burnRedeemed();
            else this.close();
        }
    }

    // =====================================================================
    // Thao tác sai vai (I5): người gọi là nhà đầu tư hoặc một vai khác
    // =====================================================================

    function unauthorized(uint8 fn, uint256 actorSeed) external tracked("unauthorized") {
        address actor = _wrongActor(fn, actorSeed);
        bytes memory data = _callFor(fn);
        vm.prank(actor);
        (bool ok,) = address(controller).call(data);
        if (ok) unauthorizedSuccess = true;
    }

    function _callFor(uint8 fn) internal view returns (bytes memory) {
        uint8 f = fn % 12;
        if (f == 0) return abi.encodeCall(SeriesController.setIsin, ("US0378331005"));
        if (f == 1) {
            return abi.encodeCall(
                SeriesController.setAllocation,
                (bytes32(uint256(1)), bytes32(0), 1, new uint32[](controller.periodCount()))
            );
        }
        if (f == 2) return abi.encodeCall(SeriesController.addPaymentBatch, (keccak256("x"), keccak256("y")));
        if (f == 3) {
            return abi.encodeCall(SeriesController.correctionBurn, (allocIds[0], allocWallets[0], allocQty[0]));
        }
        if (f == 4) return abi.encodeCall(SeriesController.attestRegister, (bytes32(0), 0));
        if (f == 5) return abi.encodeCall(SeriesController.cancel, (bytes32(0)));
        if (f == 6) return abi.encodeCall(SeriesController.freeze, (holders[0], 1, bytes32(0)));
        if (f == 7) return abi.encodeCall(SeriesController.forcedTransfer, (holders[0], holders[1], 1, bytes32(0)));
        if (f == 8) return abi.encodeCall(SeriesController.mature, (bytes32(0)));
        if (f == 9) return abi.encodeCall(SeriesController.close, ());
        if (f == 10) return abi.encodeCall(SeriesController.pause, ());
        return abi.encodeCall(SeriesController.unpause, ());
    }

    /// @dev Chọn người gọi không phải vai đúng của hàm.
    function _wrongActor(uint8 fn, uint256 seed) internal view returns (address) {
        uint8 f = fn % 12;
        address right = f == 0 || f == 4
            ? roles.vsdc
            : f == 1
                ? roles.ubnd
                : f == 2
                    ? roles.bidv
                    : f == 3 || f == 8 || f == 9
                        ? roles.operator
                        : f == 6 || f == 7 || f == 10 ? roles.enforcement : roles.governance;
        address[7] memory pool =
            [roles.ubnd, roles.vsdc, roles.bidv, roles.operator, roles.enforcement, roles.governance, holders[0]];
        address a = pool[bound(seed, 0, pool.length - 1)];
        return a == right ? holders[1] : a;
    }

    function _anyReceiver(uint256 seed) internal view returns (address) {
        uint256 k = bound(seed, 0, holders.length + 1);
        if (k < holders.length) return holders[k];
        return k == holders.length ? unverified : ineligible;
    }

    function _amount(uint64 qty) internal view returns (uint256) {
        (uint256 a,) = controller.expectedAmount(qty);
        return a;
    }
}
