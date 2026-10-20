// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {TREXSuite} from "./TREXSuite.sol";
import {UpgradeableBeacon} from "@openzeppelin/contracts/proxy/beacon/UpgradeableBeacon.sol";
import {TransparentUpgradeableProxy} from "@openzeppelin/contracts/proxy/transparent/TransparentUpgradeableProxy.sol";
import {ProxyAdmin} from "@openzeppelin/contracts/proxy/transparent/ProxyAdmin.sol";

import {SeriesController} from "../../src/SeriesController.sol";
import {ISeriesController} from "../../src/ISeriesController.sol";
import {SeriesFactory} from "../../src/factory/SeriesFactory.sol";
import {ModuleDeployer} from "../../src/factory/ModuleDeployer.sol";
import {WeRWATimelock} from "../../src/governance/WeRWATimelock.sol";
import {TREXSuiteDeployer} from "../../src/adapters/trex/TREXSuiteDeployer.sol";
import {HoldingLimitModule} from "../../src/compliance/HoldingLimitModule.sol";

/// @notice Dựng toàn nền tảng như script triển khai: bộ T-REX dùng chung, timelock, beacon của SeriesController,
///         ModuleDeployer, SeriesFactory sau TransparentUpgradeableProxy. Sau khi dựng, mọi quyền sở hữu thuộc timelock.
///         Các Safe được thay bằng địa chỉ thử.
abstract contract PlatformFixture is TREXSuite {
    uint16 internal constant CLASS_FOREIGN_INSTITUTION = 1;
    uint16 internal constant CLASS_VIFC_MEMBER = 2;
    uint16 internal constant CLASS_NOT_ELIGIBLE = 9;
    uint256 internal constant TIMELOCK_DELAY = 1 hours; // C15

    // Safe của từng tổ chức và multisig liên bên (ADR-05).
    address internal ubnd = makeAddr("UBND Safe");
    address internal vsdc = makeAddr("VSDC Safe");
    address internal holco = makeAddr("Holco Safe");
    address internal bidv = makeAddr("BIDV Safe");
    address internal holcoOperator = makeAddr("Holco operator key");
    address internal enforcement2of2 = makeAddr("VSDC+Holco 2/2");
    address internal governance3of3 = makeAddr("UBND+VSDC+Holco 3/3");

    WeRWATimelock internal timelock;
    UpgradeableBeacon internal beacon;
    ModuleDeployer internal moduleDeployer;
    ProxyAdmin internal proxyAdmin;
    SeriesFactory internal factory;
    TREXSuiteDeployer internal tokenDeployer;

    // Điều khoản mẫu.
    uint256 internal constant FACE_VALUE_MINOR = 100_000;
    uint32 internal constant ISSUE_PRICE_PPM = 995_000;
    uint64 internal constant MAX_QUANTITY = 1_000_000;
    uint16 internal constant PERIODS = 10;
    uint32 internal constant RATE_FLOOR = 40_000;
    uint32 internal constant RATE_CAP = 60_000;
    uint64 internal constant HOLDING_LIMIT = 200_000;

    function _deployPlatform() internal {
        _deployTREXSuite();

        address[] memory cancellers = new address[](3);
        cancellers[0] = ubnd;
        cancellers[1] = vsdc;
        cancellers[2] = holco;
        timelock = new WeRWATimelock(TIMELOCK_DELAY, governance3of3, cancellers);

        beacon = new UpgradeableBeacon(address(new SeriesController()));
        beacon.transferOwnership(address(timelock));

        moduleDeployer = new ModuleDeployer(TOPIC_KYC_INSTITUTION, TOPIC_INVESTOR_CLASS);
        // ADR-0019 phương án A: bộ tạo token T-REX. Phương án B thay bằng bộ tạo của bản tự viết.
        tokenDeployer = new TREXSuiteDeployer(address(ia));

        proxyAdmin = new ProxyAdmin();
        proxyAdmin.transferOwnership(address(timelock));
        factory = SeriesFactory(
            address(
                new TransparentUpgradeableProxy(
                    address(new SeriesFactory()),
                    address(proxyAdmin),
                    abi.encodeCall(
                        SeriesFactory.initialize,
                        (
                            governance3of3,
                            address(timelock),
                            address(tokenDeployer),
                            address(identityRegistry),
                            address(beacon),
                            address(moduleDeployer)
                        )
                    )
                )
            )
        );

        // Bộ T-REX dùng chung giao cho timelock.
        vm.startPrank(trexOwner);
        ia.transferOwnership(address(timelock));
        identityRegistry.transferOwnership(address(timelock));
        claimTopics.transferOwnership(address(timelock));
        trustedIssuers.transferOwnership(address(timelock));
        identityStorage.transferOwnership(address(timelock));
        vm.stopPrank();
    }

    function _terms(bytes32 seriesId) internal view returns (ISeriesController.SeriesTerms memory t) {
        uint16[] memory classes = new uint16[](2);
        classes[0] = CLASS_FOREIGN_INSTITUTION;
        classes[1] = CLASS_VIFC_MEMBER;
        t = ISeriesController.SeriesTerms({
            seriesId: seriesId,
            termsHash: sha256(abi.encode("term sheet", seriesId)),
            currency: "USD",
            faceValueMinor: FACE_VALUE_MINOR,
            issuePricePpm: ISSUE_PRICE_PPM,
            maxQuantity: MAX_QUANTITY,
            rateFloorPpm: RATE_FLOOR,
            rateCapPpm: RATE_CAP,
            periodCount: PERIODS,
            maturityDate: uint64(block.timestamp + 5 * 365 days),
            investorClasses: classes,
            holdingLimit: HOLDING_LIMIT,
            issuer: ubnd,
            registrar: vsdc,
            payingAgent: bidv,
            operator: holcoOperator,
            enforcement: enforcement2of2,
            governance: governance3of3
        });
    }

    function _tokenConfig(string memory symbol) internal pure returns (SeriesFactory.TokenConfig memory c) {
        c.name = string.concat("WeRWA ", symbol);
        c.symbol = symbol;
        c.exemptKinds = 0;
        c.omnibusWallets = new address[](0);
        c.omnibusKinds = new HoldingLimitModule.WalletKind[](0);
    }

    function _deploySeriesViaFactory(bytes32 seriesId, string memory symbol)
        internal
        returns (SeriesFactory.SeriesRecord memory r)
    {
        ISeriesController.SeriesTerms memory t = _terms(seriesId);
        SeriesFactory.TokenConfig memory c = _tokenConfig(symbol);
        vm.prank(governance3of3);
        r = factory.deploySeries(t, c);
    }

    function _investor(string memory label, uint16 classCode) internal returns (address wallet) {
        wallet = makeAddr(label);
        _onboardInvestor(
            wallet,
            abi.encode(uint64(block.timestamp + 10 * 365 days)),
            abi.encode(classCode, uint64(block.timestamp + 10 * 365 days))
        );
    }

    /// @notice Lên lịch và thực thi một lời gọi qua timelock (3/3 lên lịch, chờ, ai cũng thực thi).
    function _viaTimelock(address target, bytes memory data, bytes32 salt) internal {
        vm.prank(governance3of3);
        timelock.schedule(target, 0, data, bytes32(0), salt, TIMELOCK_DELAY);
        vm.warp(block.timestamp + TIMELOCK_DELAY);
        timelock.execute(target, 0, data, bytes32(0), salt);
    }
}
