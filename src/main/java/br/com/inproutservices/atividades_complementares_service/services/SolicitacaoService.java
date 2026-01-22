package br.com.inproutservices.atividades_complementares_service.services;

import br.com.inproutservices.atividades_complementares_service.dtos.SolicitacaoDTO;
import br.com.inproutservices.atividades_complementares_service.entities.SolicitacaoAtividadeComplementar;
import br.com.inproutservices.atividades_complementares_service.enums.StatusSolicitacaoComplementar;
import br.com.inproutservices.atividades_complementares_service.repositories.SolicitacaoAtividadeComplementarRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class SolicitacaoService {

    private final SolicitacaoAtividadeComplementarRepository repository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // URL do Monólito dentro da rede Docker (nome do container)
    // Se estiver rodando local sem docker, use "http://localhost:8080"
    private static final String MONOLITO_URL = "http://inprout-monolito:8080";

    public SolicitacaoService(SolicitacaoAtividadeComplementarRepository repository, RestTemplateBuilder restTemplateBuilder) {
        this.repository = repository;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public SolicitacaoAtividadeComplementar criar(SolicitacaoDTO.Request dto) {
        SolicitacaoAtividadeComplementar nova = SolicitacaoAtividadeComplementar.builder()
                .osId(dto.osId())
                .lpuId(dto.lpuId())
                .quantidade(dto.quantidade())
                .solicitanteId(dto.solicitanteId())
                .solicitanteNomeSnapshot(dto.solicitanteNome())
                .valorUnitarioSnapshot(dto.valorUnitarioLpu())
                .justificativa(dto.justificativa())
                .status(StatusSolicitacaoComplementar.PENDENTE_COORDENADOR)
                .build();
        return repository.save(nova);
    }

    public SolicitacaoAtividadeComplementar buscarPorId(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Solicitação não encontrada: " + id));
    }

    /**
     * Filtra pendências por ROLE.
     */
    public List<SolicitacaoAtividadeComplementar> listarPendentes(String role) {
        if (role == null) return Collections.emptyList();
        String roleUpper = role.toUpperCase();

        if (roleUpper.contains("ADMIN")) {
            return repository.findByStatusIn(List.of(
                    StatusSolicitacaoComplementar.PENDENTE_COORDENADOR,
                    StatusSolicitacaoComplementar.PENDENTE_CONTROLLER
            ));
        } else if (roleUpper.contains("CONTROLLER")) {
            return repository.findByStatus(StatusSolicitacaoComplementar.PENDENTE_CONTROLLER);
        } else {
            // Default: Coordenador
            return repository.findByStatus(StatusSolicitacaoComplementar.PENDENTE_COORDENADOR);
        }
    }

    public List<SolicitacaoAtividadeComplementar> listarPorSolicitante(Long solicitanteId) {
        return repository.findBySolicitanteId(solicitanteId);
    }

    @Transactional
    public SolicitacaoAtividadeComplementar aprovarPeloCoordenador(Long id, SolicitacaoDTO.EdicaoCoordenadorDTO dto) {
        SolicitacaoAtividadeComplementar s = buscarPorId(id);

        if (s.getStatus() != StatusSolicitacaoComplementar.PENDENTE_COORDENADOR) {
            throw new RuntimeException("Status inválido para ação do coordenador.");
        }

        // Define a proposta para o item NOVO
        s.setLpuAprovadaId(dto.lpuId());
        s.setQuantidadeAprovada(dto.quantidade());
        s.setBoqAprovado(dto.boq());
        s.setStatusRegistroAprovado(dto.statusRegistro());
        s.setJustificativaCoordenador(dto.justificativa());

        // Salva o JSON das alterações propostas nos itens ANTIGOS
        if (dto.alteracoesItensExistentesJson() != null && !dto.alteracoesItensExistentesJson().isBlank()) {
            s.setAlteracoesPropostasJson(dto.alteracoesItensExistentesJson());
        }

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

        try {
            // 1. APLICAR ALTERAÇÕES NOS ITENS EXISTENTES (Se houver Proposta)
            if (s.getAlteracoesPropostasJson() != null && !s.getAlteracoesPropostasJson().isBlank()) {
                List<Map<String, Object>> alteracoes = objectMapper.readValue(
                        s.getAlteracoesPropostasJson(),
                        new TypeReference<List<Map<String, Object>>>() {}
                );

                for (Map<String, Object> alt : alteracoes) {
                    Long itemId = ((Number) alt.get("itemId")).longValue();

                    // Se tiver mudança de status
                    if (alt.containsKey("novoStatus")) {
                        String novoStatus = (String) alt.get("novoStatus");
                        restTemplate.patchForObject(
                                MONOLITO_URL + "/os/detalhe/" + itemId + "/status",
                                Map.of("status", novoStatus),
                                Void.class
                        );
                    }

                    // Se tiver edição de valores (LPU, Qtd, BOQ)
                    if (alt.containsKey("novaQtd") || alt.containsKey("novaLpuId")) {
                        // Monta payload de atualização
                        // Nota: O backend do monólito espera { lpu: {id: ...}, quantidade: ..., boq: ... }
                        Object novaLpuId = alt.get("novaLpuId"); // Pode ser null se não mudou, tratar se necessário
                        Object novaQtd = alt.get("novaQtd");
                        Object novoBoq = alt.get("novoBoq");

                        // Aqui assumimos que se o campo veio no JSON, ele deve ser atualizado.
                        // O ideal seria buscar o item original para fazer merge, mas vamos mandar o que temos.
                        if (novaLpuId != null && novaQtd != null) {
                            Map<String, Object> updatePayload = Map.of(
                                    "quantidade", novaQtd,
                                    "boq", novoBoq != null ? novoBoq : "",
                                    "lpu", Map.of("id", novaLpuId)
                            );

                            restTemplate.put(
                                    MONOLITO_URL + "/os/detalhe/" + itemId,
                                    updatePayload
                            );
                        }
                    }
                }
            }

            // 2. CRIAR O NOVO ITEM (Solicitado e Aprovado)
            // Endpoint imaginário: POST /os/detalhe (Adicionar item na OS)
            Map<String, Object> novoItemPayload = Map.of(
                    "os", Map.of("id", s.getOsId()),
                    "lpu", Map.of("id", s.getLpuAprovadaId()),
                    "quantidade", s.getQuantidadeAprovada(),
                    "boq", s.getBoqAprovado() != null ? s.getBoqAprovado() : "",
                    "statusRegistro", s.getStatusRegistroAprovado() != null ? s.getStatusRegistroAprovado() : "ATIVO"
            );

            // Chama o endpoint de criação no monólito
            // Dica: Verifique se o seu controller de OS tem um método POST para criar itens
            restTemplate.postForObject(MONOLITO_URL + "/os/detalhe", novoItemPayload, Object.class);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Erro ao integrar com o Monólito: " + e.getMessage());
        }

        s.setAprovadorControllerId(aprovadorId);
        s.setDataAcaoController(LocalDateTime.now());
        s.setStatus(StatusSolicitacaoComplementar.APROVADO);

        return repository.save(s);
    }

    @Transactional
    public SolicitacaoAtividadeComplementar rejeitar(Long id, Long aprovadorId, String motivo, String roleOrigem) {
        SolicitacaoAtividadeComplementar s = buscarPorId(id);

        if (motivo == null || motivo.isBlank()) throw new RuntimeException("Motivo obrigatório.");

        if (roleOrigem != null && roleOrigem.toUpperCase().contains("COORDINATOR")) {
            s.setAprovadorCoordenadorId(aprovadorId);
            s.setDataAcaoCoordenador(LocalDateTime.now());
            s.setStatus(StatusSolicitacaoComplementar.REJEITADO);
            s.setMotivoRecusa(motivo);
        } else if (roleOrigem != null && roleOrigem.toUpperCase().contains("CONTROLLER")) {
            s.setAprovadorControllerId(aprovadorId);
            s.setDataAcaoController(LocalDateTime.now());
            s.setStatus(StatusSolicitacaoComplementar.PENDENTE_COORDENADOR);
            s.setJustificativaController(motivo);
        } else {
            // Fallback
            s.setStatus(StatusSolicitacaoComplementar.REJEITADO);
            s.setMotivoRecusa(motivo);
        }

        return repository.save(s);
    }
}