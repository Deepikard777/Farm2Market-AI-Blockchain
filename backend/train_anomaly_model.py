"""
train_anomaly_model.py
======================
Offline training script for the Farm2Market Supply Chain Anomaly Detection model.

IMPORTANT — Data Provenance:
    This script generates a SYNTHETIC dataset derived from realistic supply-chain
    operational patterns observed in the Farm2Market domain. No real production data
    from Farm2Market users was used because:

    1. The platform is newly deployed and has not yet accumulated sufficient real
       event logs to train a statistically meaningful model.
    2. IsolationForest is an unsupervised algorithm — it requires a representative
       "normal" distribution to learn from. A synthetic dataset built from expert
       knowledge of the domain is a standard prototype approach.

    When real event logs are available (e.g., from the scan_events table in
    PostgreSQL), re-run this script with a real dataset and the model will
    improve automatically. See "How to Retrain" section at the bottom.

Model: IsolationForest (sklearn.ensemble.IsolationForest)
    - Unsupervised anomaly detection algorithm.
    - Learns the structure of NORMAL supply-chain events by training on
      a large sample of synthetic normal events (80% of dataset).
    - Anomalous events (20%) are injected to help calibrate contamination.
    - At inference time, the fitted model assigns a decision_function score
      to every incoming event; events far from the learned distribution
      score lower (more anomalous).
    - The raw decision_function value is normalized to [0.0, 1.0].

Features (8 numerical):
    1. hour_of_day            — Local hour extracted from eventTime (0.0–23.99)
    2. time_since_last_h      — Hours since the previous scan event (capped at 720)
    3. existing_event_count   — Number of journey steps already in the batch
    4. node_role_enc          — Encoded node role (FARMER=0, TRANSPORTER=1, SELLER=2)
    5. product_status_enc     — Encoded product status (CREATED=0...REJECTED=7)
    6. events_in_last_10min   — Count of scan events within the last 10 minutes
    7. time_since_creation_h  — Hours elapsed since the batch was created
    8. role_status_mismatch   — 1 if (nodeRole, productStatus) combination violates
                                the expected supply-chain sequence; 0 otherwise

Limitations of the prototype:
    - No real event records are used; patterns are expert-designed.
    - IsolationForest cannot learn from labelled "known fraud" examples.
    - The implied travel speed anomaly is handled by deterministic rules, not
      by this model (because lat/lon coordinates are not always available).
    - Contamination parameter (0.10) is estimated; tune it as real data arrives.

How to Retrain with Real Data:
    1. Export scan_events and products from PostgreSQL:
           SELECT se.*, p.status, p.created_at FROM scan_events se
           JOIN products p ON se.batch_id = p.id;
    2. Compute the 8 features above for each row.
    3. Replace the generate_synthetic_dataset() output with your real feature matrix.
    4. Re-run this script. The model will be saved to ml_models/isolation_forest.pkl.
    5. Restart the Flask ML service (ml_service.py) to pick up the new model.

Usage:
    cd backend/
    python train_anomaly_model.py
"""

import os
import sys
import math
import random
import numpy as np
import joblib
from datetime import datetime, timezone, timedelta
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import Pipeline

# ── Reproducibility ────────────────────────────────────────────────────────────
RANDOM_SEED = 42
np.random.seed(RANDOM_SEED)
random.seed(RANDOM_SEED)

# ── Paths ──────────────────────────────────────────────────────────────────────
SCRIPT_DIR   = os.path.dirname(os.path.abspath(__file__))
MODELS_DIR   = os.path.join(SCRIPT_DIR, 'ml_models')
MODEL_PATH   = os.path.join(MODELS_DIR, 'isolation_forest.pkl')

os.makedirs(MODELS_DIR, exist_ok=True)

# ── Feature names (must match extraction in ml_service.py) ────────────────────
FEATURE_NAMES = [
    'hour_of_day',          # 0.0–23.99
    'time_since_last_h',    # hours since previous event (capped at 720)
    'existing_event_count', # number of existing journey steps
    'node_role_enc',        # FARMER=0, TRANSPORTER=1, SELLER=2
    'product_status_enc',   # CREATED=0, BOOKED_TRANSPORT=1, IN_TRANSIT=2,
                            # AT_SELLER=3, SOLD=4, ASSIGNED_TO_SELLER=5,
                            # PARTIALLY_SOLD=6, REJECTED=7
    'events_in_last_10min', # burst count
    'time_since_creation_h',# hours since batch was first created
    'role_status_mismatch', # 1 if sequence violates expected role progression
]

