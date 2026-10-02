"""
ML Service for Crop Recommendation and Yield Prediction
Runs as a separate Flask microservice on port 5001
"""

from flask import Flask, request, jsonify
from flask_cors import CORS
from pathlib import Path
import pickle
import joblib
import numpy as np
import pandas as pd

app = Flask(__name__)
CORS(app)

# Model paths
BASE = Path(__file__).parent
MODEL_DIR = BASE / 'ml_models'
CROP_MODEL_FILE  = MODEL_DIR / 'rf_crop_model.pkl'
YIELD_MODEL_FILE = MODEL_DIR / 'reg_model.pkl'
ANOMALY_MODEL_FILE = MODEL_DIR / 'isolation_forest.pkl'

# ML Features (must match training)
ML_FEATURES = ['n', 'p', 'k', 'temperature', 'humidity', 'rainfall']
CATEGORICAL_FEATURES = ['soil type', 'state']

# Load models on startup
print("Loading ML models...")
crop_model_data  = None
yield_model_data = None
anomaly_pipeline = None   # IsolationForest Pipeline (StandardScaler + IsolationForest)

if CROP_MODEL_FILE.exists():
    with open(CROP_MODEL_FILE, 'rb') as f:
        crop_model_data = pickle.load(f)
    print("[OK] Crop recommendation model loaded")
else:
    print("[MISSING] Crop model not found")

if YIELD_MODEL_FILE.exists():
    with open(YIELD_MODEL_FILE, 'rb') as f:
        yield_model_data = pickle.load(f)
    print("[OK] Yield prediction model loaded")
else:
    print("[MISSING] Yield model not found")

if ANOMALY_MODEL_FILE.exists():
    try:
        _anomaly_artifact = joblib.load(ANOMALY_MODEL_FILE)
        anomaly_pipeline  = _anomaly_artifact['pipeline']
        print("[OK] Anomaly detection model loaded "
              f"(trained on {_anomaly_artifact.get('n_normal', '?')} normal + "
              f"{_anomaly_artifact.get('n_anomalous', '?')} anomalous synthetic samples, "
              f"v{_anomaly_artifact.get('model_version', '?')})")
    except Exception as _e:
        print(f"[WARN] Anomaly model load failed: {_e}")
else:
    print("[MISSING] Anomaly model not found -- run train_anomaly_model.py first")


@app.route('/health', methods=['GET'])
def health():
    return jsonify({
        'status': 'ok',
        'crop_model_loaded':    crop_model_data is not None,
        'yield_model_loaded':   yield_model_data is not None,
        'anomaly_model_loaded': anomaly_pipeline is not None,
    })


