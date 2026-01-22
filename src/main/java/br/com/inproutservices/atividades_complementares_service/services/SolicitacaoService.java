package br.com.inproutservices.atividades_complementares_service.services;

import br.com.inproutservices.atividades_complementares_service.dtos.SolicitacaoDTO;
import br.com.inproutservices.atividades_complementares_service.entities.SolicitacaoAtividadeComplementar;
import br.com.inproutservices.atividades_complementares_service.enums.StatusSolicitacaoComplementar;
import br.com.inproutservices.atividades_complementares_service.repositories.SolicitacaoAtividadeComplementarRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SolicitacaoService {

    private final SolicitacaoAtividadeComplementarRepository repository;

    public SolicitacaoService(SolicitacaoAtividadeComplementarRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public SolicitacaoAtividadeComplementar criar(SolicitacaoDTO.Request dto) {
        // TODO: Validar se OS e Usuário existem via chamadas HTTP (FeignClient) aos outros microsserviços

        SolicitacaoAtividadeComplementar nova = SolicitacaoAtividadeComplementar.builder()
                .osId(dto.osId())
                .lpuId(dto.lpuId())
                .quantidade(dto.quantidade())
                .solicitanteId(dto.solicitanteId())
                .solicitanteNomeSnapshot(dto.solicitanteNome()) // Opcional
                .valorUnitarioSnapshot(dto.valorUnitarioLpu()) // Importante para histórico
                .justificativa(dto.justificativa())
                .status(StatusSolicitacaoComplementar.PENDENTE_COORDENADOR)
                .build();

        return repository.save(nova);
    }

    public SolicitacaoAtividadeComplementar buscarPorId(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Solicitação não encontrada: " + id));
    }

    public List<SolicitacaoAtividadeComplementar> listarPendentes() {
        return repository.findByStatusIn(List.of(
                StatusSolicitacaoComplementar.PENDENTE_COORDENADOR,
                StatusSolicitacaoComplementar.PENDENTE_CONTROLLER
        ));
    }

    public List<SolicitacaoAtividadeComplementar> listarPorSolicitante(Long solicitanteId) {
        return repository.findBySolicitanteId(solicitanteId);
    }

    @Transactional
    public SolicitacaoAtividadeComplementar aprovarPeloCoordenador(Long id, SolicitacaoDTO.EdicaoCoordenadorDTO dto) {
        SolicitacaoAtividadeComplementar s = buscarPorId(id);

        if (s.getStatus() != StatusSolicitacaoComplementar.PENDENTE_COORDENADOR) {
            throw new RuntimeException("Solicitação não está pendente para coordenador.");
        }

        // Aplica edições
        s.setLpuAprovadaId(dto.lpuId() != null ? dto.lpuId() : s.getLpuId());
        s.setQuantidadeAprovada(dto.quantidade() != null ? dto.quantidade() : s.getQuantidade());
        s.setBoqAprovado(dto.boq());
        s.setStatusRegistroAprovado(dto.statusRegistro() != null ? dto.statusRegistro() : "ATIVO");
        s.setJustificativaCoordenador(dto.justificativa());

        // Atualiza estado
        s.setAprovadorCoordenadorId(dto.aprovadorId());
        s.setDataAcaoCoordenador(LocalDateTime.now());
        s.setStatus(StatusSolicitacaoComplementar.PENDENTE_CONTROLLER);

        return repository.save(s);
    }

    @Transactional
    public SolicitacaoAtividadeComplementar aprovarPeloController(Long id, Long aprovadorId) {
        SolicitacaoAtividadeComplementar s = buscarPorId(id);

        if (s.getStatus() != StatusSolicitacaoComplementar.PENDENTE_CONTROLLER) {
            throw new RuntimeException("Solicitação não está pendente para controller.");
        }

        // TODO: INTEGRAR COM MICROSERVIÇO DE OS (Criar Item)
        // Exemplo: osClient.criarItem(s.getOsId(), s.getLpuAprovadaId(), s.getQuantidadeAprovada(), ...);
        System.out.println("INTEGRAÇÃO: Enviando comando para criar item na OS " + s.getOsId());

        s.setAprovadorControllerId(aprovadorId);
        s.setDataAcaoController(LocalDateTime.now());
        s.setStatus(StatusSolicitacaoComplementar.APROVADO);

        return repository.save(s);
    }

    @Transactional
    public SolicitacaoAtividadeComplementar rejeitar(Long id, Long aprovadorId, String motivo, String roleOrigem) {
        SolicitacaoAtividadeComplementar s = buscarPorId(id);

        if (motivo == null || motivo.isBlank()) throw new RuntimeException("Motivo obrigatório.");

        if ("COORDINATOR".equalsIgnoreCase(roleOrigem)) {
            s.setAprovadorCoordenadorId(aprovadorId);
            s.setDataAcaoCoordenador(LocalDateTime.now());
            s.setStatus(StatusSolicitacaoComplementar.REJEITADO);
            s.setMotivoRecusa(motivo);
        } else if ("CONTROLLER".equalsIgnoreCase(roleOrigem)) {
            // Controller devolve para coordenador (Regra original) ou rejeita final?
            // Baseado no código original: "rejeitarPeloController" voltava para o coordenador
            s.setAprovadorControllerId(aprovadorId);
            s.setDataAcaoController(LocalDateTime.now());
            s.setStatus(StatusSolicitacaoComplementar.PENDENTE_COORDENADOR);
            s.setJustificativaController(motivo);
        }

        return repository.save(s);
    }
}