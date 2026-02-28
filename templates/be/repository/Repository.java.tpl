package {{packageName}}.repository;

import {{packageName}}.domain.{{entityName}};
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface {{entityName}}Repository extends JpaRepository<{{entityName}}, UUID> {

    // TODO: Add custom query methods as needed
    // Example: List<{{entityName}}> findByStatus(String status);
    // Example: @Query("SELECT e FROM {{entityName}} e WHERE e.createdAt > :since")
    //          List<{{entityName}}> findRecent(@Param("since") LocalDateTime since);
}