N_FEATURES = len(FEATURE_NAMES)

# ── Sequence compatibility reference ──────────────────────────────────────────
# (nodeRole_enc, product_status_enc) pairs that are NORMAL supply-chain behaviour
VALID_ROLE_STATUS_PAIRS = {
    (0, 0), (0, 1),                         # FARMER  → CREATED, BOOKED_TRANSPORT
    (1, 1), (1, 2),                         # TRANSPORTER → BOOKED_TRANSPORT, IN_TRANSIT
    (2, 2), (2, 3), (2, 4), (2, 6),         # SELLER  → IN_TRANSIT, AT_SELLER, SOLD, PARTIAL
    (0, 3), (0, 4), (0, 5), (0, 6),         # FARMER visits AT_SELLER, SOLD, ASSIGNED, PARTIAL
    (1, 5),                                 # TRANSPORTER → ASSIGNED_TO_SELLER
}


def role_status_mismatch(role_enc: int, status_enc: int) -> int:
    """Return 1 if the (role, status) pair represents an unexpected sequence step."""
    return 0 if (role_enc, status_enc) in VALID_ROLE_STATUS_PAIRS else 1


# ── Synthetic dataset generation ───────────────────────────────────────────────

def sample_normal_event(rng: np.random.Generator) -> list:
    """
    Generate one NORMAL supply-chain scan event feature vector.

    Normal operational characteristics:
    - Scans occur during business hours (7:00–20:00 local / approx UTC+5:30)
    - Events are spaced realistically: pickup ~8h after creation, delivery ~24-72h later
    - Event counts grow monotonically: 1–10 steps per batch
    - Role/status combinations follow a valid supply-chain sequence
    """
    # Business-hours scan: 7:00–20:00 (IST ~1:30–14:30 UTC, modelled as 7–20 UTC)
    hour = float(rng.uniform(7.0, 20.0))

    # Choose a realistic supply-chain phase
    phase = rng.choice([0, 1, 2, 3], p=[0.25, 0.30, 0.30, 0.15])
    if phase == 0:   # Creation / dispatch
        role_enc, status_enc = 0, 0
        step = rng.integers(1, 3)
        gap  = float(rng.uniform(0.5, 12.0))   # hours since creation / prev scan
        age  = float(rng.uniform(0.0, 6.0))
    elif phase == 1: # In-transit pickup
        role_enc, status_enc = rng.choice([(1, 1), (1, 2)])
        role_enc, status_enc = int(role_enc), int(status_enc)
        step = rng.integers(2, 5)
        gap  = float(rng.uniform(4.0, 48.0))
        age  = float(rng.uniform(6.0, 72.0))
    elif phase == 2: # Delivery to seller
        role_enc, status_enc = rng.choice([(2, 2), (2, 3)])
        role_enc, status_enc = int(role_enc), int(status_enc)
        step = rng.integers(3, 8)
        gap  = float(rng.uniform(2.0, 72.0))
        age  = float(rng.uniform(24.0, 120.0))
    else:            # Sold / partially sold
        role_enc, status_enc = rng.choice([(2, 4), (2, 6)])
        role_enc, status_enc = int(role_enc), int(status_enc)
        step = rng.integers(5, 11)
        gap  = float(rng.uniform(12.0, 96.0))
        age  = float(rng.uniform(48.0, 168.0))

    burst = 0  # Normal events are not bursting
    mismatch = role_status_mismatch(role_enc, status_enc)

    return [hour, min(gap, 720.0), int(step), role_enc, status_enc, burst, min(age, 720.0), mismatch]


