package in.edu.ssn.backend.repository;

import in.edu.ssn.backend.entity.ScanEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScanEventRepository extends JpaRepository<ScanEventEntity, Long> {

    List<ScanEventEntity> findByBatchId(String batchId);

    List<ScanEventEntity> findByNodeId(String nodeId);

    List<ScanEventEntity> findByBatchIdAndNodeId(String batchId, String nodeId);
}
