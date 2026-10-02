// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/**
 * @title SupplyChain
 * @dev Immutable supply chain event ledger for FarmToMarket.
 *      Each product batch has an ordered list of event hashes
 *      that can be verified against off-chain journey data.
 */
contract SupplyChain {
    // ── Structs ──────────────────────────────────────────────────────
    struct ChainEvent {
        bytes32 eventHash;   // keccak256 of event data
        uint256 timestamp;   // block timestamp when recorded
        address recorder;    // address that recorded the event
    }

    // ── State ────────────────────────────────────────────────────────
    address public owner;

    // batchId => array of on-chain events
    mapping(string => ChainEvent[]) private batchEvents;

    // batchId => eventHash => exists flag (for O(1) verification)
    mapping(string => mapping(bytes32 => bool)) private eventExists;

    // track all known batch IDs
    string[] private batchIds;
    mapping(string => bool) private batchKnown;

    // ── Events ───────────────────────────────────────────────────────
    event EventRecorded(
        string indexed batchId,
        bytes32 eventHash,
        uint256 timestamp,
        address recorder
    );

    // ── Modifiers ────────────────────────────────────────────────────
    modifier onlyOwner() {
        require(msg.sender == owner, "Not authorized");
        _;
    }

    // ── Constructor ──────────────────────────────────────────────────
    constructor() {
        owner = msg.sender;
    }

    // ── Write functions ──────────────────────────────────────────────

    /**
     * @dev Record a supply chain event for a batch.
     * @param batchId  The product batch identifier
     * @param eventHash  keccak256 hash of the event data (computed off-chain)
     */
    function recordEvent(string calldata batchId, bytes32 eventHash) external {
        require(eventHash != bytes32(0), "Empty hash");
        require(!eventExists[batchId][eventHash], "Duplicate event");

        ChainEvent memory evt = ChainEvent({
            eventHash: eventHash,
            timestamp: block.timestamp,
            recorder: msg.sender
        });

        batchEvents[batchId].push(evt);
        eventExists[batchId][eventHash] = true;

        // Track batch ID
        if (!batchKnown[batchId]) {
            batchIds.push(batchId);
            batchKnown[batchId] = true;
        }

        emit EventRecorded(batchId, eventHash, block.timestamp, msg.sender);
    }

    // ── Read functions ───────────────────────────────────────────────

    /**
     * @dev Verify whether a specific event hash exists on-chain for a batch.
     */
    function verifyEvent(string calldata batchId, bytes32 eventHash)
        external view returns (bool)
    {
        return eventExists[batchId][eventHash];
    }

    /**
     * @dev Get all recorded events for a batch.
     */
    function getEvents(string calldata batchId)
        external view returns (ChainEvent[] memory)
    {
        return batchEvents[batchId];
    }

    /**
     * @dev Get the number of recorded events for a batch.
     */
    function getEventCount(string calldata batchId)
        external view returns (uint256)
    {
        return batchEvents[batchId].length;
    }

    /**
     * @dev Get all known batch IDs.
     */
    function getBatchIds() external view returns (string[] memory) {
        return batchIds;
    }
}
