# Farm2Market Live Demo Run Guide

Follow this guide step-by-step for the live project review demonstration.

---

## 1. Startup Order & Terminal Commands

Open 4 separate PowerShell or Command Prompt terminals.

### Terminal 1: Hardhat Blockchain Node (Port 8545)
```powershell
cd C:\Users\DEEPIKA\Downloads\Farm2Market-main\Farm2Market-main-backup-20261002\blockchain
npx hardhat node
```
*Wait until you see the list of 20 test accounts with private keys.*

In a separate prompt once the node starts:
```powershell
npx hardhat run scripts/deploy.js --network localhost
```
*Expected Output: `SupplyChain deployed to: 0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512`*  
*(The deployment script automatically updates `blockchain/deployments/localhost.json`)*

---

### Terminal 2: Flask ML Microservice (Port 5001)
```powershell
cd C:\Users\DEEPIKA\Downloads\Farm2Market-main\Farm2Market-main-backup-20261002\backend
python ml_service.py
```
*Health verification in another window:*
```powershell
curl http://localhost:5001/health
```
*Expected response:*
```json
{"anomaly_model_loaded": true, "crop_model_loaded": true, "status": "ok", "yield_model_loaded": true}
```

---

### Terminal 3: Spring Boot Backend Server (Port 8080)
*Prerequisite: Configure database environment variables before running.*
```powershell
cd C:\Users\DEEPIKA\Downloads\Farm2Market-main\Farm2Market-main-backup-20261002\backend

# Set your Neon database environment variables in PowerShell:
$env:DB_URL="jdbc:postgresql://<your-neon-host>:5432/farm2market?sslmode=require"
$env:DB_USERNAME="neondb_owner"
$env:DB_PASSWORD="<your-database-password>"

# Optional: Geoapify autocomplete API key:
$env:GEOAPIFY_API_KEY="<your-geoapify-key>"

# Launch Spring Boot
.\mvnw.cmd spring-boot:run
```

---

### Terminal 4: React / Vite Frontend (Port 5173)
```powershell
cd C:\Users\DEEPIKA\Downloads\Farm2Market-main\Farm2Market-main-backup-20261002\frontend
npm run dev
```
*Open in browser:* `http://localhost:5173`

---

## 2. Live Demo Script for Reviewers

### Demo Step 1: Smart Contract On-Chain Verification
Show smart contract test execution to prove EVM execution and duplicate rejection:
```powershell
cd C:\Users\DEEPIKA\Downloads\Farm2Market-main\Farm2Market-main-backup-20261002\blockchain
npx hardhat test
```
*Faculty Talking Point: "Our Solidity contract enforces keccak256 hash storage and provides O(1) event verification and duplicate rejection."*

### Demo Step 2: AI Crop Recommendation & Yield Prediction
Send sample inputs to the ML microservice:
```powershell
# Crop Recommendation
curl -X POST http://localhost:5001/recommend -H "Content-Type: application/json" -d "{\"N\": 90, \"P\": 42, \"K\": 43, \"temperature\": 20.8, \"humidity\": 82.0, \"ph\": 6.5, \"rainfall\": 202.9}"

# Yield Prediction
curl -X POST http://localhost:5001/predict-yield -H "Content-Type: application/json" -d "{\"Crop\": \"Rice\", \"Season\": \"Kharif\", \"State\": \"Tamil Nadu\", \"Area\": 100.0, \"Annual_Rainfall\": 1100.0, \"Fertilizer\": 5000.0, \"Pesticide\": 50.0}"
```

### Demo Step 3: Anomaly Detection Live Test
Demonstrate detection of impossible travel or sequence violations:
```powershell
curl -X POST http://localhost:5001/detect-anomaly -H "Content-Type: application/json" -d "{\"batchId\": \"BATCH-001\", \"nodeId\": \"NODE-02\", \"nodeRole\": \"SELLER\", \"eventType\": \"SCAN\", \"eventTime\": \"2026-10-21T10:00:00Z\", \"productStatus\": \"CREATED\", \"productCreatedAt\": \"2026-10-20T10:00:00Z\", \"existingEventCount\": 0}"
```
*Expected: Returns `decision: SUSPICIOUS`, anomaly score ~0.84, with sequence violation signals.*

### Demo Step 4: Innovation Verification (Resilient Fallback Gatekeeper)
Run the automated test demonstrating that service failure triggers a quarantine hold instead of admitting unvetted events to the blockchain:
```powershell
cd C:\Users\DEEPIKA\Downloads\Farm2Market-main\Farm2Market-main-backup-20261002\backend
.\mvnw.cmd test -Dtest=AnomalyDetectionServiceTest
```

### Demo Step 5: Edge Node SQLite Buffer
Show the offline SQLite buffer setup on the edge node:
```powershell
cd C:\Users\DEEPIKA\Downloads\Farm2Market-main\Farm2Market-main-backup-20261002\edge-node
python setup_db.py
```

---

## 3. Safe Shutdown Commands

To stop services cleanly:
- Press `Ctrl + C` in each terminal.
- To verify ports are freed:
  ```powershell
  Get-NetTCPConnection -LocalPort 8545,5001,8080,5173 -ErrorAction SilentlyContinue
  ```
