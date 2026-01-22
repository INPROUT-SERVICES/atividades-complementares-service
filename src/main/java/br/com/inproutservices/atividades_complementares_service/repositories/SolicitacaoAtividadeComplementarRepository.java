package br.com.inproutservices.atividades_complementares_service.repositories;

import br.com.inproutservices.atividades_complementares_service.entities.SolicitacaoAtividadeComplementar;
import br.com.inproutservices.atividades_complementares_service.enums.StatusSolicitacaoComplementar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SolicitacaoAtividadeComplementarRepository extends JpaRepository<SolicitacaoAtividadeComplementar, Long> {

    // Filtros básicos
    List<SolicitacaoAtividadeComplementar> findByStatus(StatusSolicitacaoComplementar status);

    List<SolicitacaoAtividadeComplementar> findByStatusIn(List<StatusSolicitacaoComplementar> statusList);

    List<SolicitacaoAtividadeComplementar> findBySolicitanteId(Long solicitanteId);

    // Nota: Filtros por "Segmento" agora são complexos pois o Segmento está no serviço de Usuário/OS.
    // Inicialmente buscaremos por status e o filtro de segmento deve ser feito em memória ou via cross-service query se necessário.
}