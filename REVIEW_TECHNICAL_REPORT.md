# Technical Report: Farm2Market Architecture, Verification & Innovation Audit

**Date:** October 20, 2026  
**Project:** Farm2Market (AI-Based Anomaly Detection for Blockchain-Secured Agricultural Supply-Chain Traceability)  
**Target Environment:** `Farm2Market-main-backup-20261002`  
**Audit Scope:** Full system verification, exact code-level algorithm audit, resilient anomaly gate, blockchain consistency, and live readiness check.

---

## 1. System Architecture & Component Mapping

Farm2Market is a cyber-physical supply-chain provenance platform designed for agricultural products. It consists of:

```
┌────────────────────────────────────────────────────────┐
│                   React 18 + Vite SPA                   │
│   (Role Dashboards, Consumer QR Journey, Anomaly Badges)│
└───────────────────────────▲────────────────────────────┘
                            │ HTTP / JSON (port 5173 / 8080)
┌───────────────────────────┴────────────────────────────┐
│              Spring Boot 3 (Java 21 / WebMVC)           │
│   (Batch State Machine, Edge Sync, Anomaly Gating,     │
│    SHA-256 Event Hashing, Smart Contract Web3j Driver) │
└───────▲───────────────────┬────────────────────▲───────┘
        │                   │                    │
   REST │ (port 5001)  JDBC │ (PostgreSQL/Neon)  │ JSON-RPC (port 8545)
┌───────▼───────────┐ ┌─────▼──────────┐ ┌───────▼──────────────┐
│  Flask ML Service │ │  Database      │ │ Hardhat Ethereum Node│
│  - Crop Recomm.   │ │  - Products    │ │ - SupplyChain.sol    │
│  - Yield Predict. │ │  - Scan Events │ │   (O(1) Hash Map,    │
│  - IsolationForest│ │  - Anomalies   │ │    Batch Registry)   │
└───────────────────┘ └────────────────┘ └──────────────────────┘
        ▲
        │ Local SQLite Buffer & Sync (events.db)
┌───────┴───────────┐
│   IoT Edge Node   │
│ (Raspberry Pi/Sim)│
└───────────────────┘
```

---

## 2. Component Verification & Actual Test Audit Results

Every test result below was confirmed by direct command execution on the target machine:

| Component | Target Artifact / Endpoint | Execution Method | Actual Outcome | Status |
|---|---|---|---|---|
| **Hardhat Blockchain** | `SupplyChain.sol` | `npx hardhat compile` & `npx hardhat test` | 3/3 tests pass (recording, duplicate rejection, non-existent check). Deployed to `0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512`. | **PASS** |
| **Flask ML Service** | `ml_service.py` | Python 3.12 / Flask on `http://127.0.0.1:5001` | Service responds; `/health` confirms all 3 models (`rf_crop_model.pkl`, `reg_model.pkl`, `isolation_forest.pkl`) loaded. | **PASS** |
| **Crop Recommendation** | POST `/recommend` | REST POST via `urllib` with N, P, K, pH, temp, humidity, rainfall | Returns recommended crop (`Groundnut`, 52.4% confidence). | **PASS** |
| **Yield Prediction** | POST `/predict-yield` | REST POST via `urllib` with crop, season, state, area, rainfall | Returns predicted yield (`2970.86 kg/acre`, 92.5% confidence). | **PASS** |
| **Anomaly Detection API** | POST `/detect-anomaly` | REST POST via `urllib` with normal & suspicious payloads | Verified: Normal scan $\rightarrow$ `NORMAL` (0.0); Sequence violation $\rightarrow$ `SUSPICIOUS` (0.8421); Impossible travel $\rightarrow$ `SUSPICIOUS` (0.8470). | **PASS** |
| **Frontend Compilation** | `frontend/` (TypeScript / Vite) | `npm run build` (`tsc -b && vite build`) | 0 TypeScript errors. `dist/` production assets built cleanly in 17.29s. | **PASS** |
| **Frontend Dev Server** | `frontend/` | `npm run dev` (`vite --host`) | Serving on `http://localhost:5173`. | **PASS** |
| **Backend Compilation** | `backend/` (Spring Boot 3) | `.\mvnw.cmd test-compile` | 33 Java source files compile without errors on Java 21 release target. | **PASS** |
| **Anomaly Fallback Test** | `AnomalyDetectionServiceTest.java` | `.\mvnw.cmd test -Dtest=AnomalyDetectionServiceTest` | 2/2 unit tests pass: confirms `SUSPICIOUS` quarantine hold during service outage and permissive `NORMAL` when configured. | **PASS** |
| **Edge-Node SQLite** | `setup_db.py`, `sync.py`, `scanner.py` | `py_compile` & SQLite execution | Python syntax valid; `events.db` schema and indexes initialized. | **PASS** |
| **Edge-Node Hardware** | Physical Raspberry Pi camera | Physical inspection | Physical Pi hardware not connected to this PC. Tested via SQLite buffer and Python script syntax. | **NOT TESTED** |
| **Neon PostgreSQL** | Remote Database connection | Environment inspection | Blocked by missing `DB_URL` and `DB_PASSWORD` variables in user environment. | **BLOCKED** |
| **Spring Boot Live HTTP** | Port 8080 | Java execution | Blocked solely by missing database credentials. | **BLOCKED** |
| **Geoapify Autocomplete** | LocationService | Environment inspection | Missing `GEOAPIFY_API_KEY`. Safely handled with fallback in `LocationService.java`. | **BLOCKED** |

