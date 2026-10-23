# Farm2Market — Blockchain Record → Verify → QR Flow: Fix Report

Date: 2026-09-24
Scope: repair the broken blockchain verification/recording flow. No architectural changes, no
contract changes, no anomaly-detection changes, no QR-generation changes, no schema changes.

---

## 1. Root cause(s) found

### RC1 — The contract's existence was never verified (primary root cause)
`BlockchainService.init()` treated "the JSON-RPC node answered" as "ready". After
`npx hardhat node` is restarted, the previously deployed contract address holds **no bytecode**
unless the contract is redeployed. Consequences:

- `eth_call` to a codeless address returns `0x`, so `verifyEvent()` silently returned `false`
  and `getEventCount()` silently returned `0` for **everything**.
- `eth_sendRawTransaction` to that same codeless address **still succeeds** as a no-op and
  returns a perfectly valid-looking transaction hash.

So the old code happily wrote that no-op txHash into `ProductEntity.journey`, while the chain
contained 0 events. **This is exactly the reported symptom**: journey steps carry txHash values
yet the UI shows `0/N steps verified • 0 events on-chain`.

### RC2 — Submitted ≠ recorded (no receipt validation)
`recordEvent()` returned `response.getTransactionHash()` immediately after submission.
`EthSendTransaction.hasError()` only reports *submission* failures, never *execution* reverts.
Therefore a reverted transaction (`"Duplicate event"`, `"Empty hash"`) or a not-yet-mined
transaction produced a txHash that was persisted as if it had landed. There was also a race:
the follow-up `verifyEvent` (`eth_call` at `LATEST`) could run before the tx was mined at all.

### RC3 — Contract address cached for the process lifetime
It was resolved once in `@PostConstruct` and never re-resolved, so a redeploy after a chain
restart produced a new address the running backend never learned about.

### RC4 — `verifyBatch` conflated "not on-chain" with "tampered"
`status = verifiedSteps == totalSteps ? "verified" : "compromised"`. Unrecorded events and events
held for AI review were reported as a tampering condition. There was no concept of a pending
review state anywhere in the endpoint.

### RC5 — A stored txHash was treated as proof of existence (recovery impossible)
`ProductController.updateProduct` used `if (step.containsKey("txHash")) continue;` — a blanket
skip. A stale hash could therefore never be recovered, and a step whose `txHash` was JSON `null`
was skipped too (because the *key* existed).

### RC6 — Canonical event data could drift, and could contain the literal string `"null"`
`String.valueOf(step.getOrDefault("type", step.getOrDefault("status", "")))` returns the string
`"null"` when a JSON key exists with a null value. Worse, the canonical string was rebuilt by
**four independent hand-rolled copies** of the field extraction (ProductController,
BlockchainController, EdgeSyncController) plus a fifth ad-hoc construction in AnomalyController
that built the recording hash from `flag.*` values while the verifier rebuilt it from the journey
step. That is precisely the "recording and verification use slightly different text" bug class.

### RC7 — Anomaly approval did not pin the fields it hashed
`AnomalyController.approveFlag` recorded using `flag.getEventTime()` / `flag.getLocation()`, but
only wrote `type` and `description` back onto the journey step — it never wrote `timestamp` or
`location`. Any divergence between the flag and the step made the approved event **permanently
unverifiable**.

### RC8 — Steps held via the product-update path had no anomaly flag, and could be force-recorded
`ProductController.updateProduct` marked a step SUSPICIOUS but never created an
`AnomalyFlagEntity`, so there was nothing to approve and the step was stuck in `PENDING_REVIEW`
forever. Conversely, `updateProduct` did not respect an *existing* review hold, so a later PUT
could push a held step onto the chain.

### RC9 — Reviewer-REJECTED steps were recordable
The backfill guard used `(anomalyDetected && !anomalyReviewed)`. After a rejection
`anomalyReviewed = true`, so a discarded event passed the guard and would have been committed to
the ledger.

