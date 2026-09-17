package md.utm.telecom.analysts.repository;

import md.utm.telecom.analysts.model.Analyst;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalystRepository extends JpaRepository<Analyst, UUID> {

    Optional<Analyst> findByIssuerAndSubject(String issuer, String subject);

    List<Analyst> findAllByEnabledTrueOrderByDisplayNameAsc();
}