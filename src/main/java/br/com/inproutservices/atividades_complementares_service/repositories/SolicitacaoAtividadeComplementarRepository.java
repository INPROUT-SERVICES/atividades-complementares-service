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

    List<SolicitacaoAtividadeComplementar> findTop300ByOrderByDataSolicitacaoDesc();

}