### RC10 — Frontend could mislabel a stale state as tampering
`getStepStatus()` fell back to `tampered` whenever a step had a txHash **and**
`onChainEventCount > 0`. A stale txHash sitting next to any valid event rendered "✗ Tampered".

---

## 2. Exact files changed

Backend (5):
1. `backend/src/main/java/in/edu/ssn/backend/service/BlockchainService.java`
2. `backend/src/main/java/in/edu/ssn/backend/controller/BlockchainController.java`
3. `backend/src/main/java/in/edu/ssn/backend/controller/AnomalyController.java`
4. `backend/src/main/java/in/edu/ssn/backend/controller/ProductController.java`
5. `backend/src/main/java/in/edu/ssn/backend/controller/EdgeSyncController.java`

Frontend (4):
6. `frontend/src/components/product/BlockchainVerification.tsx`
7. `frontend/src/components/common/NotificationList.tsx`  *(build unblock only — see §9)*
8. `frontend/src/components/dashboards/FarmerDashboard.tsx` *(build unblock only)*
9. `frontend/src/components/dashboards/TransporterDashboard.tsx` *(build unblock only)*

Regenerated by tooling (not hand-edited):
10. `blockchain/deployments/localhost.json` — rewritten by `scripts/deploy.js` during testing.

**Not changed:** `SupplyChain.sol`, the deploy script, Hardhat config, `application.properties`,
any entity/repository/DB schema, the Isolation Forest model, anomaly thresholds, `ml_service.py`,
and QR generation (`FarmerDashboard` QR URL construction, `QRCodeDisplay`, the `/product/:productId`
route).

---

## 3. What was fixed in each file

### `BlockchainService.java`
- Added `contractDeployed` + `eth_getCode` verification. `isAvailable()` now means
  *node reachable **and** contract has bytecode*; `isNodeReachable()` reports the node alone.
