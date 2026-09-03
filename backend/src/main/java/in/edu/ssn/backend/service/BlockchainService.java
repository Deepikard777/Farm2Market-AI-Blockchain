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
 */
@Service
public class BlockchainService {

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
    private boolean initialized = false;

    @PostConstruct
    public void init() {
        try {
            // Try to load contract address from deployment file if not configured
            if (contractAddress == null || contractAddress.isEmpty()) {
                contractAddress = loadContractAddress();
            }

            if (contractAddress == null || contractAddress.isEmpty()) {
                System.err.println("[Blockchain] No contract address configured. Blockchain features disabled.");
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
        } catch (Exception e) {
            System.err.println("[Blockchain] Failed to initialize: " + e.getMessage());
            System.err.println("[Blockchain] Blockchain features disabled. Start Hardhat node and redeploy.");
        }
    }

    public boolean isAvailable() {
        return initialized;
    }

    // ── Core Operations ──────────────────────────────────────────────

    /**
     * Record a supply chain event on the blockchain.
     *
     * @param batchId   The product batch ID
     * @param eventData Concatenated string of event details (type + timestamp + location + description)
     * @return Transaction hash, or null if blockchain is unavailable
     */
    public String recordEvent(String batchId, String eventData) {
        if (!initialized) {
            System.out.println("[Blockchain] Skipped recording — not initialized");
            return null;
        }

        try {
            byte[] hash = computeEventHash(batchId, eventData);
            Bytes32 eventHash = new Bytes32(hash);

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
                System.err.println("[Blockchain] Tx error: " + response.getError().getMessage());
                return null;
            }

            String txHash = response.getTransactionHash();
            System.out.println("[Blockchain] Event recorded for batch " + batchId + " tx=" + txHash);
            return txHash;

        } catch (Exception e) {
            System.err.println("[Blockchain] Error recording event: " + e.getMessage());
            return null;
        }
    }

    /**
     * Verify that a specific event exists on-chain for a batch.
     */
    public boolean verifyEvent(String batchId, String eventData) {
        if (!initialized) return false;

        try {
            byte[] hash = computeEventHash(batchId, eventData);
            Bytes32 eventHash = new Bytes32(hash);

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

            List<Type> results = FunctionReturnDecoder.decode(
                    response.getValue(), function.getOutputParameters());

            if (!results.isEmpty()) {
                return (Boolean) results.get(0).getValue();
            }
            return false;

        } catch (Exception e) {
            System.err.println("[Blockchain] Error verifying event: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the count of on-chain events for a batch.
     */
    public int getEventCount(String batchId) {
        if (!initialized) return 0;

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

            List<Type> results = FunctionReturnDecoder.decode(
                    response.getValue(), function.getOutputParameters());

            if (!results.isEmpty()) {
                return ((BigInteger) results.get(0).getValue()).intValue();
            }
            return 0;

        } catch (Exception e) {
            System.err.println("[Blockchain] Error getting event count: " + e.getMessage());
            return 0;
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
     */
    public static String buildEventData(String type, String timestamp, String location, String description) {
        return String.join("|",
                type != null ? type : "",
                timestamp != null ? timestamp : "",
                location != null ? location : "",
                description != null ? description : ""
        );
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

    /**
     * Try to load contract address from the Hardhat deployment file.
     */
    private String loadContractAddress() {
        try {
            // Look for deployment file relative to backend directory
            java.nio.file.Path deploymentFile = java.nio.file.Path.of(
                    System.getProperty("user.dir"))
                    .getParent()
                    .resolve("blockchain/deployments/localhost.json");

            if (!deploymentFile.toFile().exists()) {
                // Also try relative to project root
                deploymentFile = java.nio.file.Path.of(
                        System.getProperty("user.dir"),
                        "../blockchain/deployments/localhost.json");
            }

            if (deploymentFile.toFile().exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(deploymentFile));
                // Simple JSON parsing to extract contractAddress
                int idx = content.indexOf("\"contractAddress\"");
                if (idx >= 0) {
                    int start = content.indexOf("\"", idx + 17) + 1;
                    int end = content.indexOf("\"", start);
                    String addr = content.substring(start, end);
                    System.out.println("[Blockchain] Loaded contract address from deployment file: " + addr);
                    return addr;
                }
            }
        } catch (Exception e) {
            System.err.println("[Blockchain] Could not load deployment file: " + e.getMessage());
        }
        return null;
    }
}
