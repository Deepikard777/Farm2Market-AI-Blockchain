package in.edu.ssn.backend.repository;

import in.edu.ssn.backend.entity.AnomalyFlagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnomalyFlagRepository extends JpaRepository<AnomalyFlagEntity, String> {

    List<AnomalyFlagEntity> findByBatchId(String batchId);

    List<AnomalyFlagEntity> findByReviewStatus(String reviewStatus);

    List<AnomalyFlagEntity> findByDecision(String decision);

    List<AnomalyFlagEntity> findByBatchIdAndReviewStatus(String batchId, String reviewStatus);
}
