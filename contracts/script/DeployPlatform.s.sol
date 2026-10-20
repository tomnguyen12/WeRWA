// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {Script, console2} from "forge-std/Script.sol";

import {Token} from "@trex/token/Token.sol";
import {ClaimTopicsRegistry} from "@trex/registry/implementation/ClaimTopicsRegistry.sol";
import {IdentityRegistry} from "@trex/registry/implementation/IdentityRegistry.sol";
import {IdentityRegistryStorage} from "@trex/registry/implementation/IdentityRegistryStorage.sol";
import {TrustedIssuersRegistry} from "@trex/registry/implementation/TrustedIssuersRegistry.sol";
import {ModularCompliance} from "@trex/compliance/modular/ModularCompliance.sol";
import {TREXImplementationAuthority} from "@trex/proxy/authority/TREXImplementationAuthority.sol";
import {ITREXImplementationAuthority} from "@trex/proxy/authority/ITREXImplementationAuthority.sol";
import {ClaimTopicsRegistryProxy} from "@trex/proxy/ClaimTopicsRegistryProxy.sol";
import {TrustedIssuersRegistryProxy} from "@trex/proxy/TrustedIssuersRegistryProxy.sol";
import {IdentityRegistryStorageProxy} from "@trex/proxy/IdentityRegistryStorageProxy.sol";
import {IdentityRegistryProxy} from "@trex/proxy/IdentityRegistryProxy.sol";
import {ClaimIssuer} from "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import {IClaimIssuer} from "@onchain-id/solidity/contracts/interface/IClaimIssuer.sol";
import {UpgradeableBeacon} from "@openzeppelin/contracts/proxy/beacon/UpgradeableBeacon.sol";
import {TransparentUpgradeableProxy} from "@openzeppelin/contracts/proxy/transparent/TransparentUpgradeableProxy.sol";
import {ProxyAdmin} from "@openzeppelin/contracts/proxy/transparent/ProxyAdmin.sol";

import {SeriesController} from "../src/SeriesController.sol";
import {SeriesFactory} from "../src/factory/SeriesFactory.sol";
import {ModuleDeployer} from "../src/factory/ModuleDeployer.sol";
import {WeRWATimelock} from "../src/governance/WeRWATimelock.sol";
import {AuditAnchor} from "../src/AuditAnchor.sol";

