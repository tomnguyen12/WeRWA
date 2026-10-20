// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {PlatformFixture} from "./utils/PlatformFixture.sol";
import {MerkleHelper} from "./utils/MerkleHelper.sol";
import {Token} from "@trex/token/Token.sol";
import {ModularCompliance} from "@trex/compliance/modular/ModularCompliance.sol";
import {
    TransparentUpgradeableProxy,
    ITransparentUpgradeableProxy
} from "@openzeppelin/contracts/proxy/transparent/TransparentUpgradeableProxy.sol";
import {UpgradeableBeacon} from "@openzeppelin/contracts/proxy/beacon/UpgradeableBeacon.sol";
import {ProxyAdmin} from "@openzeppelin/contracts/proxy/transparent/ProxyAdmin.sol";

import {SeriesController} from "../src/SeriesController.sol";
import {ISeriesController} from "../src/ISeriesController.sol";
import {SeriesState} from "../src/interfaces/ISeriesState.sol";
import {SeriesLeaves} from "../src/libraries/SeriesLeaves.sol";
import {SeriesFactory} from "../src/factory/SeriesFactory.sol";
import {WeRWATimelock} from "../src/governance/WeRWATimelock.sol";
import {TREXSuiteDeployer} from "../src/adapters/trex/TREXSuiteDeployer.sol";
import {InvestorClassModule} from "../src/compliance/InvestorClassModule.sol";
import {HoldingLimitModule} from "../src/compliance/HoldingLimitModule.sol";
import {TransferGateModule} from "../src/compliance/TransferGateModule.sol";

/// @dev Bản nâng cấp thử của SeriesController: thêm một hàm đọc, không đổi bố cục lưu trữ.
contract SeriesControllerV2 is SeriesController {
    function version() external pure returns (string memory) {
        return "2";
    }
}

/// @dev Bản nâng cấp thử của SeriesFactory.
contract SeriesFactoryV2 is SeriesFactory {
    function version() external pure returns (string memory) {
        return "2";
    }
}