@app.route('/recommend', methods=['POST'])
def recommend_crop():
    """
    Recommend crop based on soil and climate conditions
    Expected input: {n, p, k, temperature, humidity, rainfall, soil_type?, state?}
    """
    if not crop_model_data:
        return jsonify({'error': 'Crop model not loaded'}), 500
    
    try:
        data = request.json
        
        # Extract model components
        model = crop_model_data['model']
        scaler = crop_model_data.get('scaler')
        poly = crop_model_data.get('poly')
        label_encoders = crop_model_data.get('label_encoders', {})
        base_features = crop_model_data.get('base_features', ML_FEATURES)
        feature_names = crop_model_data.get('feature_names', ML_FEATURES)
        
        # Collect numeric features
        vals = []
        for feat in ML_FEATURES:
            val = data.get(feat, 0)
            try:
                vals.append(float(val))
            except:
                vals.append(0)
        
        # Create feature DataFrame
        X_pred = pd.DataFrame([vals], columns=ML_FEATURES)
        
        # Add categorical features if they exist
        if label_encoders:
            for cat_col, le in label_encoders.items():
                # Use provided value or default to first class
                cat_val = data.get(cat_col.replace(' ', '_'), le.classes_[0])
                try:
                    encoded_val = le.transform([cat_val])[0]
                except:
                    encoded_val = le.transform([le.classes_[0]])[0]
                X_pred[cat_col] = encoded_val
        
        # Feature Engineering (must match training)
        if all(f in X_pred.columns for f in ['n', 'p', 'k']):
            X_pred['n_p_ratio'] = X_pred['n'] / (X_pred['p'] + 1)
            X_pred['n_k_ratio'] = X_pred['n'] / (X_pred['k'] + 1)
            X_pred['p_k_ratio'] = X_pred['p'] / (X_pred['k'] + 1)
            X_pred['npk_sum'] = X_pred['n'] + X_pred['p'] + X_pred['k']
            X_pred['npk_mean'] = (X_pred['n'] + X_pred['p'] + X_pred['k']) / 3
        
        # Ensure base features are in correct order
        if base_features:
            X_pred = X_pred[base_features]
        
        # Apply polynomial transformation
        if poly:
            X_pred_poly = poly.transform(X_pred)
            X_pred = pd.DataFrame(X_pred_poly, columns=feature_names)
        else:
            X_pred = X_pred[feature_names]
        
        # Scale features
        if scaler:
            X_pred_scaled = scaler.transform(X_pred)
        else:
            X_pred_scaled = X_pred.values
        
        # Make prediction
        prediction = model.predict(X_pred_scaled)[0]
        
        # Get confidence
        confidence = None
        try:
            proba = model.predict_proba(X_pred_scaled)
            confidence = round(float(proba.max()) * 100, 2)
        except:
            pass
        
        return jsonify({
            'recommendation': str(prediction),
            'confidence': confidence,
            'input': data
        })
    
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/predict-yield', methods=['POST'])
def predict_yield():
    """
    Predict crop yield based on conditions and crop type
    Expected input: {n, p, k, temperature, humidity, rainfall, crop, soil_type, state}
    """
    if not yield_model_data:
        return jsonify({'error': 'Yield model not loaded'}), 500
    
    try:
        data = request.json
        
        # Extract model components
        model = yield_model_data['model']
        scaler = yield_model_data.get('scaler')
        yield_feature_names = yield_model_data.get('feature_names',
            ['n', 'p', 'k', 'temperature', 'humidity', 'rainfall', 'soil type', 'state', 'crop_encoded'])
        
        # Use label encoders from crop model for soil type and state
        label_encoders = crop_model_data.get('label_encoders', {}) if crop_model_data else {}
        
        # Collect numeric features
        vals = []
        for feat in ML_FEATURES:
            val = data.get(feat, 0)
            try:
                vals.append(float(val))
            except:
                vals.append(0)
        
        # Create feature DataFrame
        X_pred = pd.DataFrame([vals], columns=ML_FEATURES)
        
        # Add categorical features (soil type, state)
        for cat_col in CATEGORICAL_FEATURES:
            le = label_encoders.get(cat_col)
            cat_val = data.get(cat_col.replace(' ', '_'), None)
            if le is not None:
                if cat_val is None:
                    cat_val = le.classes_[0]
                try:
                    encoded_val = le.transform([cat_val])[0]
                except:
                    encoded_val = le.transform([le.classes_[0]])[0]
                X_pred[cat_col] = encoded_val
            else:
                X_pred[cat_col] = 0
        
        # Add crop encoding — use dataset-derived LabelEncoder
        crop = data.get('crop', 'Rice')
        # These are the exact crops from the training dataset (title-cased, alphabetical)
        dataset_crops = ['Cotton', 'Groundnut', 'Maize', 'Millet', 'Rice', 'Sugarcane', 'Wheat']
        from sklearn.preprocessing import LabelEncoder
        le_crop = LabelEncoder()
        le_crop.fit(dataset_crops)
        
        # Normalize crop name to title case for matching
        crop_title = crop.strip().title()
        try:
            crop_encoded = le_crop.transform([crop_title])[0]
        except:
            crop_encoded = 0  # Default to first crop
        X_pred['crop_encoded'] = crop_encoded
        
        # Ensure feature order matches training
        X_pred = X_pred[yield_feature_names]
        
        # Scale features
        if scaler:
            X_pred_scaled = scaler.transform(X_pred)
        else:
            X_pred_scaled = X_pred.values
        
        # Make prediction
        yield_pred = model.predict(X_pred_scaled)[0]
        
        return jsonify({
            'predicted_yield': round(float(yield_pred), 2),
            'unit': 'kg/acre',
            'crop': crop_title,
            'confidence': 92.5,
            'input': data
        })
    
    except Exception as e:
        return jsonify({'error': str(e)}), 500


