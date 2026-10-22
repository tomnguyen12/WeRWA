// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {Initializable} from "@openzeppelin/contracts-upgradeable/proxy/utils/Initializable.sol";
import {BeaconProxy} from "@openzeppelin/contracts/proxy/beacon/BeaconProxy.sol";
import {IERC3643Compliance, IERC3643Administrable, IERC3643SuiteDeployer} from "../erc3643/IERC3643.sol";

import {ISeriesController} from "../ISeriesController.sol";
import {SeriesController} from "../SeriesController.sol";
import {ModuleDeployer} from "./ModuleDeployer.sol";
import {InvestorClassModule} from "../compliance/InvestorClassModule.sol";
import {HoldingLimitModule} from "../compliance/HoldingLimitModule.sol";
import {TransferGateModule} from "../compliance/TransferGateModule.sol";

/// @title SeriesFactory
/// @notice Triển khai trọn bộ hợp đồng của một series trong một giao dịch (ADR-04; docs/design/04-hop-dong-thong-minh.md):
///         SeriesController (BeaconProxy), compliance và token ERC-3643 (qua tokenDeployer, ADR-0019),
///         ba module tuân thủ đã cấu hình. Controller là agent duy nhất của token; token và compliance thuộc timelock.
///         Chỉ GOVERNANCE (UBND + VSDC + Holco · 3/3) được gọi (ADR-05, máy Series cạnh 4).
/// @dev Chạy sau TransparentUpgradeableProxy; ProxyAdmin thuộc timelock (C13). Giữ `__gap` cho nâng cấp.
contract SeriesFactory is Initializable {
    /// @notice Cấu hình token và ví được miễn hạn mức ban đầu (C18; ADR-0017).
    struct TokenConfig {
        string name;
        string symbol;
        uint8 exemptKinds; // mặt nạ HoldingLimitModule; 0 = dùng mặc định (chỉ CUSTODIAN_OMNIBUS)
        address[] omnibusWallets;
        HoldingLimitModule.WalletKind[] omnibusKinds;
    }

    struct SeriesRecord {
        address controller;
        address token;
        address compliance;
        address investorClassModule;
        address holdingLimitModule;
        address transferGateModule;
    }

    /// @notice Multisig 3/3 được triển khai series.
    address public governance;
    /// @notice Timelock: chủ sở hữu token, compliance của mọi series.
    address public timelock;
    /// @notice Bộ tạo token và compliance theo phương án của ADR-0019 (bản XD: TREXSuiteDeployer, phương án A).
    IERC3643SuiteDeployer public tokenDeployer;
    /// @notice Sổ định danh dùng chung.
    address public identityRegistry;
    /// @notice Beacon của SeriesController (thuộc timelock).
    address public controllerBeacon;
    ModuleDeployer public moduleDeployer;

    mapping(bytes32 => SeriesRecord) private _series; // seriesId → bộ hợp đồng
    bytes32[] private _seriesIds;

    /// @dev Khoảng trống lưu trữ cho các bản nâng cấp.
    // slither-disable-next-line naming-convention,unused-state
    uint256[42] private __gap;

    event SeriesCreated(
        bytes32 indexed seriesId,
        address indexed controller,
        address indexed token,
        address compliance,
        address investorClassModule,
        address holdingLimitModule,
        address transferGateModule
    );
    event GovernanceChanged(address indexed previousGovernance, address indexed newGovernance);

    error Unauthorized();
    error ZeroAddress();
    error SeriesExists();
    error RoleMismatch();

    /// @custom:oz-upgrades-unsafe-allow constructor
    constructor() {
        _disableInitializers();
    }

    function initialize(
        address governance_,
        address timelock_,
        address tokenDeployer_,
        address identityRegistry_,
        address controllerBeacon_,
        address moduleDeployer_
    ) external initializer {
        if (
            governance_ == address(0) || timelock_ == address(0) || tokenDeployer_ == address(0)
                || identityRegistry_ == address(0) || controllerBeacon_ == address(0) || moduleDeployer_ == address(0)
        ) revert ZeroAddress();
        governance = governance_;
        timelock = timelock_;
        tokenDeployer = IERC3643SuiteDeployer(tokenDeployer_);
        identityRegistry = identityRegistry_;
        controllerBeacon = controllerBeacon_;
        moduleDeployer = ModuleDeployer(moduleDeployer_);
        emit GovernanceChanged(address(0), governance_);
    }

    /// @notice Triển khai một series. Vai GOVERNANCE trong điều khoản phải là chính multisig 3/3 của factory.
    /// @dev Các lời gọi ngoài là tạo hợp đồng mới từ mã cố định (proxy, module) và gọi chính các hợp đồng vừa tạo;
    ///      chỉ multisig 3/3 gọi được hàm này, nên không có đường gọi lại từ bên thứ ba.
    // slither-disable-next-line reentrancy-no-eth,reentrancy-benign,reentrancy-events
    function deploySeries(ISeriesController.SeriesTerms calldata terms, TokenConfig calldata cfg)
        external
        returns (SeriesRecord memory r)
    {
        if (msg.sender != governance) revert Unauthorized();
        if (terms.governance != governance) revert RoleMismatch();
        if (_series[terms.seriesId].controller != address(0)) revert SeriesExists();

        r.controller = address(new BeaconProxy(controllerBeacon, ""));
        (r.token, r.compliance) = tokenDeployer.deployTokenSet(identityRegistry, cfg.name, cfg.symbol);
        ModuleDeployer.Modules memory m = moduleDeployer.deploy();
        r.investorClassModule = m.investorClass;
        r.holdingLimitModule = m.holdingLimit;
        r.transferGateModule = m.transferGate;

        _series[terms.seriesId] = r;
        _seriesIds.push(terms.seriesId);
        emit SeriesCreated(
            terms.seriesId, r.controller, r.token, r.compliance, m.investorClass, m.holdingLimit, m.transferGate
        );

        _configureCompliance(IERC3643Compliance(r.compliance), m, r.controller, terms, cfg);

        IERC3643Administrable(r.token).addAgent(r.controller);
        SeriesController(r.controller).initialize(terms, r.token);

        // Factory không giữ quyền nào sau khi triển khai.
        _transferOwnership(r.token, timelock);
        _transferOwnership(r.compliance, timelock);
    }

    /// @notice Đổi multisig 3/3 được triển khai series. Chỉ timelock (đổi người ký trong Safe không cần gọi hàm này).
    function setGovernance(address newGovernance) external {
        if (msg.sender != timelock) revert Unauthorized();
        if (newGovernance == address(0)) revert ZeroAddress();
        emit GovernanceChanged(governance, newGovernance);
        governance = newGovernance;
    }

    function seriesOf(bytes32 seriesId) external view returns (SeriesRecord memory) {
        return _series[seriesId];
    }

    function seriesCount() external view returns (uint256) {
        return _seriesIds.length;
    }

    function seriesIdAt(uint256 index) external view returns (bytes32) {
        return _seriesIds[index];
    }

    function _configureCompliance(
        IERC3643Compliance compliance,
        ModuleDeployer.Modules memory m,
        address controller,
        ISeriesController.SeriesTerms calldata terms,
        TokenConfig calldata cfg
    ) private {
        compliance.addModule(m.investorClass);
        compliance.addModule(m.holdingLimit);
        compliance.addModule(m.transferGate);

        compliance.callModuleFunction(
            abi.encodeCall(InvestorClassModule.setInvestorClasses, (terms.investorClasses)), m.investorClass
        );
        uint8 mask = cfg.exemptKinds == 0 ? HoldingLimitModule(m.holdingLimit).DEFAULT_EXEMPT_KINDS() : cfg.exemptKinds;
        compliance.callModuleFunction(
            abi.encodeCall(HoldingLimitModule.configure, (terms.holdingLimit, mask)), m.holdingLimit
        );
        if (cfg.omnibusWallets.length > 0 || cfg.omnibusKinds.length > 0) {
            compliance.callModuleFunction(
                abi.encodeCall(HoldingLimitModule.setWalletKinds, (cfg.omnibusWallets, cfg.omnibusKinds)),
                m.holdingLimit
            );
        }
        compliance.callModuleFunction(
            abi.encodeCall(TransferGateModule.setSeriesController, (controller)), m.transferGate
        );
    }

    function _transferOwnership(address target, address newOwner) private {
        // Token và compliance của T-REX dùng OwnableUpgradeable.
        IERC3643Administrable(target).transferOwnership(newOwner);
    }
}
