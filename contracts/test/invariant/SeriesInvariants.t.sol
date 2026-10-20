// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {PlatformFixture} from "../utils/PlatformFixture.sol";
import {console2} from "forge-std/console2.sol";
import {SeriesHandler} from "./SeriesHandler.sol";
import {Token} from "@trex/token/Token.sol";
import {SeriesController} from "../../src/SeriesController.sol";
import {SeriesFactory} from "../../src/factory/SeriesFactory.sol";
import {SeriesState} from "../../src/interfaces/ISeriesState.sol";

/// @title Bất biến I1–I8 của series (docs/design/04-hop-dong-thong-minh.md, mục "Kiểm thử bất biến")
/// @notice Chạy trên bộ hợp đồng thật do SeriesFactory triển khai. Handler gọi ngẫu nhiên mọi thao tác,
///         bằng đúng vai và sai vai, kể cả mint giả, chuyển khi bị khóa, chuyển tới ví chưa xác minh.
contract SeriesInvariantsTest is PlatformFixture {
    SeriesHandler internal handler;
    SeriesController internal controller;
    Token internal token;

    function setUp() public {
        _deployPlatform();
        SeriesFactory.SeriesRecord memory r = _deploySeriesViaFactory(keccak256("series-inv"), "INV");
        controller = SeriesController(r.controller);
        token = Token(r.token);

        address[] memory holders = new address[](5);
        holders[0] = _investor("h0", CLASS_FOREIGN_INSTITUTION);
        holders[1] = _investor("h1", CLASS_VIFC_MEMBER);
        holders[2] = _investor("h2", CLASS_FOREIGN_INSTITUTION);
        holders[3] = _investor("h3", CLASS_VIFC_MEMBER);
        holders[4] = _investor("h4", CLASS_FOREIGN_INSTITUTION);
        address ineligible = _investor("ineligible", CLASS_NOT_ELIGIBLE);

        handler = new SeriesHandler(
            controller,
            identityRegistry,
            SeriesHandler.Roles(ubnd, vsdc, bidv, holcoOperator, enforcement2of2, governance3of3),
            holders,
            makeAddr("unverified"),
            ineligible
        );
        targetContract(address(handler));
    }

    /// @dev Ghi trạng thái cuối mỗi lượt để kiểm chiến dịch có đi tới các trạng thái sâu (chạy với -vv).
    function afterInvariant() external view {
        console2.log("final-state", uint8(controller.state()));
    }

    /// I1 — totalMinted ≤ totalAllocated ≤ maxQuantity (R2).
    function invariant_I1_mintedWithinAllocatedWithinMax() public view {
        assertLe(controller.totalMinted(), controller.totalAllocated());
        assertLe(controller.totalAllocated(), controller.maxQuantity());
    }

    /// I2 — mỗi allocationId có tối đa một lần mint còn hiệu lực (R3); khớp trạng thái trên chuỗi.
    function invariant_I2_atMostOneLiveMintPerAllocation() public view {
        for (uint256 i = 0; i < handler.allocCount(); i++) {
            bytes32 id = handler.allocIdAt(i);
            int256 n = handler.netMints(id);
            assertTrue(n == 0 || n == 1);
            assertEq(n == 1, controller.isMinted(id));
        }
        assertFalse(handler.forgedMintSuccess());
    }

    /// I3 — tổng cung của token bằng totalMinted trừ số đã hủy khi đáo hạn (R4).
    function invariant_I3_supplyEqualsMintedMinusRedeemed() public view {
        assertEq(token.totalSupply(), uint256(controller.totalMinted()) - controller.totalRedeemed());
    }

    /// I4 — không chuyển nhượng nào thành công khi không ở ACTIVE/DEFAULTED hoặc chưa có xác nhận sổ của VSDC.
    function invariant_I4_noTransferWhileLocked() public view {
        assertFalse(handler.badTransfer());
    }

    /// I5 — trạng thái chỉ đổi theo cạnh của máy Series; hàm đổi trạng thái chỉ chạy với đúng vai.
    function invariant_I5_onlyDiagramEdgesAndRightRoles() public view {
        assertFalse(handler.badEdge());
        assertFalse(handler.unauthorizedSuccess());
    }

    /// I6 — số lượng phong tỏa của mỗi ví không vượt số dư của ví đó.
    function invariant_I6_frozenWithinBalance() public view {
        for (uint256 i = 0; i < handler.holdersLength(); i++) {
            address h = handler.holders(i);
            assertLe(token.getFrozenTokens(h), token.balanceOf(h));
        }
    }

    /// I7 — không ví chưa xác minh nào nhận được trái phiếu, kể cả qua chuyển cưỡng chế.
    function invariant_I7_onlyVerifiedReceive() public view {
        assertFalse(handler.unverifiedReceiver());
        assertEq(token.balanceOf(handler.unverified()), 0);
    }

    /// I8 — CANCELLED chỉ đạt được khi totalMinted = 0 (và chưa từng mint).
    function invariant_I8_cancelledOnlyWithoutMint() public view {
        if (controller.state() == SeriesState.CANCELLED) {
            assertEq(controller.totalMinted(), 0);
            assertEq(handler.everMinted(), 0);
        }
    }
}
