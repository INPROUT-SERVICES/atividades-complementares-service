package br.com.inproutservices.atividades_complementares_service.services;

import br.com.inproutservices.atividades_complementares_service.dtos.SolicitacaoDTO;
import br.com.inproutservices.atividades_complementares_service.entities.SolicitacaoAtividadeComplementar;
import br.com.inproutservices.atividades_complementares_service.enums.StatusSolicitacaoComplementar;
import br.com.inproutservices.atividades_complementares_service.repositories.SolicitacaoAtividadeComplementarRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SolicitacaoService {

    private static final Logger log = LoggerFactory.getLogger(SolicitacaoService.class);

    private final SolicitacaoAtividadeComplementarRepository repository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final Map<Long, Long> cacheSegmentoOs = new HashMap<>();

    public SolicitacaoService(SolicitacaoAtividadeComplementarRepository repository, RestTemplateBuilder builder) {
        this.repository = repository;
        this.restTemplate = builder
                .requestFactory(() -> new HttpComponentsClientHttpRequestFactory())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    // --- MÉTODOS DE NEGÓCIO ---

    public List<SolicitacaoAtividadeComplementar> listarPendentes(String role, Long userId) {
        if (role == null) return Collections.emptyList();
        String roleUpper = role.toUpperCase();

        // 1. ADMIN ou CONTROLLER: Vê TUDO (sem restrição de segmento, conforme solicitado)
        if (roleUpper.contains("ADMIN") || roleUpper.contains("CONTROLLER")) {
            // Se for Controller, foca apenas no status dele
            StatusSolicitacaoComplementar statusAlvo = roleUpper.contains("CONTROLLER")
                    ? StatusSolicitacaoComplementar.PENDENTE_CONTROLLER
                    : StatusSolicitacaoComplementar.PENDENTE_COORDENADOR; // ou ambos para Admin

            if (roleUpper.contains("ADMIN")) {
                return repository.findByStatusIn(List.of(
                        StatusSolicitacaoComplementar.PENDENTE_COORDENADOR,
                        StatusSolicitacaoComplementar.PENDENTE_CONTROLLER
                ));
            }
            return repository.findByStatus(statusAlvo);
        }

        // 2. COORDENADOR: Restrição estrita de Segmento (Query no Banco)
        else if (roleUpper.contains("COORDINATOR") || roleUpper.contains("COORDENADOR")) {
            List<Long> segmentosDoUsuario = buscarSegmentosDoUsuario(userId);

            if (segmentosDoUsuario.isEmpty()) {
                log.warn("Coordenador ID {} tentou listar pendências mas não possui segmentos.", userId);
                return Collections.emptyList();
            }

            // Busca direta no banco filtrando por Status E Lista de Segmentos
            return repository.findByStatusAndSegmentoIdIn(
                    StatusSolicitacaoComplementar.PENDENTE_COORDENADOR,
                    segmentosDoUsuario
            );
        }

        return Collections.emptyList();
    }

    public List<SolicitacaoAtividadeComplementar> listarHistorico(String role, Long userId) {
        boolean ehAdminOuController = role != null && (role.toUpperCase().contains("ADMIN") || role.toUpperCase().contains("CONTROLLER"));
        boolean ehGestorSegmentado = role != null && (role.toUpperCase().contains("COORDINATOR") || role.toUpperCase().contains("MANAGER") || role.toUpperCase().contains("COORDENADOR"));

        // 1. ADMIN/CONTROLLER: Vê tudo
        if (ehAdminOuController) {
            return repository.findTop300ByOrderByDataSolicitacaoDesc();
        }
        // 2. GESTOR/COORDENADOR: Filtra pelo ID do Segmento no Banco
        else if (ehGestorSegmentado) {
            List<Long> segmentosDoUsuario = buscarSegmentosDoUsuario(userId);

            if (segmentosDoUsuario.isEmpty()) {
                return Collections.emptyList();
            }

            // QUERY OTIMIZADA: Não chama o monólito para cada linha
            return repository.findTop300BySegmentoIdInOrderByDataSolicitacaoDesc(segmentosDoUsuario);
        }
        // 3. SOLICITANTE COMUM: Vê apenas as suas
        else if (userId != null) {
            return repository.findBySolicitanteId(userId);
        }

        return Collections.emptyList();
    }

    // --- CORREÇÃO: LÓGICA DE FILTRO POR SEGMENTO COM LOGS ---

    private List<SolicitacaoAtividadeComplementar> filtrarPorSegmentoDoUsuario(List<SolicitacaoAtividadeComplementar> lista, Long userId) {
        if (userId == null || lista.isEmpty()) return lista;

        List<Long> segmentosDoUsuario = buscarSegmentosDoUsuario(userId);

        if (segmentosDoUsuario.isEmpty()) {
            log.warn("Usuário ID {} não possui segmentos vinculados ou a API do monólito falhou.", userId);
            return Collections.emptyList();
        }

        log.info("Usuário ID {} possui segmentos: {}", userId, segmentosDoUsuario);

        cacheSegmentoOs.clear();
        return lista.stream()
                .filter(solicitacao -> {
                    Long segmentoOsId = buscarSegmentoDaOs(solicitacao.getOsId());
                    boolean permitido = segmentoOsId != null && segmentosDoUsuario.contains(segmentoOsId);

                    if (!permitido) {
                        log.debug("Solicitação {} (OS {}) filtrada. Segmento OS: {}", solicitacao.getId(), solicitacao.getOsId(), segmentoOsId);
                    }

                    return permitido;
                })
                .collect(Collectors.toList());
    }

    private List<Long> buscarSegmentosDoUsuario(Long userId) {
        try {
            Map<String, Object> map = buscarNoMonolito("/usuarios/" + userId);
            if (map != null && map.containsKey("segmentos")) {
                Object listaObj = map.get("segmentos");
                if (listaObj instanceof List<?>) {
                    return ((List<?>) listaObj).stream()
                            .map(this::convertToLong)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.error("Erro ao buscar segmentos do usuário {}: {}", userId, e.getMessage());
        }
        return Collections.emptyList();
    }

    private Long buscarSegmentoDaOs(Long osId) {
        if (osId == null) return null;
        if (cacheSegmentoOs.containsKey(osId)) return cacheSegmentoOs.get(osId);

        try {
            Map<String, Object> map = buscarNoMonolito("/os/" + osId);
            if (map != null && map.get("segmento") instanceof Map) {
                Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
                Long segId = convertToLong(segMap.get("id"));
                cacheSegmentoOs.put(osId, segId);
                return segId;
            } else {
                log.warn("OS {} retornou sem objeto 'segmento' válido.", osId);
            }
        } catch (Exception e) {
            log.error("Erro ao buscar segmento da OS {}: {}", osId, e.getMessage());
        }
        return null;
    }

    // --- CORREÇÃO PRINCIPAL: INTEGRAÇÃO ROBUSTA COM LOGS ---

    private List<String> getUrlsMonolito() {
        return List.of(
                "http://inprout-monolito:8080",
                "http://inprout-monolito-homolog:8080",
                "http://localhost:8080",
                "http://host.docker.internal:8080"
        );
    }

    private Map<String, Object> buscarNoMonolito(String path) {
        String pathClean = path.startsWith("/") ? path : "/" + path;

        for (String baseUrl : getUrlsMonolito()) {
            String fullUrl = baseUrl + pathClean;
            try {
                // log.debug("Tentando conectar ao monólito: {}", fullUrl); // Descomente para debug intenso
                ResponseEntity<Map> response = restTemplate.exchange(
                        fullUrl, HttpMethod.GET, createHttpEntity(null), Map.class
                );
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return (Map<String, Object>) response.getBody();
                }
            } catch (Exception e) {
                log.warn("Falha ao conectar no Monólito em {}: {}", fullUrl, e.getMessage());
                // Não retorna null imediatamente, tenta a próxima URL
            }
        }
        log.error("FALHA CRÍTICA: Nenhuma URL do Monólito respondeu para o endpoint {}", path);
        return null;
    }

    private HttpEntity<Object> createHttpEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null) headers.set("Authorization", authHeader);
        }
        return new HttpEntity<>(body, headers);
    }

    @Transactional
    public SolicitacaoAtividadeComplementar criar(SolicitacaoDTO.Request dto) {
        // --- ALTERAÇÃO: Busca o segmento da OS antes de salvar ---
        Long segmentoId = buscarSegmentoDaOs(dto.osId());

        SolicitacaoAtividadeComplementar nova = SolicitacaoAtividadeComplementar.builder()
                .osId(dto.osId())
                .segmentoId(segmentoId)
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
        return repository.findById(id).orElseThrow(() -> new EntityNotFoundException("Não encontrado: " + id));
    }

    public List<SolicitacaoAtividadeComplementar> listarPorSolicitante(Long solicitanteId) {
        return repository.findBySolicitanteId(solicitanteId);
    }

    @Transactional
    public SolicitacaoAtividadeComplementar aprovarPeloCoordenador(Long id, SolicitacaoDTO.EdicaoCoordenadorDTO dto) {
        SolicitacaoAtividadeComplementar s = buscarPorId(id);
        if (s.getStatus() != StatusSolicitacaoComplementar.PENDENTE_COORDENADOR) throw new RuntimeException("Status inválido.");

        s.setLpuAprovadaId(dto.lpuId());
        s.setQuantidadeAprovada(dto.quantidade());
        s.setBoqAprovado(dto.boq());
        s.setStatusRegistroAprovado(dto.statusRegistro());
        s.setJustificativaCoordenador(dto.justificativa());
        if (dto.alteracoesItensExistentesJson() != null) s.setAlteracoesPropostasJson(dto.alteracoesItensExistentesJson());

        s.setAprovadorCoordenadorId(dto.aprovadorId());
        s.setDataAcaoCoordenador(LocalDateTime.now());
        s.setStatus(StatusSolicitacaoComplementar.PENDENTE_CONTROLLER);
        return repository.save(s);
    }

    @Transactional
    public SolicitacaoAtividadeComplementar aprovarPeloController(Long id, SolicitacaoDTO.EdicaoCoordenadorDTO dto) {
        SolicitacaoAtividadeComplementar s = buscarPorId(id);
        if (s.getStatus() != StatusSolicitacaoComplementar.PENDENTE_CONTROLLER) {
            throw new RuntimeException("Status inválido. Esperado PENDENTE_CONTROLLER.");
        }

        if (dto.lpuId() != null) s.setLpuAprovadaId(dto.lpuId());
        if (dto.quantidade() != null) s.setQuantidadeAprovada(dto.quantidade());
        if (dto.boq() != null) s.setBoqAprovado(dto.boq());
        if (dto.statusRegistro() != null) s.setStatusRegistroAprovado(dto.statusRegistro());

        if (dto.alteracoesItensExistentesJson() != null) {
            s.setAlteracoesPropostasJson(dto.alteracoesItensExistentesJson());
        }

        try {
            aplicarAlteracoesNoMonolito(s);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Erro ao integrar com o Monólito: " + e.getMessage());
        }

        s.setAprovadorControllerId(dto.aprovadorId());
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

    private void aplicarAlteracoesNoMonolito(SolicitacaoAtividadeComplementar s) throws Exception {
        Map<String, Object> testConn = buscarNoMonolito("/");
        // Pega a URL que funcionou ou a primeira da lista
        String baseUrl = getUrlsMonolito().get(0);

        if (s.getAlteracoesPropostasJson() != null && !s.getAlteracoesPropostasJson().isBlank()) {
            List<Map<String, Object>> alteracoes = objectMapper.readValue(s.getAlteracoesPropostasJson(), new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> alt : alteracoes) {
                Long itemId = convertToLong(alt.get("itemId"));
                if (alt.containsKey("novoStatus")) {
                    restTemplate.exchange(baseUrl + "/os/detalhe/" + itemId + "/status", HttpMethod.PATCH, createHttpEntity(Map.of("status", alt.get("novoStatus"))), Void.class);
                }
                if (alt.containsKey("novaQtd")) {
                    Map<String, Object> payload = Map.of("quantidade", alt.get("novaQtd"), "boq", alt.get("novoBoq") != null ? alt.get("novoBoq") : "", "lpu", Map.of("id", alt.get("novaLpuId")));
                    restTemplate.exchange(baseUrl + "/os/detalhe/" + itemId, HttpMethod.PUT, createHttpEntity(payload), Void.class);
                }
            }
        }
        Map<String, Object> novoItem = Map.of("os", Map.of("id", s.getOsId()), "lpu", Map.of("id", s.getLpuAprovadaId()), "quantidade", s.getQuantidadeAprovada(), "boq", s.getBoqAprovado() != null ? s.getBoqAprovado() : "", "statusRegistro", s.getStatusRegistroAprovado() != null ? s.getStatusRegistroAprovado() : "ATIVO");
        restTemplate.postForObject(baseUrl + "/os/detalhe", createHttpEntity(novoItem), Object.class);
    }

    private Long convertToLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch(Exception e) { return null; }
    }
}