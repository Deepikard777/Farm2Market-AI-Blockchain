package in.edu.ssn.backend.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.gas.StaticGasProvider;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Service for interacting with the SupplyChain smart contract on Ethereum.
 * Records supply chain events as keccak256 hashes on-chain for tamper-proof verification.
 *
 * <h3>Hardhat / local-chain resilience</h3>
 * The Hardhat development chain is ephemeral: restarting {@code npx hardhat node} wipes all
 * state, so any txHash previously persisted in PostgreSQL becomes meaningless. This service
 * therefore never treats a stored txHash as proof of existence — it always re-checks the
 * <em>current</em> chain.
 *
 * It also verifies, before every write/read, that the contract actually has bytecode deployed
 * at the configured address. Without that check a restarted-but-not-redeployed node silently
 * swallows every call (eth_call on a codeless address returns {@code 0x}) while
 * {@code eth_sendRawTransaction} to the same address still "succeeds" as a no-op and returns a
 * perfectly valid-looking txHash.
 */
@Service
public class BlockchainService {

    private static final long RECEIPT_TIMEOUT_MS = 30_000L;
    private static final long RECEIPT_POLL_INTERVAL_MS = 250L;

    @Value("${blockchain.node.url:http://127.0.0.1:8545}")
    private String nodeUrl;

    @Value("${blockchain.contract.address:}")
    private String contractAddress;

    // Hardhat Account #0 private key (for local dev only)
    @Value("${blockchain.private.key:0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80}")
    private String privateKey;

    private Web3j web3j;
    private Credentials credentials;
    private RawTransactionManager txManager;
    private StaticGasProvider gasProvider;

    /** The node answered JSON-RPC and we hold a usable Web3j/Credentials pair. */
    private boolean initialized = false;
    /** Bytecode exists at {@link #contractAddress} — required for any real chain interaction. */
    private boolean contractDeployed = false;
    /** Last initialisation/refresh failure, surfaced through /api/blockchain/status. */
    private volatile String lastError = null;

    // ── Outcome of a record attempt ───────────────────────────────────

    public static final class RecordResult {
        public static final String RECORDED = "RECORDED";
        public static final String ALREADY_ON_CHAIN = "ALREADY_ON_CHAIN";
        public static final String FAILED = "FAILED";
        public static final String UNAVAILABLE = "UNAVAILABLE";

        public final String outcome;
        public final String txHash;
        public final String detail;

        public RecordResult(String outcome, String txHash, String detail) {
            this.outcome = outcome;
            this.txHash = txHash;
            this.detail = detail;
        }

        public boolean recorded() {
            return RECORDED.equals(outcome);
        }

        public boolean alreadyOnChain() {
            return ALREADY_ON_CHAIN.equals(outcome);
        }

