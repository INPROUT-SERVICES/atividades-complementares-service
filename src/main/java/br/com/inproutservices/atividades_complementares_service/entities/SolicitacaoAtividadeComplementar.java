package br.com.inproutservices.atividades_complementares_service.entities;

import br.com.inproutservices.atividades_complementares_service.enums.StatusSolicitacaoComplementar;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "solicitacao_atividade_complementar")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SolicitacaoAtividadeComplementar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- REFERÊNCIAS EXTERNAS (IDs) ---
    @Column(name = "os_id", nullable = false)
    private Long osId; // ID da OS no serviço de Atividades/OS

    @Column(name = "lpu_id", nullable = false)
    private Long lpuId; // ID da LPU original

    @Column(name = "solicitante_id", nullable = false)
    private Long solicitanteId; // ID do Usuário

    // --- DADOS SNAPSHOT (Opcional, mas recomendado para microsserviços) ---
    // Guardar o nome ou valor evita chamadas HTTP complexas apenas para listar
    private String solicitanteNomeSnapshot;
    private Double valorUnitarioSnapshot; // Valor da LPU no momento do cadastro

    // --- DADOS ORIGINAIS ---
    @Column(nullable = false)
    private Integer quantidade;

    @Column(columnDefinition = "TEXT")
    private String justificativa;

    // --- DADOS APROVADOS/EDITADOS PELO COORDENADOR ---
    @Column(name = "lpu_aprovada_id")
    private Long lpuAprovadaId;

    @Column(name = "quantidade_aprovada")
    private Integer quantidadeAprovada;

    @Column(name = "boq_aprovado")
    private String boqAprovado;

    @Column(name = "status_registro_aprovado")
    private String statusRegistroAprovado;

    // --- FLUXO DE APROVAÇÃO ---
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StatusSolicitacaoComplementar status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime dataSolicitacao;

    @Column(columnDefinition = "TEXT")
    private String justificativaCoordenador;

    @Column(name = "aprovador_coordenador_id")
    private Long aprovadorCoordenadorId;

    private LocalDateTime dataAcaoCoordenador;

    @Column(columnDefinition = "TEXT")
    private String justificativaController;

    @Column(name = "aprovador_controller_id")
    private Long aprovadorControllerId;

    private LocalDateTime dataAcaoController;

    @Column(columnDefinition = "TEXT")
    private String motivoRecusa;

    @PrePersist
    protected void onCreate() {
        this.dataSolicitacao = LocalDateTime.now();
        if (this.status == null) {
            this.status = StatusSolicitacaoComplementar.PENDENTE_COORDENADOR;
        }
    }
}