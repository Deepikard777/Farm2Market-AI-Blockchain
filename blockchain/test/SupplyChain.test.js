const { expect } = require("chai");
const { ethers } = require("hardhat");

describe("SupplyChain Smart Contract", function () {
  let supplyChain;
  let owner, addr1;

  beforeEach(async function () {
    [owner, addr1] = await ethers.getSigners();
    const SupplyChain = await ethers.getContractFactory("SupplyChain");
    supplyChain = await SupplyChain.deploy();
  });

  it("Should record and verify an event hash", async function () {
    const batchId = "BATCH-TEST-001";
    const eventHash = ethers.keccak256(ethers.toUtf8Bytes("SCAN|2026-10-20T12:00:00Z|Chennai|Dispatched"));

    await supplyChain.recordEvent(batchId, eventHash);

    const exists = await supplyChain.verifyEvent(batchId, eventHash);
    expect(exists).to.equal(true);

    const count = await supplyChain.getEventCount(batchId);
    expect(count).to.equal(1);
  });

  it("Should prevent duplicate events for the same batch", async function () {
    const batchId = "BATCH-TEST-002";
    const eventHash = ethers.keccak256(ethers.toUtf8Bytes("SCAN|2026-10-20T12:00:00Z|Chennai|Dispatched"));

    await supplyChain.recordEvent(batchId, eventHash);
    await expect(
      supplyChain.recordEvent(batchId, eventHash)
    ).to.be.revertedWith("Duplicate event");
  });

  it("Should return false for unrecorded event hash", async function () {
    const batchId = "BATCH-TEST-003";
    const eventHash = ethers.keccak256(ethers.toUtf8Bytes("NON-EXISTENT"));
    const exists = await supplyChain.verifyEvent(batchId, eventHash);
    expect(exists).to.equal(false);
  });
});
