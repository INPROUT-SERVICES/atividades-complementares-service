package br.com.inproutservices.atividades_complementares_service.dtos;

import br.com.inproutservices.atividades_complementares_service.entities.SolicitacaoAtividadeComplementar;
import br.com.inproutservices.atividades_complementares_service.enums.StatusSolicitacaoComplementar;
import java.time.LocalDateTime;

public class SolicitacaoDTO {

    public record Request(
            Long osId,
            Long lpuId,
            Double valorUnitarioLpu,
            Integer quantidade,
            Long solicitanteId,
            String solicitanteNome,
            String justificativa
    ) {}

    public record Response(
            Long id,
            Long osId,
            Long lpuOriginalId,
            Integer quantidadeOriginal,
            Long lpuAprovadaId,
            Integer quantidadeAprovada,
            String boqAprovado,
            String statusRegistroAprovado,
            String justificativaCoordenador, // Adicionado para o Controller ver a justificativa
            String alteracoesPropostasJson,  // <--- OBRIGATÓRIO PARA O CONTROLLER VER AS EDIÇÕES
            Double valorTotalEstimado,
            String justificativa,
            StatusSolicitacaoComplementar status,
            LocalDateTime dataSolicitacao,
            String motivoRecusa
    ) {
        public Response(SolicitacaoAtividadeComplementar s) {
            this(
                    s.getId(),
                    s.getOsId(),
                    s.getLpuId(),
                    s.getQuantidade(),
                    s.getLpuAprovadaId(),
                    s.getQuantidadeAprovada(),
                    s.getBoqAprovado(),
                    s.getStatusRegistroAprovado(),
                    s.getJustificativaCoordenador(),
                    s.getAlteracoesPropostasJson(), // <--- Mapeando aqui
                    (s.getValorUnitarioSnapshot() != null ? s.getValorUnitarioSnapshot() * s.getQuantidade() : 0.0),
                    s.getJustificativa(),
                    s.getStatus(),
                    s.getDataSolicitacao(),
                    s.getMotivoRecusa()
            );
        }
    }

    public record EdicaoCoordenadorDTO(
            Long aprovadorId,
            Long lpuId,
            Integer quantidade,
            String boq,
            String statusRegistro,
            String justificativa,
            String alteracoesItensExistentesJson
    ) {}

    public record AcaoDTO(
            Long aprovadorId,
            String motivo
    ) {}
}