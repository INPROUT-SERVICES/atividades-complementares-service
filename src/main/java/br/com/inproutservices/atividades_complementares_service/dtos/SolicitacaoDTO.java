package br.com.inproutservices.atividades_complementares_service.dtos;

import br.com.inproutservices.atividades_complementares_service.entities.SolicitacaoAtividadeComplementar;
import br.com.inproutservices.atividades_complementares_service.enums.StatusSolicitacaoComplementar;
import java.time.LocalDateTime;

public class SolicitacaoDTO {

    public record Request(
            Long osId,
            Long lpuId,
            Double valorUnitarioLpu, // Frontend envia ou Service busca via Feign
            Integer quantidade,
            Long solicitanteId,
            String solicitanteNome, // Opcional: para snapshot
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
            Double valorTotalEstimado, // Calculado (valorSnapshot * qtd)
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
            Long lpuId, // Pode ser nova LPU
            Integer quantidade,
            String boq,
            String statusRegistro,
            String justificativa
    ) {}

    public record AcaoDTO(
            Long aprovadorId,
            String motivo
    ) {}
}