def sample_anomalous_event(rng: np.random.Generator) -> list:
    """
    Generate one ANOMALOUS supply-chain scan event feature vector.

    Anomaly patterns modelled:
    A. Night-time / off-hours scan  (unusual timing)
    B. Very short event gap         (burst / duplicate)
    C. Very long event gap          (batch stuck, stale data)
    D. High burst count             (rapid repeated scanning)
    E. Sequence mismatch            (wrong role for current status)
    F. Event after completion       (status=SOLD or REJECTED re-scanned)
    G. Extremely large event count  (data integrity issue)
    """
    pattern = rng.choice(['night', 'short_gap', 'long_gap', 'burst',
                          'mismatch', 'post_completion', 'large_count'])

    if pattern == 'night':
        hour = float(rng.choice([
            rng.uniform(0.0, 4.0),    # 00:00–04:00
            rng.uniform(23.0, 24.0),  # 23:00–24:00
        ]))
        gap  = float(rng.uniform(1.0, 12.0))
        step = rng.integers(1, 6)
        role_enc, status_enc = rng.choice([(0,0),(1,1),(2,2)])
        role_enc, status_enc = int(role_enc), int(status_enc)
        burst = 0
        age   = float(rng.uniform(0.0, 96.0))
        mismatch = 0

    elif pattern == 'short_gap':
        hour  = float(rng.uniform(7.0, 20.0))
        gap   = float(rng.uniform(0.0, 0.08))   # < 5 minutes
        step  = rng.integers(1, 8)
        role_enc, status_enc = rng.choice([(0,0),(1,2),(2,3)])
        role_enc, status_enc = int(role_enc), int(status_enc)
        burst = rng.integers(3, 8)
        age   = float(rng.uniform(1.0, 48.0))
        mismatch = 0

    elif pattern == 'long_gap':
        hour = float(rng.uniform(7.0, 20.0))
        gap  = float(rng.uniform(200.0, 720.0))  # 8–30 days gap
        step = rng.integers(1, 5)
        role_enc, status_enc = rng.choice([(0,0),(1,1),(2,2)])
        role_enc, status_enc = int(role_enc), int(status_enc)
        burst = 0
        age   = float(rng.uniform(200.0, 720.0))
        mismatch = 0

    elif pattern == 'burst':
        hour  = float(rng.uniform(7.0, 20.0))
        gap   = float(rng.uniform(0.0, 0.1))
        step  = rng.integers(1, 5)
        role_enc, status_enc = rng.choice([(0,0),(1,1),(2,2)])
        role_enc, status_enc = int(role_enc), int(status_enc)
        burst = rng.integers(5, 15)
        age   = float(rng.uniform(0.5, 24.0))
        mismatch = 0

    elif pattern == 'mismatch':
        hour  = float(rng.uniform(7.0, 20.0))
        gap   = float(rng.uniform(1.0, 24.0))
        step  = rng.integers(1, 5)
        # Choose a clearly invalid (role, status) combination
        invalid_pairs = [
            (2, 0),  # SELLER scanning a CREATED (never dispatched) batch
            (1, 4),  # TRANSPORTER scanning a SOLD batch
            (0, 2),  # FARMER scanning IN_TRANSIT (they don't have it)
            (2, 1),  # SELLER scanning BOOKED_TRANSPORT (not arrived yet)
        ]
        pair = invalid_pairs[rng.integers(0, len(invalid_pairs))]
        role_enc, status_enc = pair
        burst = 0
        age   = float(rng.uniform(1.0, 48.0))
        mismatch = 1

    elif pattern == 'post_completion':
        hour  = float(rng.uniform(7.0, 20.0))
        gap   = float(rng.uniform(0.5, 48.0))
        step  = rng.integers(8, 15)   # unusually high step count
        # Scanning a batch that's already sold or rejected
        role_enc = int(rng.choice([0, 1, 2]))
        status_enc = int(rng.choice([4, 7]))  # SOLD or REJECTED
        burst = 0
        age   = float(rng.uniform(72.0, 300.0))
        mismatch = role_status_mismatch(role_enc, status_enc)

    else:  # large_count
        hour  = float(rng.uniform(7.0, 20.0))
        gap   = float(rng.uniform(0.1, 2.0))
        step  = rng.integers(15, 30)  # abnormally high event count
        role_enc, status_enc = rng.choice([(0,0),(1,2),(2,3)])
        role_enc, status_enc = int(role_enc), int(status_enc)
        burst = rng.integers(2, 8)
        age   = float(rng.uniform(0.5, 48.0))
        mismatch = 0

    return [hour, min(gap, 720.0), int(step), role_enc, status_enc,
            int(burst), min(age, 720.0), mismatch]


