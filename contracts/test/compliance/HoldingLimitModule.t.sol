// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {ModuleTestBase} from "./ModuleTestBase.sol";
import {HoldingLimitModule} from "../../src/compliance/HoldingLimitModule.sol";

contract HoldingLimitModuleTest is ModuleTestBase {
    uint64 internal constant LIMIT = 100;
    uint8 internal constant BIT_CUSTODIAN = 1 << 1;
    uint8 internal constant BIT_PLATFORM = 1 << 2;

    event HoldingLimitConfigured(address indexed compliance, uint64 holdingLimit, uint8 exemptKinds);
    event ExemptKindsSet(address indexed compliance, uint8 exemptKinds);
    event WalletKindSet(address indexed compliance, address indexed wallet, HoldingLimitModule.WalletKind kind);

    HoldingLimitModule internal limitModule;
    address internal alice;
    address internal bob;
    address internal custodianOmnibus;
    address internal platformOmnibus;

    function setUp() public {
        _setUpSeries();
        limitModule = new HoldingLimitModule();
        _addModule(address(limitModule));
        _callModule(
            address(limitModule),
            abi.encodeCall(HoldingLimitModule.configure, (LIMIT, limitModule.DEFAULT_EXEMPT_KINDS()))
        );
        (alice,) = _investor("alice", CLASS_FOREIGN_INSTITUTION);
        (bob,) = _investor("bob", CLASS_FOREIGN_INSTITUTION);
        (custodianOmnibus,) = _investor("custodianOmnibus", CLASS_FOREIGN_INSTITUTION);
        (platformOmnibus,) = _investor("platformOmnibus", CLASS_FOREIGN_INSTITUTION);

        address[] memory wallets = new address[](2);
        HoldingLimitModule.WalletKind[] memory kinds = new HoldingLimitModule.WalletKind[](2);
        wallets[0] = custodianOmnibus;
        kinds[0] = HoldingLimitModule.WalletKind.CUSTODIAN_OMNIBUS;
        wallets[1] = platformOmnibus;
        kinds[1] = HoldingLimitModule.WalletKind.PLATFORM_OMNIBUS;
        _callModule(address(limitModule), abi.encodeCall(HoldingLimitModule.setWalletKinds, (wallets, kinds)));
    }

    function test_defaultMask_onlyCustodianOmnibus() public view {
        assertEq(limitModule.DEFAULT_EXEMPT_KINDS(), BIT_CUSTODIAN);
        HoldingLimitModule.Config memory c = limitModule.configOf(address(compliance));
        assertTrue(c.configured);
        assertEq(c.holdingLimit, LIMIT);
        assertEq(c.exemptKinds, BIT_CUSTODIAN);
    }

    function test_mint_upToLimitAllowed() public {
        _mint(alice, LIMIT);
        assertEq(token.balanceOf(alice), LIMIT);
    }

    function test_mint_overLimitRejected() public {
        _mint(alice, LIMIT - 1);
        vm.prank(agent);
        vm.expectRevert("Compliance not followed");
        token.mint(alice, 2);
    }

    function test_transfer_overLimitRejected() public {
        _mint(alice, 60);
        _mint(bob, 60);
        vm.prank(alice);
        vm.expectRevert("Transfer not possible");
        token.transfer(bob, 41);
        vm.prank(alice);
        token.transfer(bob, 40);
        assertEq(token.balanceOf(bob), LIMIT);
    }

    // ADR-0017 phương án (a): ví omnibus của lưu ký được miễn theo mặc định.
    function test_ADR0017_custodianOmnibusExemptByDefault() public {
        _mint(custodianOmnibus, LIMIT * 10);
        assertEq(token.balanceOf(custodianOmnibus), LIMIT * 10);
        assertTrue(limitModule.isExempt(address(compliance), custodianOmnibus));
    }

    // ADR-0017 phương án (b): ví của WeRWA chưa được miễn khi chưa bật cấu hình.
    function test_ADR0017_platformOmnibusNotExemptUntilEnabled() public {
        assertFalse(limitModule.isExempt(address(compliance), platformOmnibus));
        vm.prank(agent);
        vm.expectRevert("Compliance not followed");
        token.mint(platformOmnibus, LIMIT + 1);
    }

    // ADR-0017: bật (b) bằng cấu hình qua compliance (timelock), không phải triển khai lại.
    function test_ADR0017_enablePlatformOmnibusByConfig() public {
        vm.expectEmit(true, false, false, true, address(limitModule));
        emit ExemptKindsSet(address(compliance), BIT_CUSTODIAN | BIT_PLATFORM);
        _callModule(
            address(limitModule), abi.encodeCall(HoldingLimitModule.setExemptKinds, (BIT_CUSTODIAN | BIT_PLATFORM))
        );

        _mint(platformOmnibus, LIMIT * 5);
        assertEq(token.balanceOf(platformOmnibus), LIMIT * 5);
        assertTrue(limitModule.isExempt(address(compliance), custodianOmnibus));
    }

    // ADR-0017: chỉ bật (b), tắt (a).
    function test_ADR0017_platformOnlyMask() public {
        _callModule(address(limitModule), abi.encodeCall(HoldingLimitModule.setExemptKinds, (BIT_PLATFORM)));
        assertFalse(limitModule.isExempt(address(compliance), custodianOmnibus));
        assertTrue(limitModule.isExempt(address(compliance), platformOmnibus));
    }

    // ADR-0017: bit STANDARD và bit ngoài phạm vi không bao giờ được bật.
    function test_ADR0017_standardBitRejected() public {
        vm.prank(timelock);
        vm.expectRevert(abi.encodeWithSelector(HoldingLimitModule.InvalidExemptKinds.selector, uint8(1)));
        compliance.callModuleFunction(
            abi.encodeCall(HoldingLimitModule.setExemptKinds, (uint8(1))), address(limitModule)
        );

        vm.prank(timelock);
        vm.expectRevert(abi.encodeWithSelector(HoldingLimitModule.InvalidExemptKinds.selector, uint8(8)));
        compliance.callModuleFunction(
            abi.encodeCall(HoldingLimitModule.configure, (LIMIT, uint8(8))), address(limitModule)
        );
    }

    function test_walletKind_removeOmnibusRestoresLimit() public {
        vm.expectEmit(true, true, false, true, address(limitModule));
        emit WalletKindSet(address(compliance), custodianOmnibus, HoldingLimitModule.WalletKind.STANDARD);
        _callModule(
            address(limitModule),
            abi.encodeCall(HoldingLimitModule.setWalletKind, (custodianOmnibus, HoldingLimitModule.WalletKind.STANDARD))
        );
        assertEq(uint8(limitModule.walletKindOf(address(compliance), custodianOmnibus)), 0);
        vm.prank(agent);
        vm.expectRevert("Compliance not followed");
        token.mint(custodianOmnibus, LIMIT + 1);
    }

    function test_zeroLimitMeansUnlimited() public {
        _callModule(address(limitModule), abi.encodeCall(HoldingLimitModule.configure, (uint64(0), BIT_CUSTODIAN)));
        _mint(alice, 1_000_000);
        assertEq(token.balanceOf(alice), 1_000_000);
    }

    function test_selfTransfer_notBlockedAtLimit() public {
        _mint(alice, LIMIT);
        assertTrue(limitModule.moduleCheck(alice, alice, 1, address(compliance)));
    }

    function test_unconfigured_failsClosed() public {
        address other = makeAddr("otherCompliance");
        assertFalse(limitModule.moduleCheck(address(0), alice, 1, other));
        assertFalse(limitModule.configOf(other).configured);
    }

    function test_setExemptKinds_requiresConfigured() public {
        HoldingLimitModule fresh = new HoldingLimitModule();
        _addModule(address(fresh));
        vm.prank(timelock);
        vm.expectRevert(HoldingLimitModule.NotConfigured.selector);
        compliance.callModuleFunction(
            abi.encodeCall(HoldingLimitModule.setExemptKinds, (BIT_CUSTODIAN)), address(fresh)
        );
    }

    function test_setWalletKinds_lengthMismatch() public {
        address[] memory wallets = new address[](1);
        HoldingLimitModule.WalletKind[] memory kinds = new HoldingLimitModule.WalletKind[](2);
        wallets[0] = alice;
        vm.prank(timelock);
        vm.expectRevert(HoldingLimitModule.LengthMismatch.selector);
        compliance.callModuleFunction(
            abi.encodeCall(HoldingLimitModule.setWalletKinds, (wallets, kinds)), address(limitModule)
        );
    }

    function test_setWalletKind_rejectsZero() public {
        vm.prank(timelock);
        vm.expectRevert(HoldingLimitModule.ZeroAddress.selector);
        compliance.callModuleFunction(
            abi.encodeCall(
                HoldingLimitModule.setWalletKind, (address(0), HoldingLimitModule.WalletKind.CUSTODIAN_OMNIBUS)
            ),
            address(limitModule)
        );
    }

    function test_configure_emits() public {
        vm.expectEmit(true, false, false, true, address(limitModule));
        emit HoldingLimitConfigured(address(compliance), 50, BIT_CUSTODIAN);
        _callModule(address(limitModule), abi.encodeCall(HoldingLimitModule.configure, (uint64(50), BIT_CUSTODIAN)));
    }

    // US-4.01-AC3: đổi tham số chỉ qua multisig 3 bên (chủ compliance).
    function test_US_4_01_AC3_configOnlyThroughCompliance() public {
        vm.expectRevert("only bound compliance can call");
        limitModule.configure(1, 0);
        vm.expectRevert("only bound compliance can call");
        limitModule.setExemptKinds(BIT_PLATFORM);
        vm.expectRevert("only bound compliance can call");
        limitModule.setWalletKind(alice, HoldingLimitModule.WalletKind.PLATFORM_OMNIBUS);

        vm.prank(alice);
        vm.expectRevert("Ownable: caller is not the owner");
        compliance.callModuleFunction(
            abi.encodeCall(HoldingLimitModule.setExemptKinds, (BIT_PLATFORM)), address(limitModule)
        );
    }

    function testFuzz_limitRespected(uint64 limit, uint64 existing, uint64 incoming) public {
        limit = uint64(bound(limit, 1, type(uint32).max));
        existing = uint64(bound(existing, 0, limit));
        incoming = uint64(bound(incoming, 1, type(uint32).max));
        _callModule(address(limitModule), abi.encodeCall(HoldingLimitModule.configure, (limit, BIT_CUSTODIAN)));
        if (existing > 0) _mint(alice, existing);
        bool expected = uint256(existing) + incoming <= limit;
        assertEq(limitModule.moduleCheck(address(0), alice, incoming, address(compliance)), expected);
    }

    function test_metadata() public view {
        assertEq(limitModule.name(), "HoldingLimitModule");
        assertTrue(limitModule.isPlugAndPlay());
        assertTrue(limitModule.canComplianceBind(address(compliance)));
    }
}