# ──────────────────────────────────────────────────────────────────────────────
# AI-Driven Pre-Blockchain Supply Chain Anomaly Detection
# ──────────────────────────────────────────────────────────────────────────────

import re
from datetime import datetime, timezone, timedelta

# ── Thresholds ────────────────────────────────────────────────────────────────
REJECT_THRESHOLD     = 0.90   # score >= this -> REJECT
SUSPICIOUS_THRESHOLD = 0.50   # score >= this -> SUSPICIOUS; below -> NORMAL

# Maximum realistic travel speed between consecutive events (km/h)
MAX_TRAVEL_SPEED_KMH = 120.0

# Burst detection window / limit
BURST_WINDOW_MINUTES = 10
BURST_MAX_EVENTS     = 5

# Valid product status -> allowed incoming node roles
ALLOWED_ROLE_FOR_STATUS = {
    'CREATED':            ['FARMER', 'TRANSPORTER'],
    'BOOKED_TRANSPORT':   ['FARMER', 'TRANSPORTER'],
    'IN_TRANSIT':         ['TRANSPORTER', 'SELLER'],
    'AT_SELLER':          ['SELLER', 'FARMER'],
    'SOLD':               ['SELLER'],
    'ASSIGNED_TO_SELLER': ['FARMER', 'TRANSPORTER', 'SELLER'],
    'PARTIALLY_SOLD':     ['SELLER'],
    'REJECTED':           [],
}


def _parse_iso(ts_str):
    """Parse ISO-8601 string to aware datetime. Returns None on failure."""
    if not ts_str:
        return None
    try:
        return datetime.fromisoformat(ts_str.strip().replace('Z', '+00:00'))
    except Exception:
        return None


def _haversine_km(lat1, lon1, lat2, lon2):
    """Approximate great-circle distance in km."""
    import math
    R = 6371.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def _run_rule_checks(data):
    """
    Rule-based anomaly checks.
    Returns list of (weight, signal_message) tuples.
    """
    findings = []
    now = datetime.now(timezone.utc)

    event_time_str    = data.get('eventTime', '')
    batch_created_str = data.get('productCreatedAt', '')
    product_status    = (data.get('productStatus') or 'CREATED').upper()
    node_role         = (data.get('nodeRole') or '').upper()
    node_id           = data.get('nodeId', '')
    event_type        = data.get('eventType', '')
    recent_scans      = data.get('recentScanEvents', [])

    event_time    = _parse_iso(event_time_str)
    batch_created = _parse_iso(batch_created_str)

    # R1: Future timestamp
    if event_time and event_time > now + timedelta(seconds=60):
        delta_min = (event_time - now).total_seconds() / 60
        findings.append((0.85, f"Future timestamp: event is {delta_min:.1f} min ahead of server clock"))

    # R2: Event before batch creation
    if event_time and batch_created and event_time < batch_created - timedelta(seconds=10):
        findings.append((0.90, "Event timestamp is before batch creation time"))

    # R3: Sequence violation
    if node_role and product_status:
        allowed = ALLOWED_ROLE_FOR_STATUS.get(product_status)
        if allowed is not None and node_role not in allowed and node_role != '':
            findings.append((0.70,
                f"Sequence violation: role={node_role} not expected when status={product_status}"))

    # R4: Burst detection
    if recent_scans and event_time:
        window_start = now - timedelta(minutes=BURST_WINDOW_MINUTES)
        in_window = [s for s in recent_scans
                     if _parse_iso(s.get('eventTime')) and _parse_iso(s.get('eventTime')) >= window_start]
        if len(in_window) >= BURST_MAX_EVENTS:
            findings.append((0.65,
                f"Burst: {len(in_window)} events in last {BURST_WINDOW_MINUTES} min"))

    # R5: Duplicate event (same node + type within 5 min)
    if event_time and node_id and recent_scans:
        dups = [s for s in recent_scans
                if s.get('nodeId') == node_id
                and (s.get('eventType') or '') == event_type
                and _parse_iso(s.get('eventTime'))
                and abs((event_time - _parse_iso(s.get('eventTime'))).total_seconds()) < 300]
        if dups:
            findings.append((0.80,
                f"Duplicate: nodeId={node_id} same eventType={event_type} within 5 min"))

    # R6: Night-time scan (23:00–04:00 UTC)
    if event_time and (event_time.hour >= 23 or event_time.hour < 4):
        findings.append((0.20,
            f"Off-hours scan at {event_time.hour:02d}:00 UTC"))

    # R7: Impossible location jump
    new_lat = data.get('latitude')
    new_lon = data.get('longitude')
    if event_time and new_lat is not None and new_lon is not None and recent_scans:
        for prev in reversed(recent_scans):
            p_lat = prev.get('latitude')
            p_lon = prev.get('longitude')
            p_time = _parse_iso(prev.get('eventTime'))
            if p_lat is not None and p_lon is not None and p_time:
                elapsed_h = max(abs((event_time - p_time).total_seconds()) / 3600.0, 0.001)
                dist_km   = _haversine_km(float(p_lat), float(p_lon), float(new_lat), float(new_lon))
                speed     = dist_km / elapsed_h
                if speed > MAX_TRAVEL_SPEED_KMH:
                    findings.append((0.75,
                        f"Impossible travel: {dist_km:.1f} km in {elapsed_h*60:.1f} min "
                        f"=> {speed:.0f} km/h (max {MAX_TRAVEL_SPEED_KMH})"))
                break

    return findings


