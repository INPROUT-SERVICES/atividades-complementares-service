package br.com.inproutservices.atividades_complementares_service.controllers;

import br.com.inproutservices.atividades_complementares_service.dtos.SolicitacaoDTO;
import br.com.inproutservices.atividades_complementares_service.entities.SolicitacaoAtividadeComplementar;
import br.com.inproutservices.atividades_complementares_service.services.SolicitacaoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/solicitacoes-complementares")
@CrossOrigin(origins = "*")
public class SolicitacaoController {

    private final SolicitacaoService service;

    public SolicitacaoController(SolicitacaoService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SolicitacaoDTO.Response> criar(@RequestBody SolicitacaoDTO.Request dto) {
        SolicitacaoAtividadeComplementar s = service.criar(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(new SolicitacaoDTO.Response(s));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SolicitacaoDTO.Response> buscarPorId(@PathVariable Long id) {
        return ResponseEntity.ok(new SolicitacaoDTO.Response(service.buscarPorId(id)));
    }

    @GetMapping("/pendentes")
    public ResponseEntity<List<SolicitacaoDTO.Response>> listarPendentes(@RequestParam(value = "role", required = false) String role) {
        // Passamos a role para o service filtrar a fila correta
        List<SolicitacaoAtividadeComplementar> lista = service.listarPendentes(role);
        return ResponseEntity.ok(lista.stream().map(SolicitacaoDTO.Response::new).toList());
    }

    @GetMapping("/usuario/{usuarioId}")
    public ResponseEntity<List<SolicitacaoDTO.Response>> listarHistoricoUsuario(@PathVariable Long usuarioId) {
        List<SolicitacaoAtividadeComplementar> lista = service.listarPorSolicitante(usuarioId);
        return ResponseEntity.ok(lista.stream().map(SolicitacaoDTO.Response::new).toList());
    }

    // --- AÇÕES DO COORDENADOR (Gera Proposta) ---

    @PostMapping("/{id}/coordenador/aprovar")
    public ResponseEntity<SolicitacaoDTO.Response> aprovarCoordenador(
            @PathVariable Long id,
            @RequestBody SolicitacaoDTO.EdicaoCoordenadorDTO dto) {
        SolicitacaoAtividadeComplementar s = service.aprovarPeloCoordenador(id, dto);
        return ResponseEntity.ok(new SolicitacaoDTO.Response(s));
    }

    @PostMapping("/{id}/coordenador/rejeitar")
    public ResponseEntity<SolicitacaoDTO.Response> rejeitarCoordenador(
            @PathVariable Long id,
            @RequestBody SolicitacaoDTO.AcaoDTO dto) {
        SolicitacaoAtividadeComplementar s = service.rejeitar(id, dto.aprovadorId(), dto.motivo(), "COORDINATOR");
        return ResponseEntity.ok(new SolicitacaoDTO.Response(s));
    }

    // --- AÇÕES DO CONTROLLER (Aplica Mudanças no Monólito) ---

    @PostMapping("/{id}/controller/aprovar")
    public ResponseEntity<SolicitacaoDTO.Response> aprovarController(
            @PathVariable Long id,
            @RequestBody SolicitacaoDTO.AcaoDTO dto) {
        SolicitacaoAtividadeComplementar s = service.aprovarPeloController(id, dto.aprovadorId());
        return ResponseEntity.ok(new SolicitacaoDTO.Response(s));
    }

    @PostMapping("/{id}/controller/devolver")
    public ResponseEntity<SolicitacaoDTO.Response> devolverController(
            @PathVariable Long id,
            @RequestBody SolicitacaoDTO.AcaoDTO dto) {
        SolicitacaoAtividadeComplementar s = service.rejeitar(id, dto.aprovadorId(), dto.motivo(), "CONTROLLER");
        return ResponseEntity.ok(new SolicitacaoDTO.Response(s));
    }
}