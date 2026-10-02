# 🌾 FarmToMarket

**A Blockchain-Secured, IoT-Enabled Farm-to-Consumer Supply Chain Traceability Platform**

FarmToMarket brings end-to-end transparency to the agricultural supply chain by combining **blockchain** for tamper-proof event recording, **IoT edge nodes** (Raspberry Pi) for physical checkpoint verification, and **QR-based consumer verification** — all in a single, deployable platform.

---

## ✨ Key Features

- **Dual Verification** — Every supply chain event is recorded in both PostgreSQL (fast queries) and Ethereum blockchain (SHA-256 tamper-proof hashes)
- **Role-Based Dashboards** — Dedicated interfaces for Farmers, Transporters, and Sellers
- **IoT Edge Scanning** — Raspberry Pi nodes with QR scanning via phone camera (IP Webcam + OpenCV + pyzbar)
- **Offline-First Architecture** — Edge nodes store events locally in SQLite and batch-sync when connectivity returns
- **Zero-Friction Consumer Verification** — Scan a QR code → view the full supply chain journey in the browser (no app, no account needed)
- **Blockchain Backfill** — Retroactive on-chain recording for events created during node outages
- **Cross-Device Networking** — All components communicate over LAN with dynamic API URL resolution

---

## 🏗️ Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│    Frontend      │     │    Backend       │     │   Blockchain    │
│  React 18 + TS   │◄───►│  Spring Boot 3   │◄───►│  Solidity +     │
│  Vite + Tailwind │     │  Java 17 + JPA   │     │  Hardhat Node   │
│  shadcn/ui       │     │  PostgreSQL      │     │  (Ethereum)     │
│  Clerk Auth      │     │  (Neon)          │     │                 │
└─────────────────┘     └────────┬─────────┘     └─────────────────┘
                                 │
                        ┌────────▼─────────┐
                        │   IoT Edge Node   │
                        │  Raspberry Pi 4   │
                        │  Python + OpenCV  │
                        │  SQLite + Sync    │
                        └──────────────────┘
```

---

## 📁 Project Structure

```
FarmToMarket/
├── backend/          # Spring Boot 3 API server (Java 17)
├── frontend/         # React 18 + TypeScript + Vite SPA
├── blockchain/       # Solidity smart contracts + Hardhat config
└── edge-node/        # Raspberry Pi QR scanner + sync scripts (Python)
```

---

## 🚀 Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 17+ | Backend runtime |
| Node.js | 18+ | Frontend & blockchain tooling |
| npm / Yarn | Latest | Package management |
| Python | 3.9+ | Edge node scripts |
| PostgreSQL | — | Or access to Neon serverless DB |

---

## 🔧 Getting Started

### 1. Blockchain (Hardhat Node)

```bash
cd blockchain
npm install
npx hardhat node                    # Starts local Ethereum node on :8545
# In a new terminal:
npx hardhat run scripts/deploy.js --network localhost
```

The deployed contract address is saved to `blockchain/deployments/localhost.json`.

### 2. Backend (Spring Boot)

```bash
cd backend
./mvnw spring-boot:run
```

Runs on `http://localhost:8080`. Database connection is configured in `backend/src/main/resources/application.properties`.

### 3. Frontend (React + Vite)

```bash
cd frontend
npm install        # or: yarn install
npm run dev        # or: yarn dev
```

Runs on `http://localhost:5173`. Use `--host` flag for LAN access.

### 4. Edge Node (Raspberry Pi — optional)

On the Raspberry Pi:

```bash
cd edge-node
pip install -r requirements.txt
python setup_db.py              # Initialize SQLite database
python scanner.py               # Start QR scanning
python sync.py                  # Sync buffered events to backend
```

Configure `edge-node/node_config.json` with your backend IP, node role, and camera stream URL.

---

## 🔄 Running All Services (Development)

Open four terminals:

| Terminal | Directory | Command |
|----------|-----------|---------|
| 1 | `blockchain/` | `npx hardhat node` |
| 2 | `blockchain/` | `npx hardhat run scripts/deploy.js --network localhost` |
| 3 | `backend/` | `./mvnw spring-boot:run` |
| 4 | `frontend/` | `npm run dev -- --host` |

---

## 🛠️ Technology Stack

### Backend
- Spring Boot 3 · Java 17
- Spring Data JPA · Hibernate
- PostgreSQL (Neon serverless)
- Web3j (Ethereum integration)
- Clerk OAuth (authentication)

### Frontend
- React 18 · TypeScript
- Vite (build tool)
- Tailwind CSS · shadcn/ui · Radix UI
- Clerk (Google & GitHub OAuth)

### Blockchain
- Solidity 0.8.x (smart contracts)
- Hardhat (development & deployment)
- SHA-256 event hash verification

### IoT Edge
- Raspberry Pi 4 (BCM2711, 4GB RAM)
- Python 3 · OpenCV · pyzbar
- SQLite (offline buffer)
- IP Webcam (phone camera stream)

---

## 📊 Performance

| Metric | Value |
|--------|-------|
| Blockchain event recording | 200–500 ms/event |
| Product page API load | ~300 ms |
| QR scan detection | < 1 second |
| Edge sync latency (Pi → Backend) | < 500 ms/event |
| Blockchain verification (4 events) | ~800 ms |
| SQLite insert on Pi | < 5 ms |

---

## 🔍 Troubleshooting

| Issue | Solution |
|-------|----------|
| Backend won't start | Check Java 17+: `java -version`. Verify DB creds in `application.properties` |
| Frontend won't start | Check Node 18+: `node -v`. Delete `node_modules` and reinstall |
| Blockchain verification fails | Ensure Hardhat node is running. Use backfill endpoint if contract was redeployed |
| QR codes not scannable from phone | Use LAN IP (not localhost). Run Vite with `--host` flag |
| Pi scanner can't connect | Verify backend IP in `node_config.json`. Check WiFi connectivity |

---

## 📄 License

This project is developed as part of the Unified IT Project at SSN College of Engineering, Chennai.