contract SeriesFactoryTest is PlatformFixture {
    event SeriesCreated(
        bytes32 indexed seriesId,
        address indexed controller,
        address indexed token,
        address compliance,
        address investorClassModule,
        address holdingLimitModule,
        address transferGateModule
    );

    bytes32 internal constant SERIES_A = keccak256("series-2027A");
    bytes32 internal constant SERIES_B = keccak256("series-2027B");

    function setUp() public {
        _deployPlatform();
    }

    // =====================================================================
    // Triển khai series
    // =====================================================================

    // US-2.07-AC1: UBND, VSDC, Holco ký 3/3 thì series sang Đã triển khai, lưu địa chỉ hợp đồng.
    function test_US_2_07_AC1_deploySeriesStoresAddresses() public {
        SeriesFactory.SeriesRecord memory r = _deploySeriesViaFactory(SERIES_A, "HCMC27A");

        SeriesFactory.SeriesRecord memory got = factory.seriesOf(SERIES_A);
        assertEq(got.controller, r.controller);
        assertEq(got.token, r.token);
        assertEq(got.compliance, r.compliance);
        assertEq(factory.seriesCount(), 1);
        assertEq(factory.seriesIdAt(0), SERIES_A);

        SeriesController c = SeriesController(r.controller);
        assertEq(uint8(c.state()), uint8(SeriesState.DEPLOYED));
        assertEq(address(c.token()), r.token);
        assertEq(c.seriesId(), SERIES_A);
    }

    function test_deploySeries_emitsSeriesCreated() public {
        ISeriesController.SeriesTerms memory t = _terms(SERIES_A);
        SeriesFactory.TokenConfig memory cfg = _tokenConfig("HCMC27A");
        vm.expectEmit(true, false, false, false, address(factory));
        emit SeriesCreated(SERIES_A, address(0), address(0), address(0), address(0), address(0), address(0));
        vm.prank(governance3of3);
        factory.deploySeries(t, cfg);
    }

    // ADR-04 và ADR-05: controller là agent duy nhất; factory không giữ quyền nào; token, compliance thuộc timelock.
    function test_deploySeries_rolesAndOwnership() public {
        SeriesFactory.SeriesRecord memory r = _deploySeriesViaFactory(SERIES_A, "HCMC27A");
        Token token = Token(r.token);
        ModularCompliance compliance = ModularCompliance(r.compliance);

        assertTrue(token.isAgent(r.controller));
        assertFalse(token.isAgent(address(factory)));
        assertEq(token.owner(), address(timelock));
        assertEq(compliance.owner(), address(timelock));
        assertEq(compliance.getTokenBound(), r.token);
        assertEq(address(token.identityRegistry()), address(identityRegistry)); // sổ định danh dùng chung
        assertEq(token.decimals(), 0);
        assertEq(token.symbol(), "HCMC27A");
        assertFalse(token.paused()); // C16
    }

    // US-4.01-AC1: tham số module lấy đúng từ điều khoản, không nhập tay.
    function test_US_4_01_AC1_modulesConfiguredFromTerms() public {
        SeriesFactory.SeriesRecord memory r = _deploySeriesViaFactory(SERIES_A, "HCMC27A");
        address[] memory mods = ModularCompliance(r.compliance).getModules();
        assertEq(mods.length, 3);
        assertEq(mods[0], r.investorClassModule);
        assertEq(mods[1], r.holdingLimitModule);
        assertEq(mods[2], r.transferGateModule);

        uint16[] memory classes = InvestorClassModule(r.investorClassModule).investorClassesOf(r.compliance);
        assertEq(classes.length, 2);
        assertEq(classes[0], CLASS_FOREIGN_INSTITUTION);
        HoldingLimitModule.Config memory hc = HoldingLimitModule(r.holdingLimitModule).configOf(r.compliance);
        assertEq(hc.holdingLimit, HOLDING_LIMIT);
        assertEq(hc.exemptKinds, 1 << 1); // ADR-0017 mặc định
        assertEq(TransferGateModule(r.transferGateModule).seriesControllerOf(r.compliance), r.controller);
        assertEq(InvestorClassModule(r.investorClassModule).kycTopic(), TOPIC_KYC_INSTITUTION);
    }

    // ADR-0017: danh sách ví omnibus và mặt nạ ban đầu đặt qua factory.
    function test_ADR0017_initialOmnibusListViaFactory() public {
        address custodian = makeAddr("custodian omnibus");
        address platform = makeAddr("platform omnibus");
        SeriesFactory.TokenConfig memory cfg = _tokenConfig("HCMC27A");
        cfg.exemptKinds = (1 << 1) | (1 << 2);
        cfg.omnibusWallets = new address[](2);
        cfg.omnibusKinds = new HoldingLimitModule.WalletKind[](2);
        cfg.omnibusWallets[0] = custodian;
        cfg.omnibusKinds[0] = HoldingLimitModule.WalletKind.CUSTODIAN_OMNIBUS;
        cfg.omnibusWallets[1] = platform;
        cfg.omnibusKinds[1] = HoldingLimitModule.WalletKind.PLATFORM_OMNIBUS;
        ISeriesController.SeriesTerms memory t = _terms(SERIES_A);
        vm.prank(governance3of3);
        SeriesFactory.SeriesRecord memory r = factory.deploySeries(t, cfg);

        HoldingLimitModule hl = HoldingLimitModule(r.holdingLimitModule);
        assertTrue(hl.isExempt(r.compliance, custodian));
        assertTrue(hl.isExempt(r.compliance, platform));
        assertEq(hl.configOf(r.compliance).exemptKinds, (1 << 1) | (1 << 2));
    }

    function test_deploySeries_omnibusLengthMismatchReverts() public {
        SeriesFactory.TokenConfig memory cfg = _tokenConfig("HCMC27A");
        cfg.omnibusWallets = new address[](1);
        cfg.omnibusWallets[0] = makeAddr("w");
        ISeriesController.SeriesTerms memory t = _terms(SERIES_A);
        vm.prank(governance3of3);
        vm.expectRevert(HoldingLimitModule.LengthMismatch.selector);
        factory.deploySeries(t, cfg);
    }

    function test_deploySeries_onlyGovernance() public {
        ISeriesController.SeriesTerms memory t = _terms(SERIES_A);
        SeriesFactory.TokenConfig memory cfg = _tokenConfig("HCMC27A");
        vm.prank(holco);
        vm.expectRevert(SeriesFactory.Unauthorized.selector);
        factory.deploySeries(t, cfg);
    }

    function test_deploySeries_governanceRoleMustMatch() public {
        ISeriesController.SeriesTerms memory t = _terms(SERIES_A);
        t.governance = holco;
        SeriesFactory.TokenConfig memory cfg = _tokenConfig("HCMC27A");
        vm.prank(governance3of3);
        vm.expectRevert(SeriesFactory.RoleMismatch.selector);
        factory.deploySeries(t, cfg);
    }

    function test_deploySeries_onceperSeriesId() public {
        _deploySeriesViaFactory(SERIES_A, "HCMC27A");
        ISeriesController.SeriesTerms memory t = _terms(SERIES_A);
        SeriesFactory.TokenConfig memory cfg = _tokenConfig("HCMC27A");
        vm.prank(governance3of3);
        vm.expectRevert(SeriesFactory.SeriesExists.selector);
        factory.deploySeries(t, cfg);
    }

    // ADR-04: lỗi ở một series không ảnh hưởng series khác — bộ hợp đồng và module tách riêng.
    function test_ADR04_seriesAreIsolated() public {
        SeriesFactory.SeriesRecord memory a = _deploySeriesViaFactory(SERIES_A, "HCMC27A");
        SeriesFactory.SeriesRecord memory b = _deploySeriesViaFactory(SERIES_B, "HCMC27B");
        assertTrue(a.token != b.token && a.compliance != b.compliance && a.controller != b.controller);
        assertTrue(a.investorClassModule != b.investorClassModule);
        assertTrue(a.holdingLimitModule != b.holdingLimitModule);
        assertTrue(a.transferGateModule != b.transferGateModule);

        vm.prank(governance3of3);
        SeriesController(a.controller).cancel(keccak256("cancel A"));
        assertEq(uint8(SeriesController(b.controller).state()), uint8(SeriesState.DEPLOYED));
    }

    // Luồng trọn vẹn trên bộ hợp đồng do factory triển khai: ISIN → phân bổ → lô BIDV → mint → xác nhận sổ → chuyển.
    function test_endToEnd_issuanceOnFactorySeries() public {
        SeriesFactory.SeriesRecord memory r = _deploySeriesViaFactory(SERIES_A, "HCMC27A");
        SeriesController c = SeriesController(r.controller);
        address alice = _investor("alice", CLASS_FOREIGN_INSTITUTION);
        address bob = _investor("bob", CLASS_VIFC_MEMBER);

        bytes32 id = keccak256("alloc-1");
        bytes32 ref = keccak256("BIDV-REF-1");
        bytes32[] memory leaves = new bytes32[](1);
        leaves[0] = SeriesLeaves.allocationLeaf(r.controller, id, alice, 10);
        bytes32[] memory pleaves = new bytes32[](1);
        pleaves[0] = SeriesLeaves.paymentLeaf(r.controller, id, 10 * 99_500, ref);

        vm.prank(vsdc);
        c.setIsin("US0378331005");
        uint32[] memory rates = new uint32[](PERIODS);
        for (uint256 i = 0; i < PERIODS; i++) {
            rates[i] = 51_250;
        }
        vm.prank(ubnd);
        c.setAllocation(MerkleHelper.root(leaves), keccak256("list"), 10, rates);
        vm.prank(bidv);
        c.addPaymentBatch(MerkleHelper.root(pleaves), keccak256("batch"));
        vm.prank(holcoOperator);
        c.mint(
            ISeriesController.MintItem(id, alice, 10, 10 * 99_500, ref),
            new bytes32[](0),
            MerkleHelper.root(pleaves),
            new bytes32[](0)
        );
        vm.roll(block.number + 1);
        vm.prank(vsdc);
        c.attestRegister(keccak256("register"), uint64(block.number - 1));

        vm.prank(alice);
        Token(r.token).transfer(bob, 4);
        assertEq(Token(r.token).balanceOf(bob), 4);
    }

    // =====================================================================
    // Timelock và nâng cấp (6.05)
    // =====================================================================

    function test_timelock_roles() public view {
        assertTrue(timelock.hasRole(timelock.PROPOSER_ROLE(), governance3of3));
        assertTrue(timelock.hasRole(timelock.CANCELLER_ROLE(), ubnd));
        assertTrue(timelock.hasRole(timelock.CANCELLER_ROLE(), vsdc));
        assertTrue(timelock.hasRole(timelock.CANCELLER_ROLE(), holco));
        assertTrue(timelock.hasRole(timelock.EXECUTOR_ROLE(), address(0))); // ai cũng thực thi (C15)
        assertFalse(timelock.hasRole(timelock.PROPOSER_ROLE(), holco));
        assertFalse(timelock.hasRole(timelock.TIMELOCK_ADMIN_ROLE(), address(this)));
        assertEq(timelock.getMinDelay(), TIMELOCK_DELAY);
    }

    function test_timelock_constructorRejectsZero() public {
        address[] memory cancellers = new address[](1);
        vm.expectRevert(WeRWATimelock.ZeroAddress.selector);
        new WeRWATimelock(1, governance3of3, cancellers);
        vm.expectRevert(WeRWATimelock.ZeroAddress.selector);
        new WeRWATimelock(1, address(0), new address[](0));
    }

    // Nâng cấp SeriesController qua beacon: 3/3 lên lịch, chờ, thực thi; series đang có giữ nguyên trạng thái.
    function test_upgradeController_viaTimelockKeepsState() public {
        SeriesFactory.SeriesRecord memory r = _deploySeriesViaFactory(SERIES_A, "HCMC27A");
        vm.prank(vsdc);
        SeriesController(r.controller).setIsin("US0378331005");

        address v2 = address(new SeriesControllerV2());
        _viaTimelock(address(beacon), abi.encodeCall(UpgradeableBeacon.upgradeTo, (v2)), keccak256("upgrade-1"));

        assertEq(beacon.implementation(), v2);
        assertEq(SeriesControllerV2(r.controller).version(), "2");
        assertEq(SeriesController(r.controller).isin(), bytes12("US0378331005"));
        assertEq(uint8(SeriesController(r.controller).state()), uint8(SeriesState.READY));
    }

    function test_upgrade_beforeDelayRejected() public {
        address v2 = address(new SeriesControllerV2());
        bytes memory data = abi.encodeCall(UpgradeableBeacon.upgradeTo, (v2));
        vm.prank(governance3of3);
        timelock.schedule(address(beacon), 0, data, bytes32(0), keccak256("s"), TIMELOCK_DELAY);
        vm.warp(block.timestamp + TIMELOCK_DELAY - 1);
        vm.expectRevert("TimelockController: operation is not ready");
        timelock.execute(address(beacon), 0, data, bytes32(0), keccak256("s"));
    }

    // Safe của từng tổ chức hủy được lịch đã đặt.
    function test_upgrade_cancelledByOrganisationSafe() public {
        address v2 = address(new SeriesControllerV2());
        bytes memory data = abi.encodeCall(UpgradeableBeacon.upgradeTo, (v2));
        vm.prank(governance3of3);
        timelock.schedule(address(beacon), 0, data, bytes32(0), keccak256("s"), TIMELOCK_DELAY);
        bytes32 id = timelock.hashOperation(address(beacon), 0, data, bytes32(0), keccak256("s"));

        vm.prank(vsdc);
        timelock.cancel(id);
        assertFalse(timelock.isOperation(id));
        vm.warp(block.timestamp + TIMELOCK_DELAY);
        vm.expectRevert("TimelockController: operation is not ready");
        timelock.execute(address(beacon), 0, data, bytes32(0), keccak256("s"));
    }

    function test_upgrade_onlyProposerSchedules_andDirectUpgradeBlocked() public {
        address v2 = address(new SeriesControllerV2());
        bytes memory data = abi.encodeCall(UpgradeableBeacon.upgradeTo, (v2));
        vm.prank(holco);
        vm.expectRevert();
        timelock.schedule(address(beacon), 0, data, bytes32(0), keccak256("s"), TIMELOCK_DELAY);

        vm.prank(governance3of3);
        vm.expectRevert("Ownable: caller is not the owner");
        beacon.upgradeTo(v2);
    }

    function test_upgradeFactory_viaProxyAdminAndTimelock() public {
        _deploySeriesViaFactory(SERIES_A, "HCMC27A");
        address v2 = address(new SeriesFactoryV2());
        _viaTimelock(
            address(proxyAdmin),
            abi.encodeCall(ProxyAdmin.upgrade, (ITransparentUpgradeableProxy(address(factory)), v2)),
            keccak256("factory-upgrade")
        );
        assertEq(SeriesFactoryV2(address(factory)).version(), "2");
        assertEq(factory.seriesCount(), 1);
        assertEq(factory.governance(), governance3of3);
    }

    // US-4.01-AC3: đổi tham số module của series đang có chỉ qua timelock (chủ compliance).
    function test_US_4_01_AC3_moduleParamChangeViaTimelock() public {
        SeriesFactory.SeriesRecord memory r = _deploySeriesViaFactory(SERIES_A, "HCMC27A");
        bytes memory inner = abi.encodeCall(HoldingLimitModule.configure, (uint64(50), uint8(1 << 1)));
        bytes memory data = abi.encodeCall(ModularCompliance.callModuleFunction, (inner, r.holdingLimitModule));

        vm.prank(governance3of3);
        vm.expectRevert("Ownable: caller is not the owner");
        ModularCompliance(r.compliance).callModuleFunction(inner, r.holdingLimitModule);

        _viaTimelock(r.compliance, data, keccak256("limit-50"));
        assertEq(HoldingLimitModule(r.holdingLimitModule).configOf(r.compliance).holdingLimit, 50);
    }

    // =====================================================================
    // Quản trị factory
    // =====================================================================

    function test_setGovernance_onlyTimelock() public {
        address newGov = makeAddr("new 3/3");
        vm.prank(governance3of3);
        vm.expectRevert(SeriesFactory.Unauthorized.selector);
        factory.setGovernance(newGov);

        _viaTimelock(address(factory), abi.encodeCall(SeriesFactory.setGovernance, (newGov)), keccak256("gov"));
        assertEq(factory.governance(), newGov);

        vm.prank(address(timelock));
        vm.expectRevert(SeriesFactory.ZeroAddress.selector);
        factory.setGovernance(address(0));
    }

    function test_factory_implementationCannotBeInitialized() public {
        SeriesFactory impl = new SeriesFactory();
        vm.expectRevert("Initializable: contract is already initialized");
        impl.initialize(address(1), address(1), address(1), address(1), address(1), address(1));
    }

    function test_factory_initializeOnceAndZeroChecks() public {
        vm.expectRevert("Initializable: contract is already initialized");
        factory.initialize(address(1), address(1), address(1), address(1), address(1), address(1));

        SeriesFactory impl = new SeriesFactory();
        ProxyAdmin pa = new ProxyAdmin();
        vm.expectRevert(SeriesFactory.ZeroAddress.selector);
        new TransparentUpgradeableProxy(
            address(impl),
            address(pa),
            abi.encodeCall(
                SeriesFactory.initialize, (address(0), address(1), address(1), address(1), address(1), address(1))
            )
        );
    }

    function test_factory_storedConfig() public view {
        assertEq(factory.governance(), governance3of3);
        assertEq(factory.timelock(), address(timelock));
        assertEq(address(factory.tokenDeployer()), address(tokenDeployer));
        assertEq(tokenDeployer.implementationAuthority(), address(ia));
        assertEq(factory.identityRegistry(), address(identityRegistry));
        assertEq(factory.controllerBeacon(), address(beacon));
        assertEq(address(factory.moduleDeployer()), address(moduleDeployer));
    }

    function test_trexSuiteDeployer_rejectsZeroAuthority() public {
        vm.expectRevert(TREXSuiteDeployer.ZeroAddress.selector);
        new TREXSuiteDeployer(address(0));
    }

    // ADR-0019: bộ tạo token chuyển quyền sở hữu cho người gọi; ai gọi cũng chỉ nhận bộ token của mình.
    function test_trexSuiteDeployer_handsOwnershipToCaller() public {
        address caller = makeAddr("anyone");
        vm.prank(caller);
        (address t, address c) = tokenDeployer.deployTokenSet(address(identityRegistry), "X", "X");
        assertEq(Token(t).owner(), caller);
        assertEq(ModularCompliance(c).owner(), caller);
        assertEq(ModularCompliance(c).getTokenBound(), t);
    }
}
