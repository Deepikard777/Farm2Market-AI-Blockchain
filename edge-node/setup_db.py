#!/usr/bin/env python3
"""
FarmToMarket Edge Node — Database Setup
Creates/upgrades the local SQLite database for offline QR scan storage.
Run once on a fresh Pi, or re-run safely to add new columns.
"""

import sqlite3
import os

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "events.db")


def setup_database():
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    # Main scan events table with role info
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS scan_events (
            id              INTEGER PRIMARY KEY AUTOINCREMENT,
            batch_id        TEXT    NOT NULL,
            node_id         TEXT    NOT NULL,
            event_type      TEXT    NOT NULL DEFAULT 'SCAN',
            event_time      TEXT    NOT NULL,
            synced          INTEGER DEFAULT 0,
            node_role       TEXT,
            operator_id     TEXT,
            operator_name   TEXT,
            location        TEXT
        )
    """)

    # Add new columns if upgrading from old schema
    existing_columns = [row[1] for row in cursor.execute("PRAGMA table_info(scan_events)")]
    new_columns = {
        "node_role": "TEXT",
        "operator_id": "TEXT",
        "operator_name": "TEXT",
        "location": "TEXT",
    }
    for col_name, col_type in new_columns.items():
        if col_name not in existing_columns:
            cursor.execute(f"ALTER TABLE scan_events ADD COLUMN {col_name} {col_type}")
            print(f"   Added column: {col_name}")

    # Indexes
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_synced ON scan_events(synced)")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_batch_node ON scan_events(batch_id, node_id)")

    conn.commit()
    conn.close()

    print(f"✅ Database ready at: {DB_PATH}")
    print("   Table 'scan_events' with role columns is set up.")


if __name__ == "__main__":
    setup_database()
