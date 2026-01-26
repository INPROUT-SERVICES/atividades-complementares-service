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
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SolicitacaoService {

    private final SolicitacaoAtividadeComplementarRepository repository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Cache simples para evitar chamar o monólito repetidamente para a mesma OS na mesma requisição
    // (Em um cenário real, usar um Cache Manager como Caffeine ou Redis)
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

        // 1. ADMIN ou CONTROLLER: Veem tudo de acordo com o status
        if (roleUpper.contains("ADMIN")) {
            return repository.findByStatusIn(List.of(
                    StatusSolicitacaoComplementar.PENDENTE_COORDENADOR,
                    StatusSolicitacaoComplementar.PENDENTE_CONTROLLER
            ));
        }
        else if (roleUpper.contains("CONTROLLER")) {
            return repository.findByStatus(StatusSolicitacaoComplementar.PENDENTE_CONTROLLER);
        }
        // 2. COORDENADOR: Vê pendentes + Filtro de Segmento
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
            // Vê tudo
            return repository.findAll();
        }
        else if (ehGestorSegmentado) {
            // Vê tudo, mas filtrado pelo segmento
            List<SolicitacaoAtividadeComplementar> todas = repository.findAll();
            return filtrarPorSegmentoDoUsuario(todas, userId);
        }
        else if (userId != null) {
            // Usuário comum vê apenas as suas
            return repository.findBySolicitanteId(userId);
        }

        return Collections.emptyList();
    }

    // --- LÓGICA DE FILTRO POR SEGMENTO ---

    private List<SolicitacaoAtividadeComplementar> filtrarPorSegmentoDoUsuario(List<SolicitacaoAtividadeComplementar> lista, Long userId) {
        if (userId == null || lista.isEmpty()) return lista;

        // 1. Descobre o segmento do usuário logado
        Long segmentoUsuarioId = buscarSegmentoDoUsuario(userId);
        if (segmentoUsuarioId == null) {
            // Se não tem segmento, por segurança, retorna vazio ou tudo?
            // Geralmente quem não tem segmento não deve ver nada segmentado.
            return Collections.emptyList();
        }

        // 2. Filtra a lista verificando o segmento de cada OS
        cacheSegmentoOs.clear(); // Limpa cache local
        return lista.stream()
                .filter(solicitacao -> {
                    Long segmentoOsId = buscarSegmentoDaOs(solicitacao.getOsId());
                    return segmentoUsuarioId.equals(segmentoOsId);
                })
                .collect(Collectors.toList());
    }

    private Long buscarSegmentoDoUsuario(Long userId) {
        try {
            Map<String, Object> map = buscarNoMonolito("/usuarios/" + userId);
            if (map != null && map.get("segmento") instanceof Map) {
                Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
                return convertToLong(segMap.get("id"));
            }
        } catch (Exception e) {
            System.err.println("Erro ao buscar segmento do usuário " + userId + ": " + e.getMessage());
        }
        return null;
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
            }
        } catch (Exception e) {
            System.err.println("Erro ao buscar segmento da OS " + osId + ": " + e.getMessage());
        }
        return null; // Segmento não encontrado ou OS sem segmento
    }

    // --- INTEGRAÇÃO ROBUSTA COM O MONÓLITO ---

    private List<String> getUrlsMonolito() {
        // Tenta várias URLs para garantir conexão em diferentes ambientes (Docker, Local, etc)
        return List.of(
                "http://inprout-monolito:8080",
                "http://inprout-monolito-homolog:8080",
                "http://localhost:8080",
                "http://host.docker.internal:8080"
        );
    }

    private Map<String, Object> buscarNoMonolito(String path) {
        String pathClean = path.startsWith("/") ? path : "/" + path;

        // Tenta cada URL configurada
        for (String baseUrl : getUrlsMonolito()) {
            try {
                String fullUrl = baseUrl + pathClean;
                // Usa exchange para passar o token de autenticação
                ResponseEntity<Map> response = restTemplate.exchange(
                        fullUrl,
                        HttpMethod.GET,
                        createHttpEntity(null),
                        Map.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return (Map<String, Object>) response.getBody();
                }
            } catch (Exception ignored) {
                // Tenta a próxima URL silenciosamente
            }
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
            if (authHeader != null) {
                headers.set("Authorization", authHeader);
            }
        }
        return new HttpEntity<>(body, headers);
    }

    // --- MÉTODOS DE CRIAÇÃO E APROVAÇÃO (MANTIDOS) ---

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
    public SolicitacaoAtividadeComplementar aprovarPeloController(Long id, Long aprovadorId) {
        SolicitacaoAtividadeComplementar s = buscarPorId(id);
        if (s.getStatus() != StatusSolicitacaoComplementar.PENDENTE_CONTROLLER) throw new RuntimeException("Status inválido.");

        try {
            aplicarAlteracoesNoMonolito(s);
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
            s.setStatus(StatusSolicitacaoComplementar.REJEITADO);
            s.setMotivoRecusa(motivo);
        }
        return repository.save(s);
    }

    private void aplicarAlteracoesNoMonolito(SolicitacaoAtividadeComplementar s) throws Exception {
        // Lógica de integração mantida, mas usando o método robusto para obter URL base se necessário
        // (Aqui mantive o uso direto do restTemplate.exchange pois já implementa a lógica complexa de retry/URLs no buscarNoMonolito,
        // mas para operações de escrita (POST/PUT/PATCH) é ideal usar a mesma lógica de descoberta de URL se o MONOLITO_URL fixo falhar.
        // Por simplificação, vou usar a primeira URL válida encontrada ou a padrão)

        String baseUrl = getUrlsMonolito().get(0); // Pega a primeira (docker service name)

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