# Faculty Review Presentation Notes: Farm2Market

Use these concise, defensible speaking notes during your project viva/review.

---

## 1. Problem Statement

Traditional agricultural supply chains face three fundamental challenges:
1. **Lack of Provenance Transparency**: Consumers cannot verify farm origin, harvest date, transit conditions, or handling checkpoints.
2. **Post-Hoc Data Tampering**: Traditional databases are centralized and vulnerable to retrospective manipulation.
3. **The "Garbage In, Garbage Out" Blockchain Dilemma**: Blockchains guarantee *immutability*, but do not guarantee *truthfulness*. If a fraudulent, spoofed, or corrupted scan is recorded on an immutable ledger, it becomes permanently immutable.

---

## 2. Proposed System & Architecture

Farm2Market resolves this by coupling **IoT physical checkpoints**, **pre-blockchain AI anomaly gating**, and **Ethereum smart contracts**:
- **IoT Edge Nodes**: Capture physical QR scans with operator IDs, GPS coordinates, and environmental telemetry.
- **Pre-Blockchain AI Gatekeeper**: Every event is inspected by a hybrid anomaly engine (rule-based physical constraints + machine-learned Isolation Forest) *before* reaching the blockchain.
- **Selective Blockchain Commitment**:
  - `NORMAL` events are hashed (`keccak256`) and committed to `SupplyChain.sol`.
  - `SUSPICIOUS` events are quarantined in the database with a notification sent to administrators/farmers, and strictly withheld from the blockchain until reviewed.
  - `REJECT` events are discarded entirely.

---

## 3. The Algorithm: Exact Code-Level Mechanics

- **Why Unsupervised Learning?** Real agricultural supply-chain anomalies and fraudulent scans are rare, diverse, and lack large-scale labeled datasets. Supervised classifiers would overfit to historical examples.
- **Why Isolation Forest?** Rather than calculating normal density distributions, Isolation Forest isolates anomalies by randomly partitioning feature space. Anomalies require significantly fewer random splits to isolate (shorter tree depth).
- **Exact Features (8 Features)**:
  1. `hour_of_day` (circadian operational pattern)
  2. `time_since_last_h` (elapsed hours since prior scan)
  3. `existing_event_count` (sequential journey depth)
  4. `node_role_enc` (Farmer, Transporter, Seller)
  5. `product_status_enc` (Created, InTransit, AtSeller, Sold, etc.)
  6. `events_in_last_10min` (burst frequency)
  7. `time_since_creation_h` (batch age)
  8. `role_status_mismatch` (binary state transition feasibility)
- **Score Fusion**:
  $$\text{Combined Score} = \min\left(1.0, 0.70 \times \text{Score}_{\text{rule}} + 0.30 \times \text{Score}_{\text{ml}}\right)$$
  - $\text{Score} < 0.50 \rightarrow \text{NORMAL}$ (auto-committed to blockchain)
  - $0.50 \le \text{Score} < 0.90 \rightarrow \text{SUSPICIOUS}$ (held for human review)
  - $\text{Score} \ge 0.90 \rightarrow \text{REJECT}$ (permanently discarded)

---

## 4. Technical Innovation: Resilient Fallback Gatekeeper

- **Baseline Flaw**: The original code defaulted to marking events as `NORMAL` if the ML microservice crashed or timed out, which allowed unvetted data to enter the blockchain during service downtime.
- **Implemented Fix**: We implemented a configurable security-first fallback policy (`anomaly.detection.fallback-policy=SUSPICIOUS`).
- When the ML microservice is unavailable, the backend automatically flags the event as `SUSPICIOUS` with a quarantine security hold, preventing uninspected data from polluting the blockchain.
- Verified with 2 automated unit tests in `AnomalyDetectionServiceTest.java`.

---

## 5. Anticipated Faculty Questions & Model Answers

### Q1: Why not execute anomaly detection inside the Solidity smart contract?
> **Answer**: Ethereum smart contracts run on the EVM where computational operations consume gas. Floating-point mathematics, decision tree traversals, and trigonometric distance calculations (Haversine) are prohibitively expensive on-chain. Our architecture follows the established enterprise pattern of **off-chain compute, on-chain verification**: AI scoring and validation happen off-chain, and only the tamper-proof cryptographic event hash is committed to the blockchain.

### Q2: What happens if an attacker launches a Denial-of-Service attack on the ML service?
> **Answer**: With our resilient fallback policy, if the ML service is unreachable, the system fails secure. Instead of admitting unverified events, the backend automatically assigns a `SUSPICIOUS` quarantine hold. Events are stored in PostgreSQL for manual administrative review and cannot touch the blockchain until signed off.

### Q3: What dataset was used to train the Isolation Forest model?
> **Answer**: The Isolation Forest was trained on a 5,000-sample synthetic supply-chain dataset generated using domain constraints (physical speed limits, role progression sequences, and realistic temporal distributions). In future work, this prototype will be calibrated against empirical commercial logistics telemetry.

### Q4: How does the consumer verify the product in the browser?
> **Answer**: The consumer scans the packaging QR code, loading the batch journey from PostgreSQL. For each step, the web client queries `SupplyChain.sol`'s `verifyEvent(batchId, eventHash)` view function. If the on-chain hash matches the journey step, a green verified badge appears. If an event was modified in the database, the hash check fails and flags it as tampered.

### Q5: How does the edge node handle offline environments?
> **Answer**: The Raspberry Pi edge nodes run an embedded SQLite database (`events.db`). When offline, scans are queued locally (`synced = 0`). Once network connectivity is restored, the `sync.py` synchronization worker pushes the backlog to the Spring Boot `/api/edge/sync` endpoint.
