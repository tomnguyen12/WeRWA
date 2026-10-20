// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {TREXSuite} from "./TREXSuite.sol";
import {MerkleHelper} from "./MerkleHelper.sol";
import {SeriesLeaves} from "../../src/libraries/SeriesLeaves.sol";
import {Token} from "@trex/token/Token.sol";
import {ModularCompliance} from "@trex/compliance/modular/ModularCompliance.sol";
import {UpgradeableBeacon} from "@openzeppelin/contracts/proxy/beacon/UpgradeableBeacon.sol";
import {BeaconProxy} from "@openzeppelin/contracts/proxy/beacon/BeaconProxy.sol";

import {SeriesController} from "../../src/SeriesController.sol";
import {ISeriesController} from "../../src/ISeriesController.sol";
import {SeriesState} from "../../src/interfaces/ISeriesState.sol";
import {InvestorClassModule} from "../../src/compliance/InvestorClassModule.sol";
import {HoldingLimitModule} from "../../src/compliance/HoldingLimitModule.sol";
import {TransferGateModule} from "../../src/compliance/TransferGateModule.sol";

/// @notice Dựng trọn một series như factory sẽ làm (PR 6): token, compliance với ba module, SeriesController
///         sau BeaconProxy làm agent duy nhất. Các vai là địa chỉ thử; timelock là trexOwner.
abstract contract SeriesFixture is TREXSuite {
    uint16 internal constant CLASS_FOREIGN_INSTITUTION = 1;
    uint16 internal constant CLASS_VIFC_MEMBER = 2;
    uint16 internal constant CLASS_NOT_ELIGIBLE = 9;

    // Vai theo ADR-05 (địa chỉ thử thay cho Safe).
    address internal ubnd = makeAddr("UBND");
    address internal vsdc = makeAddr("VSDC");
    address internal bidv = makeAddr("BIDV");
    address internal holcoOperator = makeAddr("holcoOperator");
    address internal enforcement2of2 = makeAddr("VSDC+Holco 2/2");
    address internal governance3of3 = makeAddr("UBND+VSDC+Holco 3/3");
    address internal timelock;

    // Điều khoản mẫu: mệnh giá 1 000 USD (100 000 cent), giá phát hành 99,5%, 10 kỳ, lãi suất 4%–6%.
    uint256 internal constant FACE_VALUE_MINOR = 100_000;
    uint32 internal constant ISSUE_PRICE_PPM = 995_000;
    uint64 internal constant MAX_QUANTITY = 1_000_000;
    uint16 internal constant PERIODS = 10;
    uint32 internal constant RATE_FLOOR = 40_000;
    uint32 internal constant RATE_CAP = 60_000;
    uint64 internal constant HOLDING_LIMIT = 200_000;
    bytes12 internal constant ISIN = "US0378331005"; // ISIN hợp lệ ISO 6166 dùng làm mẫu

    Token internal token;
    ModularCompliance internal compliance;
    SeriesController internal controller;
    UpgradeableBeacon internal beacon;
    InvestorClassModule internal classModule;
    HoldingLimitModule internal limitModule;
    TransferGateModule internal gateModule;

    function _defaultTerms() internal view returns (ISeriesController.SeriesTerms memory t) {
        uint16[] memory classes = new uint16[](2);
        classes[0] = CLASS_FOREIGN_INSTITUTION;
        classes[1] = CLASS_VIFC_MEMBER;
        t = ISeriesController.SeriesTerms({
            seriesId: keccak256("series-2027A"),
            termsHash: sha256("term sheet 2027A"),
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

    function _deploySeries() internal {
        _deploySeries(_defaultTerms());
    }

    function _deploySeries(ISeriesController.SeriesTerms memory terms) internal {
        _deployTREXSuite();
        timelock = trexOwner;

        vm.startPrank(timelock);
        beacon = new UpgradeableBeacon(address(new SeriesController()));
        controller = SeriesController(address(new BeaconProxy(address(beacon), "")));
        vm.stopPrank();

        (token, compliance) = _deployToken("WeRWA HCMC 2027A", "HCMC27A", address(controller));

        classModule = new InvestorClassModule(TOPIC_KYC_INSTITUTION, TOPIC_INVESTOR_CLASS);
        limitModule = new HoldingLimitModule();
        gateModule = new TransferGateModule();

        vm.startPrank(timelock);
        compliance.addModule(address(classModule));
        compliance.addModule(address(limitModule));
        compliance.addModule(address(gateModule));
        compliance.callModuleFunction(
            abi.encodeCall(InvestorClassModule.setInvestorClasses, (terms.investorClasses)), address(classModule)
        );
        compliance.callModuleFunction(
            abi.encodeCall(HoldingLimitModule.configure, (terms.holdingLimit, limitModule.DEFAULT_EXEMPT_KINDS())),
            address(limitModule)
        );
        compliance.callModuleFunction(
            abi.encodeCall(TransferGateModule.setSeriesController, (address(controller))), address(gateModule)
        );
        vm.stopPrank();

        controller.initialize(terms, address(token));
    }

    // ---- Nhà đầu tư ----

    function _investor(string memory label, uint16 classCode) internal returns (address wallet) {
        wallet = makeAddr(label);
        _onboardInvestor(
            wallet,
            abi.encode(uint64(block.timestamp + 365 days)),
            abi.encode(classCode, uint64(block.timestamp + 365 days))
        );
    }

    // ---- Danh sách phân bổ và lô tiền ----

    struct Alloc {
        bytes32 allocationId;
        address wallet;
        uint64 quantity;
        bytes32 bankRefHash;
    }

    function _amount(uint64 qty) internal pure returns (uint256) {
        return uint256(qty) * FACE_VALUE_MINOR * ISSUE_PRICE_PPM / 1_000_000;
    }

    function _allocLeaves(Alloc[] memory a) internal view returns (bytes32[] memory leaves) {
        leaves = new bytes32[](a.length);
        for (uint256 i = 0; i < a.length; i++) {
            leaves[i] = SeriesLeaves.allocationLeaf(address(controller), a[i].allocationId, a[i].wallet, a[i].quantity);
        }
    }

    function _paymentLeaves(Alloc[] memory a) internal view returns (bytes32[] memory leaves) {
        leaves = new bytes32[](a.length);
        for (uint256 i = 0; i < a.length; i++) {
            leaves[i] = SeriesLeaves.paymentLeaf(
                address(controller), a[i].allocationId, _amount(a[i].quantity), a[i].bankRefHash
            );
        }
    }

    function _total(Alloc[] memory a) internal pure returns (uint64 t) {
        for (uint256 i = 0; i < a.length; i++) {
            t += a[i].quantity;
        }
    }

    function _rates(uint32 rate) internal pure returns (uint32[] memory r) {
        r = new uint32[](PERIODS);
        for (uint256 i = 0; i < PERIODS; i++) {
            r[i] = rate;
        }
    }

    function _toReady() internal {
        vm.prank(vsdc);
        controller.setIsin(ISIN);
    }

    function _toAllocated(Alloc[] memory a) internal {
        _toReady();
        vm.prank(ubnd);
        controller.setAllocation(
            MerkleHelper.root(_allocLeaves(a)), keccak256("allocation list v3"), _total(a), _rates(51_250)
        );
    }

    function _addBatch(Alloc[] memory a) internal returns (bytes32 root) {
        root = MerkleHelper.root(_paymentLeaves(a));
        vm.prank(bidv);
        controller.addPaymentBatch(root, keccak256(abi.encode("batch", root)));
    }

    function _mintAt(Alloc[] memory a, uint256 i, bytes32 paymentRoot) internal {
        ISeriesController.MintItem memory item = ISeriesController.MintItem({
            allocationId: a[i].allocationId,
            wallet: a[i].wallet,
            quantity: a[i].quantity,
            amountMinor: _amount(a[i].quantity),
            bankRefHash: a[i].bankRefHash
        });
        bytes32[] memory ap = MerkleHelper.proof(_allocLeaves(a), i);
        bytes32[] memory pp = MerkleHelper.proof(_paymentLeaves(a), i);
        vm.prank(holcoOperator);
        controller.mint(item, ap, paymentRoot, pp);
    }

    function _toActive(Alloc[] memory a) internal {
        _toAllocated(a);
        bytes32 root = _addBatch(a);
        for (uint256 i = 0; i < a.length; i++) {
            _mintAt(a, i, root);
        }
        vm.roll(block.number + 1);
        vm.prank(vsdc);
        controller.attestRegister(keccak256("register after issuance"), uint64(block.number - 1));
    }

    function _state() internal view returns (SeriesState) {
        return controller.state();
    }
}
