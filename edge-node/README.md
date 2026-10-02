# FarmToMarket — Offline QR Scanning Edge Node

> A Raspberry Pi–based QR scanner that works **fully offline** and syncs to the backend when internet returns.

## Hardware Required

| Component | Notes |
|---|---|
| Raspberry Pi 4 (recommended) | Any Pi with USB support works |
| USB Webcam **or** Pi Camera | Must be compatible with OpenCV |
| Power supply | 5V 3A for Pi 4 |
| (Optional) Monitor + keyboard | Only needed during initial setup |

## Quick Start

### 1. Install System Dependencies

```bash
# On Raspberry Pi OS / Debian
sudo apt update
sudo apt install -y python3-pip python3-opencv libzbar0
```

### 2. Install Python Packages

```bash
cd edge-node
pip3 install -r requirements.txt
```

### 3. Initialise the Database

```bash
python3 setup_db.py
```

This creates `events.db` with the `scan_events` table.

### 4. Run the Scanner

```bash
# Default settings (node = EDGE_NODE_1, cooldown = 60s, camera = 0)
python3 scanner.py

# Custom node ID and cooldown
python3 scanner.py --node TRANSPORT_NODE_1 --cooldown 120

# Use a different camera
python3 scanner.py --camera 1
```

- Hold a QR code (containing a batch ID) in front of the camera.
- **Green banner** → scan recorded successfully.
- **Red banner** → duplicate scan suppressed.
- Press **ESC** to exit.

### 5. Sync to Backend

```bash
# Manual sync
python3 sync.py --backend http://192.168.1.100:8080

# Dry run (preview without sending)
python3 sync.py --dry-run

# Automate with cron (every minute)
crontab -e
# Add this line:
# * * * * * cd /home/pi/FarmToMarket/edge-node && python3 sync.py --backend http://YOUR_SERVER:8080 >> sync.log 2>&1
```

## How It Works

```
QR Code on Harvest Bag
        │
        ▼
  USB Camera → Raspberry Pi
        │
  Python QR Scanner (scanner.py)
        │
  Extract batch_id
        │
  Duplicate? ──YES──→ Ignore (red feedback)
        │ NO
        ▼
  Store in SQLite (events.db)
        │
  Internet available?
  ├─ NO  → stays in local queue
  └─ YES → sync.py pushes to backend
```

## Duplicate Prevention

Each batch is only recorded **once per node**:

1. On startup, all previously scanned batch IDs are loaded from SQLite.
2. A configurable cooldown (default 60s) prevents accidental re-scans.
3. Even after restart, the scanner remembers which batches have been seen.

## File Overview

| File | Purpose |
|---|---|
| `scanner.py` | Main QR scanner with camera feed + duplicate prevention |
| `sync.py` | Pushes unsynced events to the Spring Boot backend |
| `setup_db.py` | Creates/initialises the SQLite database |
| `requirements.txt` | Python dependencies |
| `events.db` | SQLite database (created by `setup_db.py`) |

## Database Schema

```sql
scan_events
├── id          INTEGER PRIMARY KEY AUTOINCREMENT
├── batch_id    TEXT NOT NULL        -- product UUID from QR code
├── node_id     TEXT NOT NULL        -- e.g. TRANSPORT_NODE_1
├── event_type  TEXT DEFAULT 'SCAN'  -- extensible for future event types
├── event_time  TEXT NOT NULL        -- ISO 8601 timestamp
└── synced      INTEGER DEFAULT 0   -- 0 = pending, 1 = synced
```
