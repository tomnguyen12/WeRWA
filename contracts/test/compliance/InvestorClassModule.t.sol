// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

import {WeRWAModuleBase} from "../../src/compliance/WeRWAModuleBase.sol";
import {ModuleTestBase} from "./ModuleTestBase.sol";
import {InvestorClassModule} from "../../src/compliance/InvestorClassModule.sol";
import {Identity} from "@onchain-id/solidity/contracts/Identity.sol";
import {ClaimIssuer} from "@onchain-id/solidity/contracts/ClaimIssuer.sol";
import {IClaimIssuer} from "@onchain-id/solidity/contracts/interface/IClaimIssuer.sol";
import {IIdentity} from "@onchain-id/solidity/contracts/interface/IIdentity.sol";

contract InvestorClassModuleTest is ModuleTestBase {
    event InvestorClassesSet(address indexed compliance, uint16[] classes);

    InvestorClassModule internal classModule;
    address internal eligible;
    address internal ineligible;
    Identity internal eligibleId;

    function setUp() public {
        _setUpSeries();
        classModule = new InvestorClassModule(TOPIC_KYC_INSTITUTION, TOPIC_INVESTOR_CLASS);
        _addModule(address(classModule));
        _callModule(address(classModule), abi.encodeCall(InvestorClassModule.setInvestorClasses, (_classes2())));
        (eligible, eligibleId) = _investor("eligible", CLASS_FOREIGN_INSTITUTION);
        (ineligible,) = _investor("ineligible", CLASS_NOT_ELIGIBLE);
    }

    function _classes2() internal pure returns (uint16[] memory c) {
        c = new uint16[](2);
        c[0] = CLASS_FOREIGN_INSTITUTION;
        c[1] = CLASS_VIFC_MEMBER;
    }

    function test_eligibleInvestor_canReceiveMint() public {
        _mint(eligible, 5);
        assertEq(token.balanceOf(eligible), 5);
        assertTrue(classModule.isEligible(address(compliance), eligible));
    }

    // US-1.03-AC3: loại không thuộc đối tượng series; mint gửi thẳng lên chuỗi bị hợp đồng từ chối.
    function test_US_1_03_AC3_mintToIneligibleClassRejected() public {
        assertTrue(identityRegistry.isVerified(ineligible)); // sổ định danh vẫn coi là đã xác minh
        vm.prank(agent);
        vm.expectRevert("Compliance not followed");
        token.mint(ineligible, 1);
    }

    // US-1.03-AC3 và US-3.08-AC2: chuyển tới ví loại không được phép bị từ chối.
    function test_US_1_03_AC3_transferToIneligibleClassRejected() public {
        _mint(eligible, 5);
        vm.prank(eligible);
        vm.expectRevert("Transfer not possible");
        token.transfer(ineligible, 1);
    }

    function test_US_3_08_AC2_transferToIneligibleRejected() public {
        _mint(eligible, 5);
        (address other,) = _investor("other", CLASS_VIFC_MEMBER);
        vm.prank(eligible);
        token.transfer(other, 2);
        assertEq(token.balanceOf(other), 2);
        vm.prank(eligible);
        vm.expectRevert("Transfer not possible");
        token.transfer(ineligible, 1);
    }

    // US-6.03-AC2: claim loại nhà đầu tư hết hạn thì chuyển tới ví đó bị từ chối.
    function test_US_6_03_AC2_expiredInvestorClassClaimRejected() public {
        address w = makeAddr("classExpiring");
        _onboardInvestor(
            w, _kyc(block.timestamp + 365 days), _class(CLASS_FOREIGN_INSTITUTION, block.timestamp + 1 days)
        );
        _mint(w, 1);
        vm.warp(block.timestamp + 1 days);
        vm.prank(agent);
        vm.expectRevert("Compliance not followed");
        token.mint(w, 1);
    }

    // US-6.03-AC2: claim KYC hết hạn thì chuyển tới ví đó bị từ chối.
    function test_US_6_03_AC2_expiredKycClaimRejected() public {
        _mint(eligible, 5);
        address w = makeAddr("kycExpiring");
        _onboardInvestor(
            w, _kyc(block.timestamp + 10 days), _class(CLASS_FOREIGN_INSTITUTION, block.timestamp + 365 days)
        );
        vm.warp(block.timestamp + 10 days + 1);
        vm.prank(eligible);
        vm.expectRevert("Transfer not possible");
        token.transfer(w, 1);
    }

    // US-6.03-AC3: KYC bị hủy, thu hồi claim thì ví không nhận được trái phiếu nữa.
    function test_US_6_03_AC3_revokedClaimBlocksReceiving() public {
        _mint(eligible, 1);
        (,,, bytes memory sig,,) =
            eligibleId.getClaim(keccak256(abi.encode(address(holcoClaimIssuer), TOPIC_KYC_INSTITUTION)));
        vm.prank(trexOwner);
        holcoClaimIssuer.revokeClaimBySignature(sig);
        assertFalse(classModule.isEligible(address(compliance), eligible));
        vm.prank(agent);
        vm.expectRevert("Identity is not verified.");
        token.mint(eligible, 1);
    }

    function test_walletWithoutIdentity_notEligible() public {
        assertFalse(classModule.isEligible(address(compliance), makeAddr("nobody")));
    }

    function test_claimFromUntrustedIssuer_ignored() public {
        // Định danh có claim INVESTOR_CLASS của một bên cấp không tin cậy, ngoài ra không có claim loại nào.
        address w = makeAddr("untrusted");
        Identity id = new Identity(w, false);
        _addClaim(id, w, TOPIC_KYC_INSTITUTION, _kyc(block.timestamp + 365 days));
        uint256 rogueKey = 0xBAD;
        ClaimIssuer rogue = new ClaimIssuer(vm.addr(rogueKey));
        bytes memory data = _class(CLASS_FOREIGN_INSTITUTION, block.timestamp + 365 days);
        bytes32 h = keccak256(
            abi.encodePacked(
                "\x19Ethereum Signed Message:\n32", keccak256(abi.encode(address(id), TOPIC_INVESTOR_CLASS, data))
            )
        );
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(rogueKey, h);
        vm.prank(w);
        id.addClaim(TOPIC_INVESTOR_CLASS, 1, address(rogue), abi.encodePacked(r, s, v), data, "");
        vm.prank(identityAgent);
        identityRegistry.registerIdentity(w, IIdentity(address(id)), COUNTRY_VN);

        assertFalse(classModule.isEligible(address(compliance), w));
    }

    function test_malformedClaimData_notEligible() public {
        address w = makeAddr("malformed");
        _onboardInvestor(
            w, abi.encode(uint64(block.timestamp + 1 days), uint64(1)), _class(1, block.timestamp + 1 days)
        );
        assertFalse(classModule.isEligible(address(compliance), w));

        address w2 = makeAddr("malformed2");
        _onboardInvestor(w2, _kyc(block.timestamp + 1 days), abi.encode(uint64(1)));
        assertFalse(classModule.isEligible(address(compliance), w2));

        address w3 = makeAddr("classOverflow");
        _onboardInvestor(
            w3, _kyc(block.timestamp + 1 days), abi.encode(uint256(1) << 20, uint64(block.timestamp + 1 days))
        );
        assertFalse(classModule.isEligible(address(compliance), w3));

        address w4 = makeAddr("untilOverflow");
        _onboardInvestor(w4, abi.encode(uint256(1) << 70), _class(1, block.timestamp + 1 days));
        assertFalse(classModule.isEligible(address(compliance), w4));
    }

    function test_trustedIssuerReverting_treatedInvalid() public {
        // Bên cấp tin cậy thứ hai luôn revert khi kiểm claim: module không revert, coi như không hợp lệ.
        RevertingIssuer bad = new RevertingIssuer();
        uint256[] memory topics = new uint256[](1);
        topics[0] = TOPIC_INVESTOR_CLASS;
        vm.prank(trexOwner);
        trustedIssuers.addTrustedIssuer(IClaimIssuer(address(bad)), topics);

        address w = makeAddr("badIssuerClaim");
        Identity id = new Identity(w, false);
        _addClaim(id, w, TOPIC_KYC_INSTITUTION, _kyc(block.timestamp + 365 days));
        // Ghi claim mang tên bên cấp `bad`; giả lập isClaimValid lúc ghi để định danh nhận claim.
        bytes memory data = _class(CLASS_FOREIGN_INSTITUTION, block.timestamp + 365 days);
        vm.mockCall(address(bad), abi.encodeWithSelector(IClaimIssuer.isClaimValid.selector), abi.encode(true));
        vm.prank(w);
        id.addClaim(TOPIC_INVESTOR_CLASS, 1, address(bad), hex"01", data, "");
        vm.clearMockedCalls();
        vm.prank(identityAgent);
        identityRegistry.registerIdentity(w, IIdentity(address(id)), COUNTRY_VN);

        assertFalse(classModule.isEligible(address(compliance), w));
    }

    function test_setInvestorClasses_replacesList() public {
        uint16[] memory only = new uint16[](1);
        only[0] = CLASS_NOT_ELIGIBLE;
        vm.expectEmit(true, false, false, true, address(classModule));
        emit InvestorClassesSet(address(compliance), only);
        _callModule(address(classModule), abi.encodeCall(InvestorClassModule.setInvestorClasses, (only)));

        assertFalse(classModule.isClassAllowed(address(compliance), CLASS_FOREIGN_INSTITUTION));
        assertTrue(classModule.isClassAllowed(address(compliance), CLASS_NOT_ELIGIBLE));
        assertEq(classModule.investorClassesOf(address(compliance)).length, 1);
        assertTrue(classModule.isEligible(address(compliance), ineligible));
        assertFalse(classModule.isEligible(address(compliance), eligible));
    }

    function test_setInvestorClasses_rejectsEmptyAndDuplicate() public {
        vm.prank(timelock);
        vm.expectRevert(InvestorClassModule.EmptyClassList.selector);
        compliance.callModuleFunction(
            abi.encodeCall(InvestorClassModule.setInvestorClasses, (new uint16[](0))), address(classModule)
        );

        uint16[] memory dup = new uint16[](2);
        dup[0] = 3;
        dup[1] = 3;
        vm.prank(timelock);
        vm.expectRevert(abi.encodeWithSelector(InvestorClassModule.DuplicateClass.selector, uint16(3)));
        compliance.callModuleFunction(
            abi.encodeCall(InvestorClassModule.setInvestorClasses, (dup)), address(classModule)
        );
    }

    // US-4.01-AC1: tham số module đọc lại khớp tham số đã đặt từ term sheet.
    function test_US_4_01_AC1_parametersReadBack() public view {
        uint16[] memory got = classModule.investorClassesOf(address(compliance));
        assertEq(got.length, 2);
        assertEq(got[0], CLASS_FOREIGN_INSTITUTION);
        assertEq(got[1], CLASS_VIFC_MEMBER);
        assertEq(classModule.kycTopic(), TOPIC_KYC_INSTITUTION);
        assertEq(classModule.investorClassTopic(), TOPIC_INVESTOR_CLASS);
    }

    // US-4.01-AC3: đổi tham số chỉ qua multisig 3 bên (chủ compliance).
    function test_US_4_01_AC3_configOnlyThroughCompliance() public {
        vm.expectRevert(WeRWAModuleBase.OnlyBoundCompliance.selector);
        classModule.setInvestorClasses(_classes2());
        vm.prank(eligible);
        vm.expectRevert("Ownable: caller is not the owner");
        compliance.callModuleFunction(
            abi.encodeCall(InvestorClassModule.setInvestorClasses, (_classes2())), address(classModule)
        );
    }

    function test_unconfigured_failsClosed() public {
        assertFalse(classModule.moduleCheck(address(0), eligible, 1, makeAddr("otherCompliance")));
    }

    function test_constructor_rejectsSameTopics() public {
        vm.expectRevert(InvestorClassModule.InvalidTopics.selector);
        new InvestorClassModule(1, 1);
    }

    function test_metadata() public view {
        assertEq(classModule.name(), "InvestorClassModule");
        assertTrue(classModule.isPlugAndPlay());
    }
}

/// @dev Bên cấp claim luôn revert khi được hỏi tính hợp lệ.
contract RevertingIssuer {
    function isClaimValid(address, uint256, bytes calldata, bytes calldata) external pure returns (bool) {
        revert("issuer down");
    }
}