def generate_synthetic_dataset(n_normal: int = 4000, n_anomalous: int = 1000,
                                seed: int = RANDOM_SEED):
    """
    Generate a synthetic supply-chain event dataset.

    Returns X (n_samples, n_features) and y (1=normal, -1=anomalous)
    using the sklearn label convention for IsolationForest.
    """
    rng = np.random.default_rng(seed)

    normal_rows    = [sample_normal_event(rng)    for _ in range(n_normal)]
    anomalous_rows = [sample_anomalous_event(rng) for _ in range(n_anomalous)]

    X_normal    = np.array(normal_rows,    dtype=float)
    X_anomalous = np.array(anomalous_rows, dtype=float)

    X = np.vstack([X_normal, X_anomalous])
    y = np.concatenate([np.ones(n_normal), -np.ones(n_anomalous)])

    # Shuffle
    idx = np.random.permutation(len(X))
    return X[idx], y[idx]


# ── Training ───────────────────────────────────────────────────────────────────

def train_and_save():
    print("=" * 60)
    print("Farm2Market — Anomaly Detection Model Training")
    print("Model:    IsolationForest (sklearn.ensemble)")
    print("Data:     Synthetic supply-chain event dataset")
    print("Seed:     ", RANDOM_SEED)
    print("=" * 60)

    # 1. Generate dataset
    print("\n[1/4] Generating synthetic dataset ...")
    X, y = generate_synthetic_dataset(n_normal=4000, n_anomalous=1000)
    n_total    = len(X)
    n_normal   = int(np.sum(y == 1))
    n_anomalous = int(np.sum(y == -1))

    print(f"      Total samples   : {n_total}")
    print(f"      Normal samples  : {n_normal}")
    print(f"      Anomalous samples: {n_anomalous}")
    print(f"      Features ({N_FEATURES}): {FEATURE_NAMES}")

    # 2. Build pipeline: StandardScaler → IsolationForest
    #    We train ONLY on normal events (unsupervised paradigm).
    #    IsolationForest is told 10% contamination so its threshold
    #    will classify roughly 10% of future data as anomalous.
    print("\n[2/4] Building Pipeline (StandardScaler + IsolationForest) ...")
    X_normal_only = X[y == 1]

    pipeline = Pipeline([
        ('scaler', StandardScaler()),
        ('iso_forest', IsolationForest(
            n_estimators=200,
            max_samples='auto',
            contamination=0.10,
            max_features=1.0,
            bootstrap=False,
            random_state=RANDOM_SEED,
            n_jobs=-1,
        ))
    ])

    print("\n[3/4] Training on normal samples only ...")
    pipeline.fit(X_normal_only)

    # Sanity check: predict on both classes
    preds = pipeline.predict(X)
    tp = int(np.sum((preds == -1) & (y == -1)))
    fp = int(np.sum((preds == -1) & (y ==  1)))
    tn = int(np.sum((preds ==  1) & (y ==  1)))
    fn = int(np.sum((preds ==  1) & (y == -1)))

    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall    = tp / (tp + fn) if (tp + fn) > 0 else 0.0

    print(f"      Anomaly recall on test set : {recall:.3f}  ({tp}/{n_anomalous} anomalies caught)")
    print(f"      Anomaly precision           : {precision:.3f}")
    print(f"      False positive rate (normal flagged): {fp}/{n_normal}")

    # 4. Save pipeline
    print(f"\n[4/4] Saving model to: {MODEL_PATH}")
    joblib.dump({
        'pipeline':      pipeline,
        'feature_names': FEATURE_NAMES,
        'n_normal':      n_normal,
        'n_anomalous':   n_anomalous,
        'random_seed':   RANDOM_SEED,
        'trained_at':    datetime.now(timezone.utc).isoformat(),
        'model_version': '1.0.0-synthetic',
        'note': (
            'Prototype model trained on synthetic supply-chain data. '
            'Retrain using real scan_events from PostgreSQL when available.'
        ),
    }, MODEL_PATH)

    size_kb = os.path.getsize(MODEL_PATH) / 1024
    print(f"      File size: {size_kb:.1f} KB")
    print("\n[DONE] isolation_forest.pkl saved successfully.")
    print("       Restart ml_service.py to load the new model.\n")

    return MODEL_PATH


if __name__ == '__main__':
    train_and_save()
