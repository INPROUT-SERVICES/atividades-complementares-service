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
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${APP_MONOLITH_URL:http://inprout-monolito:8080}")
    private String monolithUrl;

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

        if (roleUpper.contains("ADMIN") || roleUpper.contains("CONTROLLER")) {
            if (roleUpper.contains("ADMIN")) {
                return repository.findByStatusIn(List.of(
                        StatusSolicitacaoComplementar.PENDENTE_COORDENADOR,
                        StatusSolicitacaoComplementar.PENDENTE_CONTROLLER,
                        StatusSolicitacaoComplementar.DEVOLVIDO_CONTROLLER
                ));
            }
            return repository.findByStatus(StatusSolicitacaoComplementar.PENDENTE_CONTROLLER);
        } else if (roleUpper.contains("COORDINATOR") || roleUpper.contains("COORDENADOR")) {
            List<Long> segmentosDoUsuario = buscarSegmentosDoUsuario(userId);

            if (segmentosDoUsuario.isEmpty()) {
                log.warn("Coordenador ID {} tentou listar pendências mas não possui segmentos.", userId);
                return Collections.emptyList();
            }

            // Busca abrangente (Pendente + Devolvido) sem filtro de segmento no SQL para permitir Self-Healing
            List<SolicitacaoAtividadeComplementar> todasPendentes = repository.findByStatusIn(List.of(
                    StatusSolicitacaoComplementar.PENDENTE_COORDENADOR,
                    StatusSolicitacaoComplementar.DEVOLVIDO_CONTROLLER
            ));

            return todasPendentes.stream()
                    .filter(solicitacao -> validarOuAtualizarSegmento(solicitacao, segmentosDoUsuario))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    private boolean validarOuAtualizarSegmento(SolicitacaoAtividadeComplementar solicitacao, List<Long> segmentosUsuario) {
        Long segmentoId = solicitacao.getSegmentoId();

        if (segmentoId != null) {
            return segmentosUsuario.contains(segmentoId);
        }

        log.info("Solicitação {} está com segmento NULL. Buscando no Monólito...", solicitacao.getId());
        Long segmentoRecuperado = buscarSegmentoDaOs(solicitacao.getOsId());

        if (segmentoRecuperado != null) {
            solicitacao.setSegmentoId(segmentoRecuperado);
            repository.save(solicitacao);
            return segmentosUsuario.contains(segmentoRecuperado);
        }

        return false;
    }

    public List<SolicitacaoAtividadeComplementar> listarHistorico(String role, Long userId) {
        boolean ehAdminOuController = role != null && (role.toUpperCase().contains("ADMIN") || role.toUpperCase().contains("CONTROLLER"));
        boolean ehGestorSegmentado = role != null && (role.toUpperCase().contains("COORDINATOR") || role.toUpperCase().contains("MANAGER") || role.toUpperCase().contains("COORDENADOR"));

        if (ehAdminOuController) {
            return repository.findTop300ByOrderByDataSolicitacaoDesc();
        } else if (ehGestorSegmentado) {
            List<Long> segmentosDoUsuario = buscarSegmentosDoUsuario(userId);
            if (segmentosDoUsuario.isEmpty()) return Collections.emptyList();
            return repository.findTop300BySegmentoIdInOrderByDataSolicitacaoDesc(segmentosDoUsuario);
        } else if (userId != null) {
            return repository.findBySolicitanteId(userId);
        }
        return Collections.emptyList();
    }

    public List<SolicitacaoAtividadeComplementar> listarPorSolicitante(Long solicitanteId) {
        return repository.findBySolicitanteId(solicitanteId);
    }

    // --- MÉTODOS DE AÇÃO ---

    @Transactional
    public SolicitacaoAtividadeComplementar criar(SolicitacaoDTO.Request dto) {
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

    @Transactional
    public SolicitacaoAtividadeComplementar aprovarPeloCoordenador(Long id, SolicitacaoDTO.EdicaoCoordenadorDTO dto) {
        SolicitacaoAtividadeComplementar s = buscarPorId(id);

        boolean statusValido = s.getStatus() == StatusSolicitacaoComplementar.PENDENTE_COORDENADOR ||
                s.getStatus() == StatusSolicitacaoComplementar.DEVOLVIDO_CONTROLLER;

        if (!statusValido) throw new RuntimeException("Status inválido para edição do coordenador.");

        s.setLpuAprovadaId(dto.lpuId());
        s.setQuantidadeAprovada(dto.quantidade());
        s.setBoqAprovado(dto.boq());
        s.setStatusRegistroAprovado(dto.statusRegistro());
        s.setJustificativaCoordenador(dto.justificativa());
        if (dto.alteracoesItensExistentesJson() != null) s.setAlteracoesPropostasJson(dto.alteracoesItensExistentesJson());

        s.setAprovadorCoordenadorId(dto.aprovadorId());
        s.setDataAcaoCoordenador(LocalDateTime.now());

        // Volta para o Controller após correção
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
        if (dto.alteracoesItensExistentesJson() != null) s.setAlteracoesPropostasJson(dto.alteracoesItensExistentesJson());

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
        }
        else if (roleOrigem != null && roleOrigem.toUpperCase().contains("CONTROLLER")) {
            s.setAprovadorControllerId(aprovadorId);
            s.setDataAcaoController(LocalDateTime.now());
            s.setStatus(StatusSolicitacaoComplementar.DEVOLVIDO_CONTROLLER); // Correção do Status
            s.setJustificativaController(motivo);
        } else {
            s.setStatus(StatusSolicitacaoComplementar.REJEITADO);
            s.setMotivoRecusa(motivo);
        }
        return repository.save(s);
    }

    // --- MÉTODOS AUXILIARES ---

    private void aplicarAlteracoesNoMonolito(SolicitacaoAtividadeComplementar s) throws Exception {
        String baseUrl = descobrirUrlBaseAtiva();

        // 1. Processa alterações (Buffer) - Itens existentes
        if (s.getAlteracoesPropostasJson() != null && !s.getAlteracoesPropostasJson().isBlank()) {
            List<Map<String, Object>> alteracoes = objectMapper.readValue(s.getAlteracoesPropostasJson(), new TypeReference<List<Map<String, Object>>>() {});

            if (alteracoes != null) {
                for (Map<String, Object> alt : alteracoes) {
                    Long itemId = convertToLong(alt.get("itemId"));

                    if (itemId == null) continue;

                    // Ação 1: Atualizar Status (PATCH)
                    if (alt.containsKey("novoStatus")) {
                        restTemplate.exchange(
                                baseUrl + "/os/detalhe/" + itemId + "/status",
                                HttpMethod.PATCH,
                                createHttpEntity(Map.of("status", alt.get("novoStatus"))),
                                Void.class
                        );
                    }

                    // Ação 2: Atualizar Quantidade/LPU (PUT)
                    if (alt.containsKey("novaQtd")) {
                        Map<String, Object> payload = new HashMap<>();
                        // Usa convertToLong para evitar ClassCastException (Integer -> Long)
                        payload.put("quantidade", convertToLong(alt.get("novaQtd")));
                        payload.put("boq", alt.get("novoBoq") != null ? alt.get("novoBoq").toString() : "");

                        Long novaLpuId = convertToLong(alt.get("novaLpuId"));
                        if (novaLpuId != null) {
                            // Envia ID como Inteiro para compatibilidade com intValue() no Monólito
                            payload.put("lpu", Map.of("id", novaLpuId.intValue()));
                        }

                        restTemplate.exchange(
                                baseUrl + "/os/detalhe/" + itemId,
                                HttpMethod.PUT,
                                createHttpEntity(payload),
                                Void.class
                        );
                    }
                }
            }
        }

        // 2. Cria o novo item (POST) - Payload Seguro
        Map<String, Object> novoItem = new HashMap<>();

        // Estrutura OS
        Map<String, Object> osRef = new HashMap<>();
        // PROTEÇÃO CONTRA NULOS NO OS ID
        if (s.getOsId() != null) {
            osRef.put("id", s.getOsId().intValue());
        }
        novoItem.put("os", osRef);

        // Estrutura LPU
        Long lpuIdFinal = s.getLpuAprovadaId() != null ? s.getLpuAprovadaId() : s.getLpuId();
        if (lpuIdFinal != null) {
            Map<String, Object> lpuRef = new HashMap<>();
            lpuRef.put("id", lpuIdFinal.intValue()); // Envia como int
            novoItem.put("lpu", lpuRef);
        } else {
            // Se não houver ID, o monólito pode reclamar, mas evitamos o NPE aqui
            novoItem.put("lpu", null);
        }

        // Dados simples
        Long qtd = s.getQuantidadeAprovada() != null ? s.getQuantidadeAprovada() : s.getQuantidade();
        novoItem.put("quantidade", qtd != null ? qtd.intValue() : 0);

        novoItem.put("boq", s.getBoqAprovado() != null ? s.getBoqAprovado() : "");
        novoItem.put("statusRegistro", s.getStatusRegistroAprovado() != null ? s.getStatusRegistroAprovado() : "ATIVO");

        // Log para debug
        log.info("Enviando POST para Monólito (OS {}): {}", s.getOsId(), objectMapper.writeValueAsString(novoItem));

        restTemplate.postForObject(baseUrl + "/os/detalhe", createHttpEntity(novoItem), Object.class);
    }

    public SolicitacaoAtividadeComplementar buscarPorId(Long id) {
        return repository.findById(id).orElseThrow(() -> new EntityNotFoundException("Não encontrado: " + id));
    }

    private String descobrirUrlBaseAtiva() {
        String baseUrl = monolithUrl.endsWith("/") ? monolithUrl.substring(0, monolithUrl.length() - 1) : monolithUrl;
        try {
            String healthUrl = baseUrl + "/api/public/status";
            ResponseEntity<Map> response = restTemplate.exchange(healthUrl, HttpMethod.GET, createHttpEntity(null), Map.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return baseUrl;
            }
        } catch (Exception e) {
            log.error("Erro ao conectar no health check do Monólito em {}: {}", baseUrl, e.getMessage());
        }
        throw new RuntimeException("Não foi possível conectar ao Monólito na URL: " + baseUrl);
    }

    // Este método foi mantido para evitar perder lógica, embora a busca principal use o novo método.
    private List<SolicitacaoAtividadeComplementar> filtrarPorSegmentoDoUsuario(List<SolicitacaoAtividadeComplementar> lista, Long userId) {
        if (userId == null || lista.isEmpty()) return lista;

        List<Long> segmentosDoUsuario = buscarSegmentosDoUsuario(userId);

        if (segmentosDoUsuario.isEmpty()) {
            return Collections.emptyList();
        }

        cacheSegmentoOs.clear();
        return lista.stream()
                .filter(solicitacao -> {
                    Long segmentoOsId = buscarSegmentoDaOs(solicitacao.getOsId());
                    return segmentoOsId != null && segmentosDoUsuario.contains(segmentoOsId);
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
            // Tenta buscar endpoint leve primeiro se existir
            Map<String, Object> map = buscarNoMonolito("/os/" + osId);
            if (map != null && map.get("segmento") instanceof Map) {
                Map<String, Object> segMap = (Map<String, Object>) map.get("segmento");
                Long segId = convertToLong(segMap.get("id"));
                if (segId != null) {
                    cacheSegmentoOs.put(osId, segId);
                    return segId;
                }
            }
        } catch (Exception e) {
            log.error("Erro ao buscar segmento da OS {}: {}", osId, e.getMessage());
        }
        return null;
    }

    private Map<String, Object> buscarNoMonolito(String path) {
        String pathClean = path.startsWith("/") ? path : "/" + path;
        String baseUrl = descobrirUrlBaseAtiva();
        String fullUrl = baseUrl + pathClean;

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    fullUrl, HttpMethod.GET, createHttpEntity(null), Map.class
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return (Map<String, Object>) response.getBody();
            }
        } catch (Exception e) {
            log.warn("Falha ao buscar dados no Monólito em {}: {}", fullUrl, e.getMessage());
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

    private Long convertToLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch(Exception e) { return null; }
    }
}