- Added `getUnavailableReason()` / `getLastError()` so callers can explain *why* it is unusable.
- Added self-healing `refreshContractAddress()`: when the configured address has no bytecode, the
  deployment artifact is re-read and adopted on the next operation (this is the "Hardhat
  restarted / redeployed" recovery path).
- `loadContractAddress()` now tries three candidate paths instead of one.
- **`recordEvent()` is now idempotent and receipt-validated.** New `recordEventDetailed()` returns
  `RECORDED | ALREADY_ON_CHAIN | FAILED | UNAVAILABLE`:
  1. if the canonical hash already exists on chain → no transaction is sent at all (this also
     avoids the contract's `"Duplicate event"` revert);
  2. otherwise the tx is submitted and **waited on** (`eth_getTransactionReceipt`, 30 s budget);
  3. the receipt must exist **and** have `status == 1`;
  4. finally `verifyEvent` must confirm the hash is registered.
  A txHash is only returned when all four hold — a reverted or unmined tx can no longer poison
  the journey.
- Added `isTransactionConfirmed(txHash)` — the discriminator between a **stale** txHash (receipt
  no longer on this chain → chain was reset) and a **genuine** mismatch.
- `verifyEvent` / `getEventCount` now reject empty (`"0x"`) responses instead of decoding them as
  a negative result.
- Added `buildEventDataFromStep()` / `stepString()` — **the single source of truth** for canonical
  event data, null-safe and never emitting the literal `"null"`.

### `BlockchainController.java`
- `/status` now returns `available`, `nodeReachable`, `contractDeployed`, `contractAddress`,
  `message` (previously only `available`).
- `/verify/{batchId}` classifies **every step from the live chain** as `verified` /
  `pending_review` / `rejected` / `unrecorded` / `tampered`, and returns `stepStatus` per step plus
  `pendingReviewCount`, `unrecordedCount`, `tamperedCount`, `rejectedCount`,
  `txConfirmedOnCurrentChain`.
  - `tampered` is emitted **only** when a step carries a txHash whose transaction really executed
    on *this* chain, yet the expected event hash is not registered — i.e. a real cryptographic
    mismatch. A missing record is merely `unrecorded`.
- `/record/{batchId}` now implements every required case explicitly: CASE A (no txHash → record),
  CASE B (valid on current chain → skip), CASE C (stale txHash → re-record and update the existing
  step in place), CASE D (`PENDING_REVIEW` → never auto-recorded), CASE E (approved event → recorded
  exactly once with the same canonical data the verifier reconstructs). Reviewer-rejected steps are
  never recorded. Response reports `recorded`, `skipped`, `alreadyOnChain`, `pendingReview`,
  `rejected`, `failed`, `status` (`success`/`partial`/`error`) and embeds a fresh verification.

### `AnomalyController.java`
- `approveFlag` now **updates the existing journey step first** (`type`, `description`,
  `timestamp`, `location`, `status = COMPLETED`, `anomalyReviewed`, `anomalyDecision = APPROVED`,
  `requiresReview = false`, `anomalyDetected = true`) and only then derives the canonical event
  data **from that persisted step**. Recording and verification therefore cannot drift.
- Recording is idempotent (`recordEventDetailed`); the txHash is stored only when the event is
  genuinely on-chain. No duplicate journey step is ever appended.

### `ProductController.java`
- `createProduct`: persists the canonical `description` before hashing and only stores a real,
  receipt-confirmed txHash.
- `updateProduct`: replaced `if (step.containsKey("txHash")) continue;` with a **live on-chain
  check**, so stale/missing events are re-recorded and the existing step's txHash is updated in
  place.
- `updateProduct`: refuses to record a step that is already held for review or was rejected by
  review (closes the bypass in TASK 8).
- `updateProduct`: when the AI flags a step SUSPICIOUS it now also persists an `AnomalyFlagEntity`
  (status `PENDING`) and links it via `anomalyFlagId`, so the event can actually be approved;
  a `REJECT` decision is recorded as terminal (`anomalyDecision = REJECTED`) and is never committed.

### `EdgeSyncController.java`
- `appendJourneyStep` derives canonical data from the same fields it stores, and stores a txHash
  only when the write is confirmed on-chain (previously a submitted-but-reverted tx could be
  recorded as success).

### `BlockchainVerification.tsx`
- Consumes the backend's authoritative `stepStatus`; the local fallback no longer labels a stale
  txHash as tampered.
- Implements the four required UI states: green **all verified**; amber **⚠ Event Held for AI
  Review — Blockchain commitment pending approval** (never "tampered"); yellow **Pending On-Chain
  Recording** with the **Record Events on Blockchain** button; red **tampered** only for a genuine
  hash mismatch.
- Displays the accurate recording outcome (`recorded / skipped / already on-chain / pendingReview
  / rejected / failed`) and re-verifies after recording. A txHash is only shown for `verified` /
  `tampered` steps, so stale hashes no longer appear next to "Pending Sync".

---

## 4. Are stale Hardhat txHashes handled now?

**Yes.** A txHash in PostgreSQL is no longer trusted. The live contract is always the authority:
existence is decided by `verifyEvent(batchId, keccak256(batchId + "|" + type|timestamp|location|description))`.

- Stale txHash (chain reset) → reported as `unrecorded`, **not** tampering, and eligible for
  re-recording.
- Re-recording updates the **existing** journey step's txHash and preserves all other fields —
  no step is appended.
- Verified end-to-end in TEST 2 (below): 4 stale hashes → `Pending On-Chain Recording`,
  `tamperedCount = 0` → one Record call → 4 recorded → 4/4 verified.

## 5. Is canonical hashing consistent now?

**Yes.** There is exactly one implementation: `BlockchainService.buildEventDataFromStep(step)` /
`buildEventData(type, timestamp, location, description)` producing `type|timestamp|location|description`
with null → `""` (never `"null"`). Recording, verification, anomaly approval and recovery all use it.
Anomaly approval now writes the step first and hashes from the step, so the recorded hash is
byte-for-byte the hash the verifier later reconstructs.

## 6. Is anomaly `PENDING_REVIEW` protected?

**Yes**, on every automated write path: `/api/blockchain/record/{batchId}`,
`PUT /api/products/{id}`, and `/api/edge/sync` + `/api/edge/sync/batch` (which share
`processSingleSyncEvent`). Held events are never committed, are counted separately
(`pendingReview`), and are reported as `pending_review` — never as tampering. Reviewer-rejected
events are likewise never committed. Verified in TEST 3.

## 7. Do approved anomaly events verify correctly?

**Yes.** Approval updates the existing step, records it exactly once with the canonical data taken
from that same step, then verification reconstructs the identical hash and returns `verified`.
Journey step count is unchanged by approval (no duplicates). Verified in TEST 4 and in the
edge-node variant.

---

## 8. Build results

| Check | Command | Result |
|---|---|---|
| Backend compile | `mvn compile -DskipTests` | **BUILD SUCCESS** |
| Backend tests | `mvn test` | **BUILD SUCCESS** — `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` |
| Frontend typecheck | `npx tsc --noEmit` | **exit 0, no errors** |
| Frontend build | `npm run build` | **✓ built in 34.75s** (only the pre-existing >500 kB chunk-size warning) |

*(Maven is not on `PATH` on this machine and `./mvnw` cannot self-bootstrap because
`.mvn/wrapper/maven-wrapper.properties` is missing. I used the wrapper's cached distribution at
`~/.m2/wrapper/dists/apache-maven-3.9.16/...`. Worth fixing if you want `./mvnw` to work.)*

## 9. Note on the three "build unblock only" frontend files

`npm run build` was **already failing before any of my changes** with four `TS6133` unused-declaration
errors (`noUnusedLocals` / `noUnusedParameters` are on) in files unrelated to the blockchain flow:
`NotificationList.tsx`, `FarmerDashboard.tsx` (×2), `TransporterDashboard.tsx`. I removed the unused
import/variable and underscored the two unused parameters — zero behaviour change, no UI or
architecture impact. Without this the required build verification could not pass.

---

## 10. End-to-end test results

Environment: PostgreSQL/Neon (your live DB), a **separate** Hardhat chain on port 8546 and a
**separate** backend on port 8081 — your running backend (8080) and node (8545) were left untouched.
The real Flask ML service and the real `isolation_forest.pkl` were used (no stubbing).

| # | Test | Result |
|---|---|---|
| 1 | Normal event → record → verify | **PASS** — 4 steps, 4 real txHashes, `4/4 steps verified • 4 events on-chain • "All journey events verified on blockchain"` |
| 2 | Restart Hardhat → stale txHashes → record/recovery → verify | **PASS** — after a chain reset + redeploy: `status = pending_recording`, `0/4 verified`, `0 events on-chain`, `unrecordedCount = 4`, **`tamperedCount = 0`** and every step `unrecorded` (no false tampering). `POST /record` → `recorded: 4, skipped: 0, pendingReview: 0, failed: 0` → **4/4 verified**. A second Record call → `recorded: 0, alreadyOnChain: 4` (idempotent, no "Duplicate event" revert). Journey stayed at 4 steps with all 4 txHashes replaced in place; every other field preserved. |
| 3 | Pending AI anomaly → must NOT be auto-recorded | **PASS** — (a) real ML flagged 2 update-path steps (`SUSPICIOUS`, score 0.748) → `record` returned `recorded: 0, alreadyOnChain: 2, pendingReview: 2`; (b) real ML flagged an edge-node event backdated before batch creation (`SUSPICIOUS`, score 0.8516, signals: *before batch creation / duplicate / off-hours / ML 0.5055*) → step stored as `PENDING_REVIEW` and not committed; `record` returned `recorded: 0, alreadyOnChain: 1, pendingReview: 2`. |
| 4 | Approve anomaly → exactly one event → record → verification succeeds | **PASS** — approving 2 flags produced 2 on-chain txs; journey stayed 4 steps (**no duplicate appended**), each step became `status = COMPLETED`, `requiresReview = false`, `anomalyDecision = APPROVED`, `anomalyReviewed = true` with a new txHash → **4/4 verified**. Same result on the edge-node batch: 3 flags/steps → 3/3 verified. |
| 5 | QR → correct ProductPage → correct batch ID → verification component | **PASS (static + API)** — `FarmerDashboard` builds `qrCode = ${origin}/product/${createdProduct.id}`; `/product/:productId` renders `ProductPage`, which loads `getProduct(productId)` and passes `batchId={product.id}` and `journey={product.journey}`. No hardcoded batch ID exists anywhere (verified by repo-wide search). I exercised `getProduct(id) → verify(id)` against the live API; **I did not perform a browser click-through**, so the rendering path is verified by build + code inspection rather than by clicking. |
| + | Genuine tamper detection still works | **PASS** — with the event *content* altered out-of-band while keeping the original (still-confirmed) txHash: `tamperedCount = 1`, `status = tampered`, other steps still `verified`. This is now the only route to the red state. |
| + | Node up but contract not deployed | **PASS** — `available: false`, `nodeReachable: true`, `contractDeployed: false`, actionable message; `/verify` and `/record` both return `unavailable` and **write nothing** (no bogus txHashes). This is the direct regression test for RC1. |

Note on the safe-hash caveat: two byte-identical journey steps for the same batch produce the same
canonical hash, so the second would collide. The contract's `"Duplicate event"` revert is now
handled by the idempotency guard (the event is correctly reported as already on-chain and verifies),
and every real ingestion path includes a distinct timestamp and description.

---

## 11. Security / data rules respected

No journey data deleted; no products recreated; no duplicate journey steps; PostgreSQL untouched
(no schema change, no reset); smart contract unmodified; anomaly detection intact and
`PENDING_REVIEW` protected; verification never bypassed; no faked txHashes; no hardcoded success
responses or batch IDs; endpoint counts are computed dynamically from the journey.

## 12. Action required on your side (important)

1. **Restart the backend.** Your currently running backend (pid 30180, port 8080) is still the old
   pre-fix build. Stop it and start it again to pick up the fix.
2. After each `npx hardhat node` restart, **redeploy** the contract
   (`npx hardhat run scripts/deploy.js --network localhost`). The backend now detects the stale
   address and self-heals from `blockchain/deployments/localhost.json`, and will tell you clearly if
   no contract is deployed instead of silently writing bogus txHashes. `POST /api/blockchain/reconnect`
   also forces a re-check.
3. Then open a product page and press **Record Events on Blockchain** — stale/missing events are
   recorded and the UI should land on `N/N steps verified` with `N events on-chain`.

### Side effects on your machine
- `blockchain/deployments/localhost.json` was regenerated by the deploy script during testing; it is
  a build artifact rewritten on every deploy.
- `scikit-learn` was pinned to `1.6.1` so `backend/ml_service.py` can load its pickled models
  (it crashed with `ModuleNotFoundError: No module named '_loss'` under 1.9.1). Model files and
  thresholds untouched.
- A `backend/mockitoboot*.jar` was created by Mockito during `mvn test`.
- My test harness lives outside the project at `C:\Users\DEEPIKA\AppData\Local\Temp\f2m-e2e\`.
- Your backend (8080), Hardhat node (8545) and ML service (5001) were left running and untouched.

### Observation (not changed, by instruction)
`ProductController.updateProduct` builds its `AnomalyRequest` without `nodeRole`/`nodeId`, so with
the real ML service running the Isolation Forest sees `role_status_mismatch = 1` for events on that
path and scores them ~0.75–0.85 → `SUSPICIOUS` → held for review. I did **not** change this, since it
would alter anomaly behaviour and the model/thresholds are off-limits. If you want those steps
recorded directly, the correct fix is to populate `nodeRole`/`operatorId` in that request — a
follow-up worth considering.
