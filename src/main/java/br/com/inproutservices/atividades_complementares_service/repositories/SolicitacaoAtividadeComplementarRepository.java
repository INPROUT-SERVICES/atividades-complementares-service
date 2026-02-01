package br.com.inproutservices.atividades_complementares_service.repositories;

import br.com.inproutservices.atividades_complementares_service.entities.SolicitacaoAtividadeComplementar;
import br.com.inproutservices.atividades_complementares_service.enums.StatusSolicitacaoComplementar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SolicitacaoAtividadeComplementarRepository extends JpaRepository<SolicitacaoAtividadeComplementar, Long> {

    List<SolicitacaoAtividadeComplementar> findByStatus(StatusSolicitacaoComplementar status);

    List<SolicitacaoAtividadeComplementar> findByStatusIn(List<StatusSolicitacaoComplementar> statuses);

    List<SolicitacaoAtividadeComplementar> findByStatusInAndSegmentoIdIn(List<StatusSolicitacaoComplementar> statuses, List<Long> segmentoIds);

    List<SolicitacaoAtividadeComplementar> findBySolicitanteId(Long solicitanteId);

    // Métodos para histórico (já existentes no seu código, manter)
    List<SolicitacaoAtividadeComplementar> findTop300ByOrderByDataSolicitacaoDesc();
    List<SolicitacaoAtividadeComplementar> findTop300BySegmentoIdInOrderByDataSolicitacaoDesc(List<Long> segmentoIds);
}