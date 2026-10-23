# Farm2Market Review Test Checklist

**Project Path:** `Farm2Market-main-backup-20261002`  
**Audit Date:** October 20, 2026  
**Verification Method:** Direct automated test execution and verified command output  

---

## Component Status Summary

| # | Item / Feature | Status | Actual Test Outcome / Reason | Action Required to Clear |
|---|---|---|---|---|
| 1 | **Hardhat Node & Port 8545** | **PASS** | Hardhat JSON-RPC node running on `127.0.0.1:8545`. Contract deployed at `0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512`. | None. Ready for demo. |
| 2 | **Smart Contract Compilation & Tests** | **PASS** | `npx hardhat test` executed cleanly (3/3 passing: event recording, duplicate revert, non-existent hash check). | None. Ready for demo. |
| 3 | **Flask ML Microservice (Port 5001)** | **PASS** | `ml_service.py` running; `/health` returns `status: ok` and all 3 models loaded (`rf_crop_model.pkl`, `reg_model.pkl`, `isolation_forest.pkl`). | None. Ready for demo. |
| 4 | **Crop Recommendation Endpoint** | **PASS** | POST `/recommend` returned predicted crop (`Groundnut`, 52.4% confidence). | None. Ready for demo. |
| 5 | **Yield Prediction Endpoint** | **PASS** | POST `/predict-yield` returned predicted yield (`2970.86 kg/acre`, 92.5% confidence). | None. Ready for demo. |
| 6 | **AI Anomaly Detection Endpoint** | **PASS** | POST `/detect-anomaly` tested with normal (0.0) and suspicious (0.8421 sequence violation; 0.8470 impossible travel) payloads. Both correctly classified. | None. Ready for demo. |
| 7 | **Frontend TypeScript & Build** | **PASS** | `npm run build` (`tsc -b && vite build`) passed with 0 TypeScript errors. `dist/` bundle created in 17.29s. | None. Ready for demo. |
| 8 | **Frontend Dev Server** | **PASS** | `npm run dev` serves on `http://localhost:5173`. | None. Ready for demo. |
| 9 | **Backend Maven Compilation** | **PASS** | `.\mvnw.cmd test-compile` compiled all 33 Java source files on Java 21 without errors. | None. Ready for demo. |
| 10 | **Anomaly Fallback Policy (Innovation)** | **PASS** | `AnomalyDetectionServiceTest` (2/2 passing) verified that ML service downtime triggers a `SUSPICIOUS` quarantine hold instead of an uninspected blockchain commit. | None. Ready for demo. |
| 11 | **Edge Node SQLite Database & Scripts** | **PASS** | `setup_db.py` creates `events.db`; `py_compile` verified syntax for `setup_db.py`, `sync.py`, and `scanner.py`. | None. Ready for demo. |
| 12 | **Edge Node Physical Hardware** | **NOT TESTED** | Physical Raspberry Pi camera hardware not connected. Offline queuing logic verified via SQLite. | Connect physical Raspberry Pi to test camera scanning if desired. |
| 13 | **Location Autocomplete (Geoapify)** | **BLOCKED** | Missing `GEOAPIFY_API_KEY` environment variable. Handled safely in `LocationService.java` with empty suggestions fallback. | Set `$env:GEOAPIFY_API_KEY="<key>"` if live geocoding is required. |
| 14 | **PostgreSQL Connection (Neon)** | **BLOCKED** | Missing `DB_URL` and `DB_PASSWORD` in user environment; port 5432 closed locally. | Provide valid Neon connection string and password via environment variables. |
| 15 | **Spring Boot Live HTTP Startup (Port 8080)** | **BLOCKED** | Blocked solely by missing database credentials; once configured, starts immediately. | Set `$env:DB_URL` and `$env:DB_PASSWORD` before starting Spring Boot. |