/// @notice Triển khai toàn nền tảng WeRWA lên một mạng (devnet, mạng thử XD).
///         Không chứa khóa: người gửi giao dịch là khóa triển khai truyền qua `forge script --sender/--account`;
///         mọi quyền sở hữu được chuyển cho timelock trước khi script kết thúc.
///
/// Biến môi trường (địa chỉ, không phải khóa):
///   WERWA_GOVERNANCE_3OF3     Safe liên bên UBND + VSDC + Holco (lên lịch timelock, triển khai series)
///   WERWA_CANCELLERS          Danh sách Safe tổ chức được hủy lịch, ngăn bằng dấu phẩy (UBND, VSDC, Holco)
///   WERWA_HOLCO_SAFE          Safe của Holco: khóa quản lý của bên cấp claim
///   WERWA_HOLCO_CLAIM_SIGNER  Địa chỉ khóa ký claim của Holco (trong HSM)
///   WERWA_HOLCO_OPERATOR      Khóa vận hành Holco: agent sổ định danh, OPERATOR của AuditAnchor
///   WERWA_TIMELOCK_DELAY      Giây; mặc định 3600 (C15)
///   WERWA_TOPIC_KYC, WERWA_TOPIC_CLASS  Mã chủ đề claim; mặc định theo giả định C1
contract DeployPlatform is Script {
    struct Config {
        address governance3of3;
        address[] cancellers;
        address holcoSafe;
        address holcoClaimSigner;
        address holcoOperator;
        uint256 timelockDelay;
        uint256 kycTopic;
        uint256 classTopic;
    }

    struct Deployed {
        address timelock;
        address implementationAuthority;
        address identityRegistry;
        address identityRegistryStorage;
        address claimTopicsRegistry;
        address trustedIssuersRegistry;
        address holcoClaimIssuer;
        address controllerBeacon;
        address moduleDeployer;
        address proxyAdmin;
        address seriesFactory;
        address auditAnchor;
    }

    function run() external returns (Deployed memory d) {
        Config memory cfg = _configFromEnv();
        vm.startBroadcast();
        d = deploy(cfg);
        vm.stopBroadcast();
        _log(d);
    }

    function deploy(Config memory cfg) public returns (Deployed memory d) {
        d.timelock = address(new WeRWATimelock(cfg.timelockDelay, cfg.governance3of3, cfg.cancellers));
        _deployTrex(cfg, d);
        _deployClaimIssuer(cfg, d);
        _deployWeRWA(cfg, d);
        _handOver(d);
    }

    /// @dev Bộ T-REX dùng chung: Implementation Authority, sổ định danh, chủ đề claim, bên cấp tin cậy.
    function _deployTrex(Config memory cfg, Deployed memory d) internal {
        TREXImplementationAuthority ia = new TREXImplementationAuthority(true, address(0), address(0));
        ia.addAndUseTREXVersion(
            ITREXImplementationAuthority.Version({major: 4, minor: 1, patch: 3}),
            ITREXImplementationAuthority.TREXContracts({
                tokenImplementation: address(new Token()),
                ctrImplementation: address(new ClaimTopicsRegistry()),
                irImplementation: address(new IdentityRegistry()),
                irsImplementation: address(new IdentityRegistryStorage()),
                tirImplementation: address(new TrustedIssuersRegistry()),
                mcImplementation: address(new ModularCompliance())
            })
        );
        d.implementationAuthority = address(ia);
        d.claimTopicsRegistry = address(new ClaimTopicsRegistryProxy(address(ia)));
        d.trustedIssuersRegistry = address(new TrustedIssuersRegistryProxy(address(ia)));
        d.identityRegistryStorage = address(new IdentityRegistryStorageProxy(address(ia)));
        d.identityRegistry = address(
            new IdentityRegistryProxy(
                address(ia), d.trustedIssuersRegistry, d.claimTopicsRegistry, d.identityRegistryStorage
            )
        );
        IdentityRegistryStorage(d.identityRegistryStorage).bindIdentityRegistry(d.identityRegistry);
        IdentityRegistry(d.identityRegistry).addAgent(cfg.holcoOperator);
        ClaimTopicsRegistry(d.claimTopicsRegistry).addClaimTopic(cfg.kycTopic);
        ClaimTopicsRegistry(d.claimTopicsRegistry).addClaimTopic(cfg.classTopic);
    }

    /// @dev Bên cấp claim Holco. Khóa triển khai làm khóa quản lý tạm để thêm khóa ký (trong HSM),
    ///      rồi giao quyền quản lý cho Safe Holco và tự gỡ.
    function _deployClaimIssuer(Config memory cfg, Deployed memory d) internal {
        ClaimIssuer issuer = new ClaimIssuer(address(this));
        issuer.addKey(keccak256(abi.encode(cfg.holcoClaimSigner)), 3, 1);
        issuer.addKey(keccak256(abi.encode(cfg.holcoSafe)), 1, 1);
        issuer.removeKey(keccak256(abi.encode(address(this))), 1);
        d.holcoClaimIssuer = address(issuer);
        uint256[] memory topics = new uint256[](2);
        topics[0] = cfg.kycTopic;
        topics[1] = cfg.classTopic;
        TrustedIssuersRegistry(d.trustedIssuersRegistry).addTrustedIssuer(IClaimIssuer(address(issuer)), topics);
    }

    function _deployWeRWA(Config memory cfg, Deployed memory d) internal {
        d.controllerBeacon = address(new UpgradeableBeacon(address(new SeriesController())));
        d.moduleDeployer = address(new ModuleDeployer(cfg.kycTopic, cfg.classTopic));
        d.proxyAdmin = address(new ProxyAdmin());
        bytes memory init = abi.encodeCall(
            SeriesFactory.initialize,
            (
                cfg.governance3of3,
                d.timelock,
                d.implementationAuthority,
                d.identityRegistry,
                d.controllerBeacon,
                d.moduleDeployer
            )
        );
        d.seriesFactory = address(new TransparentUpgradeableProxy(address(new SeriesFactory()), d.proxyAdmin, init));
        // Neo nhật ký kiểm toán: OPERATOR là khóa vận hành Holco; GOVERNANCE xoay khóa (C3).
        d.auditAnchor = address(new AuditAnchor(cfg.governance3of3, cfg.holcoOperator));
    }

    /// @dev Giao mọi quyền sở hữu cho timelock.
    function _handOver(Deployed memory d) internal {
        TREXImplementationAuthority(d.implementationAuthority).transferOwnership(d.timelock);
        ClaimTopicsRegistry(d.claimTopicsRegistry).transferOwnership(d.timelock);
        TrustedIssuersRegistry(d.trustedIssuersRegistry).transferOwnership(d.timelock);
        IdentityRegistryStorage(d.identityRegistryStorage).transferOwnership(d.timelock);
        IdentityRegistry(d.identityRegistry).transferOwnership(d.timelock);
        UpgradeableBeacon(d.controllerBeacon).transferOwnership(d.timelock);
        ProxyAdmin(d.proxyAdmin).transferOwnership(d.timelock);
    }

    function _configFromEnv() internal view returns (Config memory cfg) {
        cfg.governance3of3 = vm.envAddress("WERWA_GOVERNANCE_3OF3");
        cfg.cancellers = vm.envAddress("WERWA_CANCELLERS", ",");
        cfg.holcoSafe = vm.envAddress("WERWA_HOLCO_SAFE");
        cfg.holcoClaimSigner = vm.envAddress("WERWA_HOLCO_CLAIM_SIGNER");
        cfg.holcoOperator = vm.envAddress("WERWA_HOLCO_OPERATOR");
        cfg.timelockDelay = vm.envOr("WERWA_TIMELOCK_DELAY", uint256(1 hours));
        cfg.kycTopic = vm.envOr("WERWA_TOPIC_KYC", uint256(keccak256("WERWA.CLAIM.KYC_INSTITUTION")));
        cfg.classTopic = vm.envOr("WERWA_TOPIC_CLASS", uint256(keccak256("WERWA.CLAIM.INVESTOR_CLASS")));
    }

    function _log(Deployed memory d) internal pure {
        console2.log("timelock", d.timelock);
        console2.log("implementationAuthority", d.implementationAuthority);
        console2.log("identityRegistry", d.identityRegistry);
        console2.log("holcoClaimIssuer", d.holcoClaimIssuer);
        console2.log("controllerBeacon", d.controllerBeacon);
        console2.log("seriesFactory", d.seriesFactory);
        console2.log("auditAnchor", d.auditAnchor);
    }
}