        /** True when the event is now provably present on chain (freshly written or pre-existing). */
        public boolean isOnChain() {
            return RECORDED.equals(outcome) || ALREADY_ON_CHAIN.equals(outcome);
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    @PostConstruct
    public void init() {
        initialized = false;
        contractDeployed = false;
        lastError = null;

        try {
            // Try to load contract address from deployment file if not configured in properties
            if (contractAddress == null || contractAddress.isBlank()) {
                contractAddress = loadContractAddress();
            }

            if (contractAddress == null || contractAddress.isBlank()) {
                lastError = "No contract address configured or found in blockchain/deployments/localhost.json";
                System.err.println("[Blockchain] " + lastError + " — blockchain features disabled.");
                return;
            }

            web3j = Web3j.build(new HttpService(nodeUrl));
            credentials = Credentials.create(privateKey);
            txManager = new RawTransactionManager(web3j, credentials);
            gasProvider = new StaticGasProvider(
                    BigInteger.valueOf(20_000_000_000L), // gas price
                    BigInteger.valueOf(6_721_975L)        // gas limit
            );

            // Test connection
            EthBlockNumber blockNumber = web3j.ethBlockNumber().send();
            System.out.println("[Blockchain] Connected to node at " + nodeUrl);
            System.out.println("[Blockchain] Current block: " + blockNumber.getBlockNumber());
            System.out.println("[Blockchain] Contract: " + contractAddress);
            System.out.println("[Blockchain] Account: " + credentials.getAddress());

            initialized = true;

            // Contract bytecode check — guards against "Hardhat restarted but not redeployed".
            contractDeployed = hasContractCode(contractAddress);
            if (!contractDeployed) {
                System.out.println("[Blockchain] No bytecode at " + contractAddress
                        + " — attempting to re-resolve from deployment file (Hardhat may have been restarted).");
                if (refreshContractAddress()) {
                    contractDeployed = hasContractCode(contractAddress);
                }
            }

            if (contractDeployed) {
                System.out.println("[Blockchain] Contract verified on-chain at " + contractAddress);
            } else {
                lastError = "No SupplyChain contract deployed at " + contractAddress
                        + ". Run 'npx hardhat node' then 'npx hardhat run scripts/deploy.js --network localhost'.";
                System.err.println("[Blockchain] " + lastError);
            }

        } catch (Exception e) {
            lastError = "Failed to connect to node at " + nodeUrl + ": " + e.getMessage();
            System.err.println("[Blockchain] " + lastError);
            System.err.println("[Blockchain] Blockchain features disabled. Start Hardhat node and redeploy.");
        }
    }

    /** Node reachable (regardless of whether the contract is deployed). */
    public boolean isNodeReachable() {
        return initialized;
    }

    /**
     * True only when the node is reachable AND the contract has bytecode.
     * All mutating/reading operations go through this gate.
     */
    public boolean isAvailable() {
        if (!initialized) return false;
        if (!contractDeployed) {
            // Cheap self-heal: the contract may have been redeployed after a Hardhat restart.
            contractDeployed = hasContractCode(contractAddress);
            if (!contractDeployed && refreshContractAddress()) {
                contractDeployed = hasContractCode(contractAddress);
            }
        }
        return contractDeployed;
    }

    public String getContractAddress() {
        return contractAddress;
    }

    public boolean isContractDeployed() {
        return contractDeployed;
    }

    public String getLastError() {
        return lastError;
    }

    /** Human-readable reason the service is not usable, or null when it is healthy. */
    public String getUnavailableReason() {
        if (!initialized) {
            return lastError != null ? lastError : "Blockchain node is not reachable at " + nodeUrl;
        }
        if (!contractDeployed) {
            return lastError != null
                    ? lastError
                    : "No SupplyChain contract deployed at " + contractAddress;
        }
        return null;
    }

    // ── Core Operations ──────────────────────────────────────────────

    /**
     * Record a supply chain event on the blockchain.
     *
     * @param batchId   The product batch ID
     * @param eventData Concatenated string of event details (type + timestamp + location + description)
     * @return Transaction hash when the event was written to the chain, otherwise null.
     *         Returns null (not a fake hash) when the event was already on-chain or the write failed.
     */
    public String recordEvent(String batchId, String eventData) {
        RecordResult result = recordEventDetailed(batchId, eventData);
        return result.recorded() ? result.txHash : null;
    }

    /**
     * Record an event, reporting precisely what happened.
     *
     * <p>The operation is <strong>idempotent</strong>: if the canonical event hash already exists
     * on the current chain for this batch, no transaction is sent (the contract would revert with
     * "Duplicate event" anyway). This makes recovery after a Hardhat restart safe to retry.
     */
    public RecordResult recordEventDetailed(String batchId, String eventData) {
        if (!isAvailable()) {
            System.out.println("[Blockchain] Skipped recording — " + getUnavailableReason());
            return new RecordResult(RecordResult.UNAVAILABLE, null, getUnavailableReason());
        }

        try {
            byte[] hash = computeEventHash(batchId, eventData);
            Bytes32 eventHash = new Bytes32(hash);

            // Idempotency guard: never double-write, never trigger the contract's "Duplicate event" revert.
            if (verifyEventHash(batchId, eventHash)) {
                System.out.println("[Blockchain] Event already present on current chain for batch " + batchId);
                return new RecordResult(RecordResult.ALREADY_ON_CHAIN, null,
                        "Event hash already exists on the current blockchain");
            }

            // Encode function call: recordEvent(string batchId, bytes32 eventHash)
            Function function = new Function(
                    "recordEvent",
                    Arrays.asList(new Utf8String(batchId), eventHash),
                    Collections.emptyList()
            );
            String encodedFunction = FunctionEncoder.encode(function);

            // Send transaction
            EthSendTransaction response = txManager.sendTransaction(
                    gasProvider.getGasPrice(),
                    gasProvider.getGasLimit(),
                    contractAddress,
                    encodedFunction,
                    BigInteger.ZERO
            );

            if (response.hasError()) {
                String msg = response.getError() != null ? response.getError().getMessage() : "unknown RPC error";
                System.err.println("[Blockchain] Tx error: " + msg);
                return new RecordResult(RecordResult.FAILED, null, msg);
            }

            String txHash = response.getTransactionHash();
            if (txHash == null || txHash.isBlank()) {
                return new RecordResult(RecordResult.FAILED, null, "Node returned no transaction hash");
            }

            // Wait for the receipt: submission alone is NOT proof the event was written.
            TransactionReceipt receipt = awaitReceipt(txHash);
            if (receipt == null) {
                System.err.println("[Blockchain] No receipt for tx " + txHash + " (timed out) — not treating as recorded");
                return new RecordResult(RecordResult.FAILED, txHash, "Transaction was not mined in time");
            }
            if (!receipt.isStatusOK()) {
                System.err.println("[Blockchain] Tx " + txHash + " reverted — not treating as recorded");
                return new RecordResult(RecordResult.FAILED, txHash, "Transaction reverted on-chain");
            }

            // Final authority: the contract's own ledger.
            if (!verifyEventHash(batchId, eventHash)) {
                System.err.println("[Blockchain] Tx " + txHash + " mined but event hash absent on-chain");
                return new RecordResult(RecordResult.FAILED, txHash,
                        "Transaction mined but the event was not registered by the contract");
            }

            System.out.println("[Blockchain] Event recorded for batch " + batchId + " tx=" + txHash);
            return new RecordResult(RecordResult.RECORDED, txHash, "Recorded on-chain");

        } catch (Exception e) {
            System.err.println("[Blockchain] Error recording event: " + e.getMessage());
            return new RecordResult(RecordResult.FAILED, null, e.getMessage());
        }
    }

    /**
     * Verify that a specific event exists on-chain for a batch.
     */
    public boolean verifyEvent(String batchId, String eventData) {
        if (!isAvailable()) return false;
        try {
            return verifyEventHash(batchId, new Bytes32(computeEventHash(batchId, eventData)));
        } catch (Exception e) {
            System.err.println("[Blockchain] Error verifying event: " + e.getMessage());
            return false;
        }
    }

    /** Low-level eth_call against verifyEvent(string,bytes32). */
    private boolean verifyEventHash(String batchId, Bytes32 eventHash) {
        try {
            Function function = new Function(
                    "verifyEvent",
                    Arrays.asList(new Utf8String(batchId), eventHash),
                    Collections.singletonList(new TypeReference<Bool>() {})
            );
            String encodedFunction = FunctionEncoder.encode(function);

            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(
                            credentials.getAddress(), contractAddress, encodedFunction),
                    DefaultBlockParameterName.LATEST
            ).send();

            String raw = response.getValue();
            // "0x" means no contract at the address (or a non-existent method) — never a "true".
            if (raw == null || raw.length() <= 2) {
                return false;
            }

            List<Type> results = FunctionReturnDecoder.decode(raw, function.getOutputParameters());
            if (!results.isEmpty()) {
                return (Boolean) results.get(0).getValue();
            }
            return false;

        } catch (Exception e) {
            System.err.println("[Blockchain] Error verifying event hash: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the count of on-chain events for a batch.
     */
    public int getEventCount(String batchId) {
        if (!isAvailable()) return 0;

        try {
            Function function = new Function(
                    "getEventCount",
                    Collections.singletonList(new Utf8String(batchId)),
                    Collections.singletonList(new TypeReference<Uint>() {})
            );
            String encodedFunction = FunctionEncoder.encode(function);

            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(
                            credentials.getAddress(), contractAddress, encodedFunction),
                    DefaultBlockParameterName.LATEST
            ).send();

            String raw = response.getValue();
            if (raw == null || raw.length() <= 2) {
                return 0;
            }

            List<Type> results = FunctionReturnDecoder.decode(raw, function.getOutputParameters());

            if (!results.isEmpty()) {
                return ((BigInteger) results.get(0).getValue()).intValue();
            }
            return 0;

        } catch (Exception e) {
            System.err.println("[Blockchain] Error getting event count: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Whether a transaction hash is present <em>and successful</em> on the currently active chain.
     *
     * <p>This is the discriminator between a stale txHash (previous Hardhat session — receipt no
     * longer exists) and a genuine mismatch (the transaction really ran on this chain, yet the
     * expected event hash is not registered = tampering).
     */
    public boolean isTransactionConfirmed(String txHash) {
        if (!initialized || txHash == null) return false;
        String clean = txHash.trim();
        if (clean.isEmpty() || "null".equalsIgnoreCase(clean) || !clean.startsWith("0x")) return false;
        try {
            EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(clean).send();
            if (resp.hasError()) return false;
            return resp.getTransactionReceipt()
                    .map(TransactionReceipt::isStatusOK)
                    .orElse(false);
        } catch (Exception e) {
            System.err.println("[Blockchain] Error fetching receipt for " + clean + ": " + e.getMessage());
            return false;
        }
    }

    // ── Utility ──────────────────────────────────────────────────────

    /**
     * Compute keccak256 hash of batchId + eventData.
     * This must match on both the recording and verification side.
     */
    public byte[] computeEventHash(String batchId, String eventData) {
        String combined = batchId + "|" + eventData;
        return Hash.sha3(combined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Build a canonical event data string from journey step fields.
     * This ensures consistent hashing regardless of JSON key order.
     *
     * <p>THIS IS THE SINGLE SOURCE OF TRUTH for canonical event data. Recording, verification,
     * anomaly approval and recovery/re-recording must all funnel through this method so that the
     * same journey step always produces the same {@code type|timestamp|location|description}
     * string — and therefore the same keccak256 hash.
     */
    public static String buildEventData(String type, String timestamp, String location, String description) {
        return String.join("|", safe(type), safe(timestamp), safe(location), safe(description));
    }

    /**
     * Build the canonical event data for a journey step map.
     * Null-safe: a JSON key present with a null value yields "" (never the literal "null").
     */
    public static String buildEventDataFromStep(Map<String, Object> step) {
        return buildEventData(
                stepString(step, "type"),
                stepString(step, "timestamp"),
                stepString(step, "location"),
                stepString(step, "description"));
    }

    /**
     * Null-safe extraction of a journey step field.
     * Falls back to the {@code status} field for {@code type}, matching the legacy behaviour of the
     * verification endpoint, but never produces the string {@code "null"}.
     */
    public static String stepString(Map<String, Object> step, String key) {
        if (step == null) return "";
        Object value = step.get(key);
        if (value == null && "type".equals(key)) {
            value = step.get("status");
        }
        return safe(value);
    }

    private static String safe(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        return "null".equals(s) ? "" : s;
    }

    /**
     * Convert a byte array to hex string for API responses.
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ── Internals ────────────────────────────────────────────────────

    /** Poll for a transaction receipt until timeout. Returns null when never mined. */
    private TransactionReceipt awaitReceipt(String txHash) {
        long deadline = System.currentTimeMillis() + RECEIPT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                EthGetTransactionReceipt resp = web3j.ethGetTransactionReceipt(txHash).send();
                if (resp != null && !resp.hasError()) {
                    Optional<TransactionReceipt> maybe = resp.getTransactionReceipt();
                    if (maybe != null && maybe.isPresent()) {
                        return maybe.get();
                    }
                }
            } catch (Exception e) {
                System.err.println("[Blockchain] Receipt poll failed for " + txHash + ": " + e.getMessage());
            }
            try {
                Thread.sleep(RECEIPT_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Does the address have contract bytecode on the current chain? */
    private boolean hasContractCode(String address) {
        if (web3j == null || address == null || address.isBlank()) return false;
        try {
            EthGetCode response = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send();
            if (response == null || response.hasError()) return false;
            String code = response.getCode();
            return code != null && code.length() > 2 && !"0x".equals(code);
        } catch (Exception e) {
            System.err.println("[Blockchain] eth_getCode failed for " + address + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Re-read the Hardhat deployment artifact and adopt its address when it differs.
     * Called when the configured address has no bytecode (typical after a chain reset).
     */
    private boolean refreshContractAddress() {
        try {
            String resolved = loadContractAddress();
            if (resolved == null || resolved.isBlank()) return false;
            if (resolved.equalsIgnoreCase(contractAddress)) return false;
            System.out.println("[Blockchain] Adopting deployment address " + resolved
                    + " (previous: " + contractAddress + ")");
            contractAddress = resolved;
            return true;
        } catch (Exception e) {
            System.err.println("[Blockchain] Could not refresh contract address: " + e.getMessage());
            return false;
        }
    }

    /**
     * Try to load contract address from the Hardhat deployment file.
     */
    private String loadContractAddress() {
        try {
            java.nio.file.Path userDir = java.nio.file.Path.of(System.getProperty("user.dir"));

            // Candidate locations: <backend>/../blockchain/deployments/localhost.json (normal layout)
            // and <root>/blockchain/deployments/localhost.json when launched from the repo root.
            List<java.nio.file.Path> candidates = new ArrayList<>();
            if (userDir.getParent() != null) {
                candidates.add(userDir.getParent().resolve("blockchain/deployments/localhost.json"));
            }
            candidates.add(userDir.resolve("blockchain/deployments/localhost.json"));
            candidates.add(userDir.resolve("../blockchain/deployments/localhost.json"));

            for (java.nio.file.Path deploymentFile : candidates) {
                if (!deploymentFile.toFile().exists()) continue;

                String content = new String(
                        java.nio.file.Files.readAllBytes(deploymentFile), StandardCharsets.UTF_8);
                int idx = content.indexOf("\"contractAddress\"");
                if (idx >= 0) {
                    int start = content.indexOf("\"", idx + 17) + 1;
                    int end = content.indexOf("\"", start);
                    if (start > 0 && end > start) {
                        String addr = content.substring(start, end).trim();
                        System.out.println("[Blockchain] Loaded contract address from "
                                + deploymentFile + ": " + addr);
                        return addr;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Blockchain] Could not load deployment file: " + e.getMessage());
        }
        return null;
    }
}
