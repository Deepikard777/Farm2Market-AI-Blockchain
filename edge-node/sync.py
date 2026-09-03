#!/usr/bin/env python3
"""
FarmToMarket Edge Node — Sync Script (Role-Aware)
===================================================
Reads unsynced scan events from SQLite and pushes them to the Spring Boot
backend with full role information (FARMER / TRANSPORTER / SELLER).

The backend uses the role to update the product's supply chain status:
  TRANSPORTER → sets status to IN_TRANSIT, links transporter
  SELLER      → sets status to AT_SELLER, links seller
  FARMER      → records dispatch event

Usage:
    python3 sync.py                                         # uses node_config.json
    python3 sync.py --backend http://192.168.1.100:8080     # override backend
    python3 sync.py --dry-run                               # preview only
"""

import argparse
import datetime
import json
import os
import sqlite3
import sys

import requests

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(SCRIPT_DIR, "events.db")
CONFIG_PATH = os.path.join(SCRIPT_DIR, "node_config.json")
SYNC_ENDPOINT = "/api/edge/sync"


def load_config():
    """Load backend URL from node_config.json."""
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r") as f:
            config = json.load(f)
        return config
    return {}


def parse_args():
    parser = argparse.ArgumentParser(description="FarmToMarket Sync Script")
    parser.add_argument(
        "--backend", default=None,
        help="Override backend URL from config"
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Preview without sending"
    )
    return parser.parse_args()


def get_unsynced_events(db_path):
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()
    cursor.execute(
        "SELECT id, batch_id, node_id, event_type, event_time, "
        "node_role, operator_id, operator_name, location "
        "FROM scan_events WHERE synced = 0 ORDER BY id ASC"
    )
    rows = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return rows


def mark_synced(db_path, event_id):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute("UPDATE scan_events SET synced = 1 WHERE id = ?", (event_id,))
    conn.commit()
    conn.close()


def sync_event(backend_url, event):
    """POST a scan event with full role info to the backend."""
    url = f"{backend_url}{SYNC_ENDPOINT}"
    payload = {
        "batchId":      event["batch_id"],
        "nodeId":       event["node_id"],
        "eventType":    event["event_type"],
        "eventTime":    event["event_time"],
        "nodeRole":     event.get("node_role", ""),
        "operatorId":   event.get("operator_id", ""),
        "operatorName": event.get("operator_name", ""),
        "location":     event.get("location", ""),
    }
    try:
        resp = requests.post(url, json=payload, timeout=10)
        if resp.status_code in (200, 201):
            return True
        else:
            print(f"   ⚠ Server returned {resp.status_code}: {resp.text[:200]}")
            return False
    except requests.ConnectionError:
        print("   ⚠ Cannot reach backend (no internet or server down).")
        return False
    except requests.Timeout:
        print("   ⚠ Request timed out.")
        return False
    except Exception as e:
        print(f"   ⚠ Unexpected error: {e}")
        return False


def main():
    args = parse_args()
    config = load_config()

    backend = args.backend or config.get("backend_url", "http://localhost:8080")
    dry_run = args.dry_run

    node_role = config.get("node_role", "UNKNOWN")
    operator_name = config.get("operator_name", "Unknown")

    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"\n[{now}] FarmToMarket Sync")
    print(f"   Role    : {node_role} ({operator_name})")
    print(f"   Backend : {backend}")
    print(f"   Database: {DB_PATH}")

    if not os.path.exists(DB_PATH):
        print("   ⚠ Database not found. Run setup_db.py first.")
        sys.exit(1)

    events = get_unsynced_events(DB_PATH)
    if not events:
        print("   ✅ Nothing to sync — all events are up to date.")
        return

    print(f"   📤 {len(events)} event(s) to sync.\n")

    success_count = 0
    fail_count = 0

    for evt in events:
        role = evt.get('node_role', '?')
        label = (f"  [{evt['id']}] batch={evt['batch_id']} "
                 f"role={role} operator={evt.get('operator_name', '?')} "
                 f"time={evt['event_time']}")

        if dry_run:
            print(f"   (dry-run) Would sync: {label}")
            continue

        if sync_event(backend, evt):
            mark_synced(DB_PATH, evt["id"])
            print(f"   ✅ Synced: {label}")
            success_count += 1
        else:
            print(f"   ❌ Failed: {label}")
            fail_count += 1
            if fail_count >= 3:
                print("\n   ⚠ Too many failures. Will retry on next run.")
                break

    if not dry_run:
        print(f"\n   Summary: {success_count} synced, {fail_count} failed, "
              f"{len(events) - success_count - fail_count} skipped.")


if __name__ == "__main__":
    main()