---

## 3. Exact Anomaly Detection Algorithm (Verified Against Source Code)

### 3.1 Dual-Engine Scoring Architecture

The anomaly detection engine in `ml_service.py` is a **hybrid rule-based and machine-learning verification pipeline**:

1. **Deterministic Rule Checks (`_run_rule_checks`)**:
   - **R1: Future Timestamp**: If event time is $> 60\text{ s}$ ahead of server clock (weight: 0.85).
   - **R2: Prior-to-Creation Timestamp**: If event is $> 10\text{ s}$ before batch creation (weight: 0.90).
   - **R3: Role Sequence Violation**: Validates transition against `ALLOWED_ROLE_FOR_STATUS`:
     - `CREATED`: `FARMER`, `TRANSPORTER`
     - `BOOKED_TRANSPORT`: `FARMER`, `TRANSPORTER`
     - `IN_TRANSIT`: `TRANSPORTER`, `SELLER`
     - `AT_SELLER`: `SELLER`, `FARMER`
     - `SOLD`: `SELLER`
     - Mismatches trigger a sequence violation finding (weight: 0.70).
   - **R4: Burst Detection**: $\ge 5$ scans within a 10-minute window (weight: 0.65).
   - **R5: Rapid Duplicate**: Same node ID and event type within 5 minutes (weight: 0.80).
   - **R6: Off-Hours Scan**: Scans between 23:00 and 04:00 UTC (weight: 0.20).
   - **R7: Impossible Location Jump**: Calculates Haversine distance and elapsed time between consecutive scans. Speeds $> 120\text{ km/h}$ trigger an impossible travel finding (weight: 0.75).
   - **Rule Score**:
     $$\text{Score}_{\text{rule}} = \min\left(1.0, \sum \text{weights}\right)$$

2. **Machine Learning Model (`_ml_anomaly_score`)**:
   - **Algorithm**: `IsolationForest` (Ensemble of Isolation Trees) combined with `StandardScaler` inside a scikit-learn `Pipeline`.
   - **Training Data Disclosure**: Trained on a **5,000-sample synthetic supply-chain dataset** via `train_anomaly_model.py`. It is a prototype model calibrated on domain distributions, not on empirical production field logs.
   - **Input Features (8 features in exact order)**:
     1. `hour_of_day` (float, 0.0 – 23.99)
     2. `time_since_last_h` (elapsed hours since prior scan, default 24.0, max 720.0)
     3. `existing_event_count` (integer event depth)
     4. `node_role_enc` (Farmer=0, Transporter=1, Seller=2)
     5. `product_status_enc` (Created=0, Booked=1, InTransit=2, AtSeller=3, Sold=4, Assigned=5, PartiallySold=6, Rejected=7)
     6. `events_in_last_10min` (burst count in last 10 minutes)
     7. `time_since_creation_h` (elapsed hours since batch inception)
     8. `role_status_mismatch` (binary indicator: 0 if valid pair, 1 if sequence violation)
   - **Raw Score Transformation**:
     $$\text{Score}_{\text{ml}} = \frac{1}{1 + e^{8 \cdot \text{decision\_function}(X)}}$$

