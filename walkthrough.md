# Farm2Market — Demo Walkthrough & Implemented Architecture

> Blockchain-Secured, IoT-Enabled Farm-to-Consumer Supply Chain Traceability Platform.
>
> This document describes **what is actually implemented in the code today**. Anything that is
> claimed but not implemented is called out explicitly in
> [Poster Claims vs. Actual Implementation](#poster-claims-vs-actual-implementation).

---

## 1. Mam Demo Checklist

Run through this in order. Steps 1–5 need no AI service; steps 6–11 exercise the anomaly-review path.

| # | Step | What to do | What to point out |
|---|---|---|---|
| 1 | **Create batch** | Sign in as a FARMER → **Create New Batch** (name + quantity) → submit. | The batch gets a **server-generated UUID** (`POST /api/products`); the farmer never types an ID. The first journey step (`LOCATION`, "Batch created by …") is written and immediately recorded on-chain. |
| 2 | **Generate QR** | The dashboard renders the QR for the new batch (**Product QR Code** card → *Download QR Code*). | `qrCode = <current origin>/product/<productId>` — built from the real ID at runtime. There is **no hardcoded batch ID** anywhere in the codebase. |
| 3 | **Open public product page** | Scan the QR with a phone (or open the URL). **No login required.** | `/product/:productId` is a public route; `ProductPage` does a direct lookup via `GET /api/products/{id}`. This is the consumer-facing traceability page. |
| 4 | **Show journey** | Scroll to **Supply Chain Journey**. | Chronological timeline rendered from `ProductEntity.journey` (JSON), with farmer → transporter → seller → sold steps, prices, and per-step anomaly badges. |
| 5 | **Show blockchain verification** | Scroll to **Blockchain Verification** → press **Verify Journey Integrity** (auto-verifies on load). | Shows `N/N steps verified • N events on-chain` with a green state. Each step is verified by **recomputing the hash from DB data** and asking the smart contract. Click **Re-verify** to show it is live, not cached. |
| 6 | **Trigger a normal AI event** | POST a current-timestamp scan (see [§4 demo commands](#4-demo-commands)). | Response: `decision = NORMAL` → the journey step is appended **and** recorded on-chain in the same request. |
| 7 | **Trigger a suspicious event** | POST the same call with `eventTime` set **before the batch creation time**. | Response: `decision = SUSPICIOUS` with the fired rule signals (e.g. *"Event timestamp is before batch creation time"*). |
| 8 | **Show PENDING_REVIEW** | Reload the product page. | The step is stored with `status = PENDING_REVIEW`; **no txHash** was written; the UI shows amber **"⚠ Event Held for AI Review — Blockchain commitment pending approval"** and the blockchain panel reports `pending_review` — **never "tampered"**. |
| 9 | **Approve event** | `GET /api/anomalies?status=PENDING` → copy the flag `id` → `POST /api/anomalies/{id}/approve`. | Approval locates the **existing** PENDING_REVIEW step and updates it in place. The journey step count does **not** change (no duplicate step). |
| 10 | **Show blockchain transaction** | Look at the product page (or the approve response). | The step now shows `status = COMPLETED`, `anomalyDecision = APPROVED`, and a **real txHash** from the local chain. |
| 11 | **Re-verify** | Press **Re-verify**. | Now `N/N steps verified` in green — the hash recorded at approval is byte-for-byte the hash the verifier reconstructs. |
| 12 | **Explain recovery / tamper detection** | Restart Hardhat (`npx hardhat node`), redeploy, then press **Re-verify**, then **Record Events on Blockchain**. | Every stored txHash is now stale. The UI shows **"Pending On-Chain Recording"** (yellow) — **not** tampering — because a missing receipt means *chain was reset*, not *data was forged*. One click re-records and updates the existing steps; counts return to `N/N`. Then explain that red **"✗ Tampered"** appears only when a transaction **really did execute on the current chain** yet the expected hash is absent — a genuine cryptographic mismatch (e.g. someone edits journey JSON behind the app's back). |

**Demo safety:** none of the steps above delete or reset data. Step 12 restarts only the *local
development blockchain*, which is ephemeral by design; PostgreSQL data is untouched and recovery is
demonstrated precisely because the DB keeps its now-stale hashes.

---

## 2. Implemented Architecture (end-to-end)

```
                       ┌──────────────────────────────────────────────┐
  QR label ──scan──►   │  Edge node (Raspberry Pi, offline-capable)   │
                       │  scanner.py → SQLite events.db (synced = 0)  │
                       └───────────────────────┬──────────────────────┘
                                               │ sync.py (replay unsynced rows)
                                               ▼
                       ┌──────────────────────────────────────────────┐
  Web app ──PUT────►   │  POST /api/edge/sync | /api/edge/sync/batch  │
                       │  PUT  /api/products/{id}                     │
                       └───────────────────────┬──────────────────────┘
                                               ▼
                              ┌─────────────────────────────────┐
                              │ 1. Validation                   │
                              │    required fields, product     │
                              │    lookup, journey parse        │
                              └────────────────┬────────────────┘
                                               ▼
                    ┌──────────────────────────────────────────────────┐
                    │ 2. AI + rule anomaly detection (Flask, :5001)    │
                    │    POST /detect-anomaly                          │
                    │    IsolationForest (30%) + deterministic rules   │
                    │    (70%) → combined score in [0,1]               │
                    └───────────────────────┬──────────────────────────┘
                                            ▼
              ┌───────────────────────┬───────────────────────┬───────────────────────┐
              │ NORMAL (< 0.50)       │ SUSPICIOUS (≥ 0.50)   │ REJECT (≥ 0.90)       │
              │ proceed immediately   │ hold for human review │ blocked outright      │
              └───────────┬───────────┴───────────┬───────────┴───────────┬───────────┘
                          ▼                       ▼                       ▼
              ┌───────────────────────┐  ┌───────────────────────┐  ┌───────────────────────┐
              │ 3a. Canonical hashing │  │ 3b. Journey step      │  │ 3c. AnomalyFlag row   │
              │ keccak256(            │  │ status=PENDING_REVIEW │  │ reviewStatus=REJECTED │
              │  batchId + "|" +      │  │ requiresReview=true   │  │ no journey step       │
              │  type|ts|location|desc│  │ AnomalyFlag PENDING   │  │ no blockchain write   │
              │ )                     │  │ NO blockchain write   │  └───────────────────────┘
              │ 4. recordEvent() on   │  └──────────┬────────────┘
              │    SupplyChain.sol    │             │
              │ 5. receipt + hash     │             ▼
              │    re-confirmed       │  ┌───────────────────────────────┐
              └───────────┬───────────┘  │ 4b. Human review              │
                          │              │ POST /api/anomalies/{id}/     │
                          │              │   approve → update SAME step  │
                          │              │   → canonical hash → on-chain │
                          │              │   reject  → mark REJECTED,    │
                          │              │   never committed             │
                          │              └───────────┬───────────────────┘
                          └──────────────────────────┴──────────┐
                                                                 ▼
                              ┌─────────────────────────────────────────────┐
                              │ 5. Verification                             │
                              │ GET /api/blockchain/verify/{batchId}        │
                              │ per step: verified | pending_review |       │
                              │ rejected | unrecorded | tampered            │
                              └─────────────────────────────────────────────┘
```

### Canonical event hashing (single source of truth)

```
eventData = type | timestamp | location | description      (null → "", never the string "null")
eventHash = keccak256( batchId + "|" + eventData )
```

Implemented once in `BlockchainService.buildEventData` / `buildEventDataFromStep` /
`stepString`, and reused by **recording, verification, anomaly approval and recovery**, so the same
journey step always produces the same hash. (Confirmed by the end-to-end tests: an event recorded at
approval is verified later without re-recording.)

### On-chain ledger (`SupplyChain.sol`)

| Function | Purpose |
|---|---|
| `recordEvent(string batchId, bytes32 eventHash)` | Appends an event hash for a batch; reverts on `bytes32(0)` or a duplicate hash. |
| `verifyEvent(string batchId, bytes32 eventHash) → bool` | O(1) existence check — the authority used for verification. |
| `getEventCount(string batchId) → uint256` | Number of events recorded for a batch. |
| `getEvents(string batchId) → ChainEvent[]` | Full event list (hash, block timestamp, recorder). |

The backend **never** treats a txHash stored in PostgreSQL as proof of existence: the live contract
decides. Writes are receipt-validated and idempotent, so a reverted or unmined transaction can
never be persisted as a success.

---

## 3. AI Anomaly Detection — as implemented

**Service:** `backend/ml_service.py`, Flask, port **5001**, endpoint `POST /detect-anomaly`.

### Isolation Forest (30% weight)

Model artifact: `backend/ml_models/isolation_forest.pkl` — a scikit-learn `Pipeline`
(`StandardScaler` → `IsolationForest`), trained offline on 5 000 synthetic supply-chain samples by
`backend/train_anomaly_model.py`.

Exact 8-feature vector, in this order (`_extract_anomaly_features`):

| # | Feature | Meaning |
|---|---|---|
| 1 | `hour_of_day` | event hour (fractional) |
| 2 | `time_since_last_h` | hours since previous scan (default 24 h) |
| 3 | `existing_event_count` | journey steps already present |
| 4 | `node_role_enc` | FARMER=0, TRANSPORTER=1, SELLER=2 |
| 5 | `product_status_enc` | CREATED=0, BOOKED_TRANSPORT=1, IN_TRANSIT=2, AT_SELLER=3, SOLD=4, ASSIGNED_TO_SELLER=5, PARTIALLY_SOLD=6, REJECTED=7 |
| 6 | `events_in_last_10min` | burst count |
| 7 | `time_since_creation_h` | hours since batch creation |
| 8 | `role_status_mismatch` | 1 if (role, status) is not a valid supply-chain pair |

Score normalisation: `ml_score = clip(-raw_decision_function + 0.5, 0.0, 1.0)`.

### Deterministic rules (70% weight)

| ID | Rule | Weight |
|---|---|---|
| R1 | Event timestamp in the future (> 60 s ahead of server clock) | 0.85 |
| R2 | Event timestamp **before batch creation** | 0.90 |
| R3 | Sequence violation (role not allowed for current product status) | 0.70 |
| R4 | Burst: ≥ 5 events in a 10-minute window | 0.65 |
| R5 | Duplicate: same node + event type within 5 minutes | 0.80 |
| R6 | Off-hours scan (23:00–04:00 UTC) | 0.20 |
| R7 | Impossible travel (> 120 km/h between consecutive geo-tagged events) | 0.75 |

### Decision

```
rule_score = min(1.0, sum(rule weights))
combined   = min(1.0, 0.70 * rule_score + 0.30 * ml_score)

combined >= 0.90  → REJECT      (blocked, never committed)
combined >= 0.50  → SUSPICIOUS  (held for human review)
otherwise         → NORMAL      (recorded on-chain)
```

**Fail-safe:** if the Flask service is unreachable (3 s connect / 5 s read timeout) the backend
returns `NORMAL`. Supply-chain operations are therefore never blocked by ML-service downtime.
Note this also means the anomaly gate is effectively inactive while the service is down.

---

## 4. Demo commands

Replace `BATCH_ID` with the product UUID shown on the product page.

**Blockchain health**
```bash
curl http://localhost:8080/api/blockchain/status
# → available, nodeReachable, contractDeployed, contractAddress, message
```

**Step 6 — normal event** (current timestamp, role valid for `CREATED`)
```bash
curl -X POST http://localhost:8080/api/edge/sync -H "Content-Type: application/json" -d '{
  "batchId":"BATCH_ID","nodeId":"DEMO_NODE","nodeRole":"TRANSPORTER",
  "eventType":"SCAN","eventTime":"<NOW ISO-8601 UTC>",
  "location":"Demo Dock","operatorId":"demo-op","operatorName":"Demo Operator"}'
```

**Step 7 — suspicious event** (same call, but `eventTime` set *before* the batch creation time)
```bash
curl -X POST http://localhost:8080/api/edge/sync -H "Content-Type: application/json" -d '{
  "batchId":"BATCH_ID","nodeId":"DEMO_NODE","nodeRole":"TRANSPORTER",
  "eventType":"SCAN","eventTime":"<6 HOURS AGO ISO-8601 UTC>",
  "location":"Demo Dock","operatorId":"demo-op","operatorName":"Demo Operator"}'
```

**Steps 9–10 — review queue and approval**
```bash
curl "http://localhost:8080/api/anomalies?status=PENDING"
curl "http://localhost:8080/api/anomalies/batch/BATCH_ID"
curl -X POST http://localhost:8080/api/anomalies/<FLAG_ID>/approve -H "Content-Type: application/json" -d '{"reviewedBy":"demo"}'
# reject instead:
curl -X POST http://localhost:8080/api/anomalies/<FLAG_ID>/reject  -H "Content-Type: application/json" -d '{"reviewedBy":"demo","reason":"invalid event"}'
```

**Steps 11–12 — verify and recover**
```bash
curl http://localhost:8080/api/blockchain/verify/BATCH_ID
curl -X POST http://localhost:8080/api/blockchain/record/BATCH_ID
```

**Local blockchain lifecycle**
```bash
cd blockchain
npx hardhat node                                        # terminal 1 (ephemeral chain)
npx hardhat run scripts/deploy.js --network localhost   # must re-run after every restart
```

---

## 5. Poster Claims vs. Actual Implementation

| Poster claim | Status | Evidence / notes |
|---|---|---|
| Farmer dashboard | ✅ Implemented | `FarmerDashboard.tsx` — batches, transport booking, seller requests, revenue, crop & yield ML tools, profile. |
| Transporter dashboard | ✅ Implemented | `TransporterDashboard.tsx` — incoming requests, accept/reject, cancellation flow, trip completion, revenue. |
| Seller dashboard | ✅ Implemented | `SellerDashboard.tsx` — inventory, incoming stock, sell (full/partial), sales history. |
| Product / batch creation | ✅ Implemented | `BatchCreator.tsx` → `POST /api/products`; server generates the UUID and the initial journey step. |
| Product journey timeline | ✅ Implemented | `ProductPage.tsx` renders `ProductEntity.journey` as a timeline with per-step metadata. |
| QR generation | ✅ Implemented | `QRCodeDisplay.tsx` (`qrcode.react`) + dynamic URL built in `FarmerDashboard`. |
| Public QR product page | ✅ Implemented | `/product/:productId` is a public route; no authentication required. |
| Blockchain recording | ✅ Implemented | `recordEvent` / `recordEventDetailed` — receipt-validated, idempotent. |
| Blockchain verification | ✅ Implemented | `GET /api/blockchain/verify/{batchId}` — per-step state from the live contract. |
| AI anomaly detection | ✅ Implemented | `ml_service.py` — IsolationForest + 7 deterministic rules, 70:30 blend. |
| Human anomaly approval / rejection | ⚠️ **Backend only — no UI** | `POST /api/anomalies/{id}/approve` and `/reject` work and are used in tests, but **no frontend component calls `/api/anomalies`**. `AnomalyBadge` only *displays* the held state. Reviewing currently requires a direct API call. |
| Edge scan / sync | ✅ Implemented | `edge-node/scanner.py` (OpenCV + pyzbar) → `sync.py` → `POST /api/edge/sync`. |
| Offline-first edge event storage | ✅ Implemented | Local SQLite `events.db` with `synced = 0`; `sync.py` replays unsynced rows and marks them synced **only** on a 2xx response, so failed events are retried on the next run. Replay is triggered by running `sync.py` (manually or via cron) — there is no background daemon. |
| Product status transitions | ⚠️ Partial | Statuses exist and are displayed correctly, but transitions are **not validated server-side** (see [§6](#6-known-gaps-not-yet-fixed)). `BOOKED_TRANSPORT` is referenced in UI/ML but never assigned by any code path. |
| Price transparency | ✅ Implemented | `farmerPrice` / `sellerPrice` / `transporterCharge` surfaced on the product page and in farmer/seller revenue views. |
| Audit trail | ✅ Implemented | Immutable on-chain event hashes + `AnomalyFlagEntity` review history + `NotificationEntity` alerts + per-step `txHash` in the journey. |

---

## 6. Known Gaps (not yet fixed)

These are documented honestly so the demo does not over-claim. None of them block the demo flow
above.

1. **No frontend for anomaly review.** The approve/reject API exists but has no UI, so a human
   reviewer must call the endpoint directly. *(Recommended: add a small review panel to the farmer
   dashboard listing `GET /api/anomalies?status=PENDING` with Approve / Reject buttons.)*
2. **The backend has no authentication or authorization.** There is no Spring Security dependency;
   every endpoint is open. Identity is enforced only in the browser (Clerk + `ProtectedRoute` +
   `DashboardRouter`). A direct HTTP client can, for example, call `PUT /api/roles/{role}/{userId}`
   to change a role, `PUT /api/products/{id}` to alter any journey, or `DELETE /api/products/all`.
3. **No server-side validation of product status transitions.** `PUT /api/products/{id}` accepts any
   status string, so invalid transitions are possible from a non-UI client.
4. **`POST /api/bookings/{id}/transported` appends a journey step and records it on-chain without
   passing through the anomaly gate**, unlike the edge-sync and product-update paths.
5. **Dead statuses.** `BOOKED_TRANSPORT` (product), `REJECTED` (product) and `TRANSPORTED` (product)
   are declared in `ProductStatus`, referenced by UI display maps, and encoded in the ML feature map,
   but no code path assigns them to a product. `TRANSPORTED` *is* assigned to bookings, and
   `REJECTED` to bookings/requests.
6. **Verification cannot detect an edit to a journey step that has no txHash** (e.g. a step added
   while the blockchain was down). Such steps are reported as `unrecorded` — by design — so an
   attacker who also removes the txHash is indistinguishable from a legitimate pending step.

---

## 7. Verification states shown in the UI

| UI state | Meaning | Never shown for |
|---|---|---|
| 🟢 **All journey events verified on blockchain** | Every step's recomputed hash exists on the current chain. | — |
| 🟡 **Pending On-Chain Recording** | Legitimate steps with no valid record on the *current* chain (e.g. after a Hardhat restart). Offers **Record Events on Blockchain**. | — |
| 🟠 **⚠ Event Held for AI Review** | `status = PENDING_REVIEW` / `requiresReview = true`, awaiting human decision. **Not** tampering. | — |
| 🔴 **✗ Tampered** | A step carries a txHash whose transaction **did execute on this chain**, yet the expected event hash is not registered — a genuine cryptographic mismatch. | unrecorded, pending-review or rejected steps |
