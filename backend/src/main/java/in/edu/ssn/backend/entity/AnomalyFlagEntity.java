package in.edu.ssn.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Persists an anomaly flag raised by the AI detection layer.
 *
 * lifecycle:
 *  SUSPICIOUS → farmer/admin reviews → APPROVED (event is then committed to blockchain)
 *                                    → REJECTED  (event discarded)
 *  REJECTED   → stored for audit trail, no blockchain action taken
 */
@Entity
@Table(name = "anomaly_flags")
@Data
public class AnomalyFlagEntity {

    @Id
    private String id;

    /** The product batch this event belongs to */
    @Column(nullable = false)
    private String batchId;

    /** Node/role that generated the event */
    private String nodeId;
    private String nodeRole;

    /** Event payload fields captured at detection time */
    private String eventType;
    private String eventTime;
    private String location;
    private String operatorId;
    private String operatorName;

    /** AI detection output */
    private Double anomalyScore;          // 0.0 – 1.0

    @Column(columnDefinition = "TEXT")
    private String signals;               // JSON array of signal strings

    /** SUSPICIOUS | REJECTED */
    private String decision;

    /** PENDING | APPROVED | REJECTED */
    private String reviewStatus;

    private String reviewedBy;
    private String reviewedAt;

    /** Server-side timestamp when the flag was created */
    @Column(nullable = false)
    private String createdAt;

    /**
     * If the flag was APPROVED and a blockchain tx was subsequently submitted,
     * store the txHash here for the audit trail.
     */
    private String approvedTxHash;
}
