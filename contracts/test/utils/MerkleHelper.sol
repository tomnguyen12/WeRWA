// SPDX-License-Identifier: GPL-3.0-or-later
pragma solidity 0.8.17;

/// @notice Dựng cây Merkle kiểu OpenZeppelin (cặp nút sắp xếp rồi keccak256) cho kiểm thử.
///         Nút lẻ ở cuối một tầng được đưa thẳng lên tầng trên.
library MerkleHelper {
    function hashPair(bytes32 a, bytes32 b) internal pure returns (bytes32) {
        return a < b ? keccak256(abi.encodePacked(a, b)) : keccak256(abi.encodePacked(b, a));
    }

    function root(bytes32[] memory leaves) internal pure returns (bytes32) {
        require(leaves.length > 0, "empty");
        bytes32[] memory level = leaves;
        while (level.length > 1) {
            level = _next(level);
        }
        return level[0];
    }

    function proof(bytes32[] memory leaves, uint256 index) internal pure returns (bytes32[] memory) {
        bytes32[] memory tmp = new bytes32[](64);
        uint256 n;
        bytes32[] memory level = leaves;
        uint256 idx = index;
        while (level.length > 1) {
            uint256 sibling = idx ^ 1;
            if (sibling < level.length) tmp[n++] = level[sibling];
            level = _next(level);
            idx /= 2;
        }
        bytes32[] memory out = new bytes32[](n);
        for (uint256 i = 0; i < n; i++) {
            out[i] = tmp[i];
        }
        return out;
    }

    function _next(bytes32[] memory level) private pure returns (bytes32[] memory up) {
        up = new bytes32[]((level.length + 1) / 2);
        for (uint256 i = 0; i < up.length; i++) {
            uint256 l = 2 * i;
            up[i] = l + 1 < level.length ? hashPair(level[l], level[l + 1]) : level[l];
        }
    }
}