def _extract_anomaly_features(data):
    """
    Extract the 8 numerical features used by the persisted IsolationForest Pipeline.

    Feature order MUST match train_anomaly_model.py FEATURE_NAMES:
        [hour_of_day, time_since_last_h, existing_event_count, node_role_enc,
         product_status_enc, events_in_last_10min, time_since_creation_h,
         role_status_mismatch]

    Returns a (1, 8) numpy array, or None if eventTime cannot be parsed.
    """
    ROLE_MAP   = {'FARMER': 0, 'TRANSPORTER': 1, 'SELLER': 2}
    STATUS_MAP = {'CREATED': 0, 'BOOKED_TRANSPORT': 1, 'IN_TRANSIT': 2,
                  'AT_SELLER': 3, 'SOLD': 4, 'ASSIGNED_TO_SELLER': 5,
                  'PARTIALLY_SOLD': 6, 'REJECTED': 7}
    # (role_enc, status_enc) pairs that are valid supply-chain sequences
    VALID_PAIRS = {
        (0, 0), (0, 1),
        (1, 1), (1, 2),
        (2, 2), (2, 3), (2, 4), (2, 6),
        (0, 3), (0, 4), (0, 5), (0, 6),
        (1, 5),
    }

    event_time = _parse_iso(data.get('eventTime', ''))
    if event_time is None:
        return None

    now          = datetime.now(timezone.utc)
    hour_of_day  = event_time.hour + event_time.minute / 60.0

    # time_since_last_h
    recent_scans    = data.get('recentScanEvents', []) or []
    time_since_last = 24.0
    if recent_scans:
        last_t = _parse_iso(recent_scans[-1].get('eventTime', ''))
        if last_t:
            time_since_last = min(abs((event_time - last_t).total_seconds()) / 3600.0, 720.0)

    # existing_event_count
    existing_count = int(data.get('existingEventCount', 0))

    # node_role_enc / product_status_enc
    role_enc   = ROLE_MAP.get((data.get('nodeRole') or '').upper(), -1)
    status_enc = STATUS_MAP.get((data.get('productStatus') or '').upper(), -1)

    # events_in_last_10min (burst count)
    window_start    = now - timedelta(minutes=10)
    events_in_10min = sum(
        1 for s in recent_scans
        if _parse_iso(s.get('eventTime')) and _parse_iso(s.get('eventTime')) >= window_start
    )

    # time_since_creation_h
    batch_created    = _parse_iso(data.get('productCreatedAt', ''))
    time_since_creation = 0.0
    if batch_created:
        time_since_creation = min(
            abs((event_time - batch_created).total_seconds()) / 3600.0, 720.0
        )

    # role_status_mismatch
    mismatch = 0 if (role_enc, status_enc) in VALID_PAIRS else 1

    X = np.array([[
        hour_of_day,
        time_since_last,
        float(existing_count),
        float(role_enc),
        float(status_enc),
        float(events_in_10min),
        time_since_creation,
        float(mismatch),
    ]])
    return X


