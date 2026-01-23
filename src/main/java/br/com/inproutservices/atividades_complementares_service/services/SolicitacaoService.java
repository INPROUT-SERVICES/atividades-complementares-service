package br.com.inproutservices.atividades_complementares_service.services;

import br.com.inproutservices.atividades_complementares_service.dtos.SolicitacaoDTO;
import br.com.inproutservices.atividades_complementares_service.entities.SolicitacaoAtividadeComplementar;
import br.com.inproutservices.atividades_complementares_service.enums.StatusSolicitacaoComplementar;
import br.com.inproutservices.atividades_complementares_service.repositories.SolicitacaoAtividadeComplementarRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class SolicitacaoService {

    private final SolicitacaoAtividadeComplementarRepository repository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // URL do Monólito
    private static final String MONOLITO_URL = "http://inprout-monolito:8080";

    public SolicitacaoService(SolicitacaoAtividadeComplementarRepository repository, RestTemplateBuilder builder) {
        this.repository = repository;
        // Habilita PATCH no RestTemplate
        this.restTemplate = builder
                .requestFactory(() -> new HttpComponentsClientHttpRequestFactory())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // --- MÉTODOS AUXILIARES DE INTEGRAÇÃO ---

    /**
     * Cria os headers HTTP incluindo o Token JWT da requisição atual.
     * Isso permite que o microsserviço se autentique no monólito como o usuário logado.
     */
    private HttpEntity<Object> createHttpEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Pega o token da requisição atual que chegou no Controller
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null) {
                headers.set("Authorization", authHeader);
            }
        }

        return new HttpEntity<>(body, headers);
    }

    // --- MÉTODOS DE NEGÓCIO ---

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

        s.setLpuAprovadaId(dto.lpuId());
        s.setQuantidadeAprovada(dto.quantidade());
        s.setBoqAprovado(dto.boq());
        s.setStatusRegistroAprovado(dto.statusRegistro());
        s.setJustificativaCoordenador(dto.justificativa());

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
            // 1. APLICAR ALTERAÇÕES NOS ITENS EXISTENTES (Com Token Repassado)
            if (s.getAlteracoesPropostasJson() != null && !s.getAlteracoesPropostasJson().isBlank()) {
                List<Map<String, Object>> alteracoes = objectMapper.readValue(
                        s.getAlteracoesPropostasJson(),
                        new TypeReference<List<Map<String, Object>>>() {}
                );

                for (Map<String, Object> alt : alteracoes) {
                    Long itemId = ((Number) alt.get("itemId")).longValue();

                    // PATCH: Alterar Status
                    if (alt.containsKey("novoStatus")) {
                        String novoStatus = (String) alt.get("novoStatus");

                        // Usa exchange para poder passar Headers com Token
                        restTemplate.exchange(
                                MONOLITO_URL + "/os/detalhe/" + itemId + "/status",
                                HttpMethod.PATCH,
                                createHttpEntity(Map.of("status", novoStatus)),
                                Void.class
                        );
                    }

                    // PUT: Alterar Valores
                    if (alt.containsKey("novaQtd") || alt.containsKey("novaLpuId")) {
                        Object novaLpuId = alt.get("novaLpuId");
                        Object novaQtd = alt.get("novaQtd");
                        Object novoBoq = alt.get("novoBoq");

                        if (novaLpuId != null && novaQtd != null) {
                            Map<String, Object> updatePayload = Map.of(
                                    "quantidade", novaQtd,
                                    "boq", novoBoq != null ? novoBoq : "",
                                    "lpu", Map.of("id", novaLpuId)
                            );

                            restTemplate.exchange(
                                    MONOLITO_URL + "/os/detalhe/" + itemId,
                                    HttpMethod.PUT,
                                    createHttpEntity(updatePayload),
                                    Void.class
                            );
                        }
                    }
                }
            }

            // 2. CRIAR O NOVO ITEM (Com Token Repassado)
            Map<String, Object> novoItemPayload = Map.of(
                    "os", Map.of("id", s.getOsId()),
                    "lpu", Map.of("id", s.getLpuAprovadaId()),
                    "quantidade", s.getQuantidadeAprovada(),
                    "boq", s.getBoqAprovado() != null ? s.getBoqAprovado() : "",
                    "statusRegistro", s.getStatusRegistroAprovado() != null ? s.getStatusRegistroAprovado() : "ATIVO"
            );

            restTemplate.postForObject(
                    MONOLITO_URL + "/os/detalhe",
                    createHttpEntity(novoItemPayload),
                    Object.class
            );

        } catch (Exception e) {
            e.printStackTrace();
            // Pega a mensagem de erro interna do HttpClient se houver
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
            s.setStatus(StatusSolicitacaoComplementar.REJEITADO);
            s.setMotivoRecusa(motivo);
        }

        return repository.save(s);
    }
}