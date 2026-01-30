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

        if (roleUpper.contains("ADMIN")) {
            return repository.findByStatusIn(List.of(
                    StatusSolicitacaoComplementar.PENDENTE_COORDENADOR,
                    StatusSolicitacaoComplementar.PENDENTE_CONTROLLER
            ));
        }
        else if (roleUpper.contains("CONTROLLER")) {
            return repository.findByStatus(StatusSolicitacaoComplementar.PENDENTE_CONTROLLER);
        }
        else if (roleUpper.contains("COORDINATOR") || roleUpper.contains("COORDENADOR")) {
            List<SolicitacaoAtividadeComplementar> todas = repository.findByStatus(StatusSolicitacaoComplementar.PENDENTE_COORDENADOR);
            return filtrarPorSegmentoDoUsuario(todas, userId);
        }

        return Collections.emptyList();
    }

    public List<SolicitacaoAtividadeComplementar> listarHistorico(String role, Long userId) {
        boolean ehAdminOuController = role != null && (role.toUpperCase().contains("ADMIN") || role.toUpperCase().contains("CONTROLLER"));
        boolean ehGestorSegmentado = role != null && (role.toUpperCase().contains("COORDINATOR") || role.toUpperCase().contains("MANAGER"));

        if (ehAdminOuController) {
            // CORREÇÃO: Limita aos últimos 300 para não travar
            return repository.findTop300ByOrderByDataSolicitacaoDesc();
        }
        else if (ehGestorSegmentado) {
            // CORREÇÃO: Busca os últimos 300 e DEPOIS filtra (muito mais rápido)
            List<SolicitacaoAtividadeComplementar> ultimos = repository.findTop300ByOrderByDataSolicitacaoDesc();
            return filtrarPorSegmentoDoUsuario(ultimos, userId);
        }
        else if (userId != null) {
            // Usuário comum vê apenas as suas (geralmente são poucas, ok manter assim)
            return repository.findBySolicitanteId(userId);
        }

        return Collections.emptyList();
    }

    // --- CORREÇÃO AQUI: LÓGICA DE FILTRO POR SEGMENTO ---

    private List<SolicitacaoAtividadeComplementar> filtrarPorSegmentoDoUsuario(List<SolicitacaoAtividadeComplementar> lista, Long userId) {
        if (userId == null || lista.isEmpty()) return lista;

        // 1. Busca a LISTA de IDs de segmentos do usuário (Correção de tipo)
        List<Long> segmentosDoUsuario = buscarSegmentosDoUsuario(userId);

        if (segmentosDoUsuario.isEmpty()) {
            // Se o coordenador não tem segmento vinculado, não vê nada
            return Collections.emptyList();
        }

        cacheSegmentoOs.clear();
        return lista.stream()
                .filter(solicitacao -> {
                    Long segmentoOsId = buscarSegmentoDaOs(solicitacao.getOsId());
                    // Verifica se o segmento da OS está na lista de segmentos permitidos do usuário
                    return segmentoOsId != null && segmentosDoUsuario.contains(segmentoOsId);
                })
                .collect(Collectors.toList());
    }

    /**
     * CORRIGIDO: Agora busca o campo 'segmentos' (Lista) e não 'segmento' (Map)
     */
    private List<Long> buscarSegmentosDoUsuario(Long userId) {
        try {
            Map<String, Object> map = buscarNoMonolito("/usuarios/" + userId);
            // O endpoint /usuarios/{id} retorna: { "id": 1, "nome": "...", "segmentos": [10, 20] }
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
            System.err.println("Erro ao buscar segmentos do usuário " + userId + ": " + e.getMessage());
        }
        return Collections.emptyList();
    }

    private Long buscarSegmentoDaOs(Long osId) {
        if (osId == null) return null;
        if (cacheSegmentoOs.containsKey(osId)) return cacheSegmentoOs.get(osId);

        try {
            Map<String, Object> map = buscarNoMonolito("/os/" + osId);
            // A OS continua retornando objeto simples: { "segmento": { "id": 1, "nome": "SP" } }
            if (map != null && map.get("segmento") instanceof Map) {
                Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
                Long segId = convertToLong(segMap.get("id"));
                cacheSegmentoOs.put(osId, segId);
                return segId;
            }
        } catch (Exception e) {
            System.err.println("Erro ao buscar segmento da OS " + osId + ": " + e.getMessage());
        }
        return null;
    }

    // --- Restante do código (INTEGRAÇÃO, CRIAÇÃO, APROVAÇÃO) permanece igual ---

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
            try {
                String fullUrl = baseUrl + pathClean;
                ResponseEntity<Map> response = restTemplate.exchange(
                        fullUrl, HttpMethod.GET, createHttpEntity(null), Map.class
                );
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return (Map<String, Object>) response.getBody();
                }
            } catch (Exception ignored) {}
        }
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

        // 1. Atualiza os dados com o que veio da tela do Controller (caso ele tenha editado algo)
        if (dto.lpuId() != null) s.setLpuAprovadaId(dto.lpuId());
        if (dto.quantidade() != null) s.setQuantidadeAprovada(dto.quantidade());
        if (dto.boq() != null) s.setBoqAprovado(dto.boq());
        if (dto.statusRegistro() != null) s.setStatusRegistroAprovado(dto.statusRegistro());
        // Se o controller mudar as alterações propostas nos itens existentes
        if (dto.alteracoesItensExistentesJson() != null) {
            s.setAlteracoesPropostasJson(dto.alteracoesItensExistentesJson());
        }

        // 2. Tenta aplicar no Monólito
        try {
            aplicarAlteracoesNoMonolito(s);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Erro ao integrar com o Monólito: " + e.getMessage());
        }

        // 3. Finaliza
        s.setAprovadorControllerId(dto.aprovadorId()); // Pega do DTO
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