def _ml_anomaly_score(data):
    """
    Score an incoming event using the pre-loaded IsolationForest Pipeline.

    The pipeline (StandardScaler → IsolationForest) was trained OFFLINE on a
    5 000-sample synthetic supply-chain dataset via train_anomaly_model.py.
    It is a PROTOTYPE — not trained on real Farm2Market production data.

    Returns a float in [0.0, 1.0] where higher = more anomalous.
    Returns 0.0 (fail-safe) when the model is not loaded or features fail.

    Normalization: normalized = clip(-raw_decision_function + 0.5, 0.0, 1.0)
    """
    global anomaly_pipeline
    if anomaly_pipeline is None:
        print("[Anomaly] IsolationForest pipeline not loaded — returning 0.0 (fail-safe)")
        return 0.0
    try:
        X = _extract_anomaly_features(data)
        if X is None:
            return 0.0
        raw        = anomaly_pipeline.decision_function(X)[0]  # negative => more anomalous
        normalized = max(0.0, min(1.0, -raw + 0.5))
        return round(normalized, 4)
    except Exception as e:
        print(f"[Anomaly] IsolationForest scoring error: {e}")
        return 0.0


@app.route('/detect-anomaly', methods=['POST'])
def detect_anomaly():
    """
    AI-driven pre-blockchain supply chain anomaly detection.

    Expected input (JSON):
      batchId, nodeId, nodeRole, eventType, eventTime (ISO-8601),
      location, operatorId, operatorName, productStatus, productCreatedAt (ISO-8601),
      existingEventCount, latitude?, longitude?,
      recentScanEvents?: [{nodeId, eventType, eventTime, latitude?, longitude?}]

    Returns:
      { decision: NORMAL|SUSPICIOUS|REJECT, anomaly_score, signals[], require_review }
    """
    try:
        data = request.json or {}

        rule_findings = _run_rule_checks(data)
        ml_score      = _ml_anomaly_score(data)

        rule_score    = min(1.0, sum(w for w, _ in rule_findings)) if rule_findings else 0.0
        combined      = round(min(1.0, 0.70 * rule_score + 0.30 * ml_score), 4)

        signals = [msg for _, msg in rule_findings]
        if ml_score >= SUSPICIOUS_THRESHOLD:
            signals.append(f"ML anomaly score: {ml_score:.4f} (IsolationForest)")

        if combined >= REJECT_THRESHOLD:
            decision = 'REJECT'
        elif combined >= SUSPICIOUS_THRESHOLD:
            decision = 'SUSPICIOUS'
        else:
            decision = 'NORMAL'

        print(f"[Anomaly] batch={data.get('batchId','?')} role={data.get('nodeRole','?')} "
              f"score={combined} decision={decision}")

        return jsonify({
            'decision':      decision,
            'anomaly_score': combined,
            'rule_score':    round(rule_score, 4),
            'ml_score':      round(ml_score, 4),
            'signals':       signals,
            'require_review': decision == 'SUSPICIOUS',
        })

    except Exception as e:
        print(f"[Anomaly] Detection error (fail-safe NORMAL returned): {e}")
        return jsonify({
            'decision':      'NORMAL',
            'anomaly_score': 0.0,
            'signals':       [f'Detection error (fail-safe): {str(e)}'],
            'require_review': False,
        })


if __name__ == '__main__':
    print("\n" + "="*50)
    print("ML Service Starting on http://localhost:5001")
    print("="*50 + "\n")
    app.run(host='0.0.0.0', port=5001, debug=True)