3. **Score Fusion Formula**:
   $$\text{Combined Score} = \min\left(1.0, 0.70 \times \text{Score}_{\text{rule}} + 0.30 \times \text{Score}_{\text{ml}}\right)$$

4. **Classification Thresholds**:
   - **NORMAL** ($\text{Score} < 0.50$): Event proceeds to database and blockchain commit.
   - **SUSPICIOUS** ($0.50 \le \text{Score} < 0.90$): Event is stored in PostgreSQL with `status: PENDING_REVIEW`. An `AnomalyFlagEntity` is persisted, farmer/admin notification is created, and **blockchain commitment is held**.
   - **REJECT** ($\text{Score} \ge 0.90$): Event is rejected; status set to `REJECTED`, never written to blockchain.

---

## 4. Verified Technical Innovation: Resilient Fallback Gatekeeper

### 4.1 Identified Vulnerability
In the baseline code, `AnomalyDetectionService.java` contained a dangerous fail-safe:
```java
// Original vulnerable behavior:
} catch (Exception e) {
    System.err.println("[Anomaly] Detection service unavailable... — defaulting to NORMAL (fail-safe)");
    return normalResult("Detection service unavailable");
}
```
If the ML service was unreachable, timed out, or crashed, incoming events bypassed anomaly inspection and were directly submitted to Ethereum. Once on-chain, corrupted or forged data could never be removed.

### 4.2 Implemented & Verified Solution
1. **Configurable Security Policy**: Added `anomaly.detection.fallback-policy=SUSPICIOUS` to `application.properties`.
2. **Quarantine on Service Failure**: When `ml_service.py` is down or returns a non-200 error code:
   - Under `SUSPICIOUS` policy (default): Returns `decision: SUSPICIOUS`, score `0.70`, and reason `Fallback-security-hold: ML anomaly service unreachable — held for administrator verification`.
   - The event is preserved in PostgreSQL for manual inspection and withheld from the blockchain.
   - Under `NORMAL` policy: Allows permissive operation when explicitly configured.
3. **Automated Verification**: `AnomalyDetectionServiceTest.java` was executed via Maven (`.\mvnw.cmd test -Dtest=AnomalyDetectionServiceTest`), confirming both `SUSPICIOUS` and `NORMAL` fallback policies pass (2/2 passing).

---

## 5. Blockchain & Contract Consistency

1. **Ephemeral Local Hardhat Behavior**:
   - Restarting `npx hardhat node` resets blockchain state to block 0.
   - Contract must be redeployed after node restart:
     ```bash
     npx hardhat run scripts/deploy.js --network localhost
     ```
   - Deployment writes the current address to `blockchain/deployments/localhost.json`.
   - `BlockchainService.java` automatically resolves the contract address from `localhost.json` on startup.
2. **Current Contract Address**: `0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512`
3. **On-Chain Cryptographic Integrity**:
   - `SupplyChain.sol` stores `keccak256(eventData)` where event data is canonically derived as `type|timestamp|location|description`.
   - Enforces $O(1)$ duplicate rejection via `mapping(string => mapping(bytes32 => bool)) eventExists`.
   - All 3 smart contract unit tests passed via `npx hardhat test`.

---

## 6. Limitations & Future Work

1. **Synthetic Training Data**: The Isolation Forest model was trained on 5,000 synthetic records generated with domain constraints. Future work should train on commercial sensor and telemetry streams.
2. **Edge-Level Pre-Filtering**: Anomaly detection currently executes at the backend layer upon sync. Future work: compile the Isolation Forest to ONNX/TFLite and execute it locally on the Raspberry Pi edge node.
3. **Decentralized Multi-Sig Governance**: Currently, single-admin approval releases quarantined events. A decentralized multi-signature smart contract approval mechanism would improve governance.
