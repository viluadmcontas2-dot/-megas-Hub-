# ecu-link — transporte USB-serial e ciclo de vida da ligação

Fonte: evidência (log Portmon do ProgBase `fixtures/portmon/progbase-lognovo-transactions.jsonl.gz`,
sessões reais em `fixtures/autocal/real/`) e conhecimento da Platina @ `a125436` (só leitura).
Classe de prova do que está aqui: 1 (contrato) e 3 (replay real). Nada físico.

## 1. Hardware

| Item | Valor | Evidência |
|---|---|---|
| Conversor | Silicon Labs CP210x | Portmon abre `Silabser0`; Platina usa driver CP2102 |
| VID / PID | `0x10C4` (4292) / `0xEA60` (60000) | `device_filter.xml` da Platina; ProgBase fala com `Silabser0` |
| Serial | 9600 bps, 8 bits, sem paridade, 1 stop | Portmon: `SET_BAUD_RATE Rate: 9600`, `StopBits: 1 Parity: NONE WordLength: 8` |
| Controle de fluxo | nenhum; DTR e RTS **desligados** | Portmon: `CLR_RTS`, `CLR_DTR`, `Shake:0` |
| Buffers | 4096 in / 4096 out, purge TX+RX na abertura | Portmon `SET_QUEUE_SIZE`, `PURGE` |

O ProgBase tenta 38400 na porta errada (`Serial1`) e só obtém resposta em 9600 no `Silabser0`.
O Hub não varre baud: usa 9600 fixo.

## 2. Transação

Toda conversa é **uma pergunta, uma resposta**, estritamente sequencial. Nunca há dois pedidos em voo.

```
Hub  → ECU : pedido = corpo + checksum            (checksum = soma dos bytes do corpo mod 256)
ECU  → Hub : eco do pedido (todos os bytes)
             status (1 byte)
             LEN (1 byte)                          (0..192)
             payload (LEN bytes)
             checksum = soma(status, LEN, payload) mod 256
```

Verificado no corpus Lognovo: 36 016 transações com status `0x53`, 3 499 com `0xCA`; 2 sem eco e 3
vazias (cabo/porta ausente). Teste `tests/test_lognovo_envelope.py` reprova se a regra falhar.

| Status | Significado | Ação do Hub |
|---|---|---|
| `0x53` | ACK: pedido aceito | seguir |
| `0xCA` + payload `01 08` | erro recuperável | repetir uma vez |
| `0xCA` + payload `01 10` | erro definitivo (comando não suportado) | ✗ ECU, não repetir |
| outro / eco diferente / checksum errado | lixo na linha | ✗ TRANSPORTE, ressincronizar |

Exemplo real: `48 08 50` (telemetria secundária) responde sempre `CA 01 10` nesta ECU. O Hub **não
envia** `48 08 50`.

## 3. Temporização

| Parâmetro | Valor | Origem |
|---|---|---|
| Timeout de leitura por transação | 240 ms | Platina; Portmon `RI:100 RM:50 RC:200` |
| Timeout ao sondar sessão já aberta | 350 ms | Platina |
| Intervalo de telemetria `48 01 49` | contínuo, limitado pela resposta (~80–120 ms/quadro) | Lognovo: 20 451 quadros |
| Silêncio máximo antes de `SEM_CABO` | 1 800 ms | Platina |
| Falhas seguidas toleradas | 3 (reconexão leve) · 10 (reabrir porta) | Platina |
| Backoff de handshake | 250 ms × 2ⁿ, teto 5 s, desiste após 40 falhas | Platina |

## 4. Handshake

Sequência exata do ProgBase ao abrir (Lognovo seq 73–97):

1. `00 02 02` → `53 00` — init 1 (acorda a ECU)
2. `01 00 3A 3B` → `53 00` — init 2 (abre sessão)
3. `00 25 25` → identificação (payload com versão)
4. Depois: leituras de configuração (`09 … 00`, `29 … 00`) e telemetria.

Fechamento: `00 01 01` → `53 00`. O Hub envia o fechamento ao encerrar a sessão e antes de soltar a porta.

Se o Hub abrir a porta e a ECU já responder a `48 01 49` (sessão deixada aberta), pula o handshake.

## 5. Estados da ligação (`connection.state`)

```
SEM_CABO ──(USB anexado + permissão)──► CONECTANDO ──(handshake ok)──► CONECTADO
   ▲                                        │ falha ×40 → ✗ "Cabo sem resposta"
   └──────────(USB removido / 10 falhas)────┴────────────────────────────────┘
```

Frase humana por estado: "Sem cabo", "Ligando à ECU…", "ECU ligada". Em ✗: "ECU não responde.
Confira o cabo e a chave na posição ligada." Detalhes técnicos: último pedido, bytes, status.

## 6. Permissão USB (Android)

- Dispositivo anexado → Activity recebe `USB_DEVICE_ATTACHED` (filtro VID/PID) → pede permissão
  uma vez → abre a porta no serviço em primeiro plano (`connectedDevice`).
- Permissão negada → estado `SEM_CABO` com frase "Toque em Permitir para usar o cabo".
- A porta pertence ao serviço; a Activity nunca fala com a ECU.

## 7. Fila de trabalho

Uma fila, uma transação por vez, prioridade:

1. **Segurança** — desligar modo de inserção, fechar sessão.
2. **Mutação do dono** — escrita de curva/mapa/ação AutoCal (uma operação inteira, indivisível).
3. **Leitura** — telemetria, status nativo, leituras AutoCal.

Uma mutação ocupa a fila do primeiro ao último byte (foto → escritas → readback). Telemetria volta
depois. Nenhuma leitura "se mete" no meio de uma escrita: isso é o que torna o readback confiável.

## 8. O que o Hub não faz

- Não envia `48 08 50` (falha definitiva nesta ECU).
- Não varre baud rates.
- Não envia comandos que não estejam nestes specs. Qualquer byte novo exige evidência e decisão do dono.
