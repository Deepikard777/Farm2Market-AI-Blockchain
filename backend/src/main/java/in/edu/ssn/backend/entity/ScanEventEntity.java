package in.edu.ssn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "scan_events")
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScanEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String batchId;

    @Column(nullable = false)
    private String nodeId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String eventTime;

    /** Server-side timestamp when the event was received */
    private String receivedAt;
}
