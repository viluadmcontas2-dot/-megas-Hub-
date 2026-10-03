# autocal — AutoCal nativo da ECU: campos, ações, observação

Fonte: ProgBase (`fixtures/progbase/progbase-autocal-*.json`, dump `platinum-progbase-dump-autocal-v1.json`),
Lognovo (sequência real de leituras do ProgBase), sessões reais (`fixtures/autocal/real/*.json.gz`).

## 1. Escalas

| Grandeza | Escala | Exemplo |
|---|---|---|
| Tempo de injeção (eixos e buffers) | bruto ÷ 512 ms | `0x0600` → 3.000 ms |
| MAP (limiares e buffers) | bruto ÷ 1024 bar (S16) | `0x0199` → 0.400 bar |
| Fator K (MUL_ACT) | Q14: bruto ÷ 16384 | `0x4000` → 1.000 |

## 2. Mapa de campos (endereço → leitura)

Leitura escalar `09 lo hi ck`, vetor `29 lo hi ck`, indexada `0A lo hi idx ck`.

| Nome | Endereço | Tipo | Leitura | Papel |
|---|---|---|---|---|
| AUTO_CAL_ENABLE | `0x014A` | U8 | `09 4A 01 54` | 1 = AutoCal ligado |
| PETR_INJ_TBP | `0x014B` | U16[30] ms | `29 4B 01 75` | eixo de 30 tempos da curva K |
| MNFLD_PRESS_THD | `0x014C` | S16[18] bar | `29 4C 01 76` | limiares das 18 bandas de MAP |
| NUM_BUF_UPD_PETR | `0x015B` | U8[18] | `29 5B 01 85` | contagem de amostras por banda (gasolina) |
| NUM_BUF_UPD_GAS | `0x015C` | U8[18] | `29 5C 01 86` | idem (gás) |
| PETR_INJ_TBUF_GAS_PREV | `0x015D` | U16[18] | `29 5D 01 87` | injeção gasolina antes do último AutoMatch |
| MNFLD_PRESS_BUF_GAS_PREV | `0x015E` | S16[18] | `29 5E 01 88` | MAP antes do último AutoMatch |
| PETR_INJ_TBUF_GAS | `0x015F` | U16[18] | `29 5F 01 89` | injeção gasolina em GNV (média por banda) |
| MNFLD_PRESS_BUF_GAS | `0x0160` | S16[18] | `29 60 01 8A` | MAP em GNV |
| MUL_ACT | `0x0161` | U16[30] Q14 | `29 61 01 8B` | fatores K atuais |
| PETR_INJ_TBUF | `0x0162` | U16[18] | `29 62 01 8C` | injeção em gasolina (média por banda) |
| MNFLD_PRESS_BUF | `0x0163` | S16[18] | `29 63 01 8D` | MAP em gasolina |
| VECT_AUTOCAL_EE | `0x0164` | U8[4] | `29 64 01 8E` | **não suportado nesta ECU**: Lognovo responde `CA 01 10`. Não ler. |
| AUTOCAL_U8 (idx 2 = MAX_AUTOMATCH) | `0x0165` | U8[3] | `0A 65 01 02 72` | máximo de AutoMatch (3 no ProgBase) |
| EN_CDN_T_THD | `0x0167`:1 | U8 | `0A 67 01 01 73` | |
| LIMIT_PRESSURE_MIN / MAX | `0x0169` / `0x016A` | S16 | `09 69 01 73` / `09 6A 01 74` | faixa de MAP válida |
| ACQUIRED_ZONES_PETROL | `0x016F` | U8[4] | `29 6F 01 99` | zona cheia = 1 (`01 01 01 01` no Lognovo) |
| ACQUIRED_ZONES_GAS | `0x0170` | U8[4] | `29 70 01 9A` | (`00 00 00 00` no Lognovo) |
| CALIBRATION_VAL_1 | `0x0172` | U8[10] | `29 72 01 9C` | [2] limiar normal gasolina · [5] baixo gás · [8] normal gás |
| MODULE_VERSION | `0x0173` | U8 | `0A 73 01 00 7E` → `04` | |
| NUM_AUTOMATCH_EXECUTED | `0x0174` | U8 ou U16 | `09 74 01 7E` → `03` | contador de AutoMatch |
| MAX_RPM_FOR_AUTOCAL | `0x017A` | U16 | `09 7A 01 84` | |
| (limiares) | `0x0183`–`0x0188` | — | `09 83 01 8D` … `09 88 01 92` | |
| DISABLE_ACQ_BAND | `0x018B` | U8 | `09 8B 01 95` | |
| PETR_MNFLD_PRESS_RV | `0x018D` | S16[30] | `29 8D 01 B7` | MAP por ponto da curva (gasolina) |
| GAS_MNFLD_PRESS_RV | `0x018E` | S16[30] | `29 8E 01 B8` | MAP por ponto da curva (gás) |

Zonas (4): bandas 0–5, 6–9, 10–13, 14–17. Maturidade de uma banda: contagem ≥ limiar "baixo"
(parcial) e ≥ "normal" (madura). Limiares vêm da própria ECU, em CALIBRATION_VAL_1: gasolina
baixo = [1], normal = [2]; gás baixo = [5], normal = [8] — nunca de constante no app.
Valores reais desta ECU (Lognovo `29 72 01 9C`): `01 03 03 01 03 03 01 03 03 01` → baixo 3, normal 3.
Padrões ProgBase (`progbase-autocal-resource-defaults-v1.json`): MAX_AUTOMATCH 3,
CALIBRATION_VAL_1 = `[8,4,3,20,1,6,8,3,3,7]` — a ECU real difere; vale a leitura, não o padrão.

Valores reais (Lognovo) úteis como fixture do P1:
- `29 4C 01 76` (18 limiares de MAP): `0.150, 0.250, 0.300, 0.350, 0.400, 0.450, 0.500, 0.550, 0.600,
  0.650, 0.700, 0.750, 0.800, 0.850, 0.900, 0.950, 1.000, 1.100` bar.
- `29 4B 01 75` (eixo de 30 tempos, ms): `0.5 … 10.0` em passos de 0.5 (20 valores), `11 … 18` em passos
  de 1 (8 valores), depois `20` e `22`. Fixado em `tests/test_lognovo_envelope.py`.
- `29 5B 01 85` (contagens gasolina): `0A ×14, 08, 04, 00, 00` — 14 bandas com 10 amostras.
- `09 74 01 7E` → `03` (três AutoMatch executados); `00 25 25` → ident `02`.

## 3. Ações `02 24 04 modo ck`

| modo | ck | Ação | Exposta no Hub? |
|---|---|---|---|
| `0x01` | `2B` | zera aquisição em gasolina | sim — `AUTOCAL_RESET_PETROL` |
| `0x02` | `2C` | zera aquisição em gás | sim — `AUTOCAL_RESET_GAS` |
| `0x04` | `2E` | zera tudo | **não** |
| `0x08` | `32` | dispara AutoMatch manual | **não** (removido do Hub) |

Ligar/desligar aquisição: `12 4A 01 01 5E` / `12 4A 01 00 5D` (observado 4× cada no Lognovo).
Intents: `AUTOCAL_RESUME` / `AUTOCAL_PAUSE`. `AUTOCAL_RELEARN` = reset gás + resume, em uma operação.

Apagar pontos (Platina, não visto no Lognovo): máscara U8[18] em `0x016D` (gasolina) ou `0x016E` (gás),
0 = apagar, 1 = manter, escrita com `37 …` ou `(30+n) …`; depois commit `01 24 05 2A`.
Fica fora do P1; entra só se o dono pedir.

Pós-ação: aguardar 1000 ms após reset (500 ms após apagar ponto) antes do readback testemunha:
reset gás → `NUM_BUF_UPD_GAS` todo zero e `ACQUIRED_ZONES_GAS` zero; reset gasolina → idem em `_PETR`;
liga/desliga → `AUTO_CAL_ENABLE`. Sem a testemunha, a ação é `FALHOU`, nunca "feita".

## 4. Observação (AutoCalMonitor)

Observar é automático e **não altera nada**. Cadência:

| Grupo | Comandos | Período | Coerência |
|---|---|---|---|
| sonda | `48 0B 53` | 1 s | — |
| aquisição | `29 5B`, `29 5C`, `29 5F`, `29 60`, `29 62`, `29 63` (+ `29 6F`, `29 70`) | 1–2 s | todos no mesmo ciclo ≤ 2500 ms |
| referência | `29 61`, `29 4B`, `29 4C`, `29 5D`, `29 5E`, `29 8D`, `29 8E`, `09 74` | 4 s | ≤ 2000 ms |

O ProgBase não segue ordem fixa (amostra: `5C 5D 5F 60 6F 62 63 5B 5D 5E 5F 5C 61 60 …`); `8D/8E/6F/70`
aparecem a cada ~2 ciclos. O Hub usa uma ordem própria e fixa (a da tabela acima), porque a ordem
dos pedidos de leitura não muda nada na ECU — só os bytes de cada pedido são sagrados.

- Após abrir sessão: 8 s de assentamento antes do primeiro grupo.
- **Época**: um snapshot só é válido se `NUM_AUTOMATCH_EXECUTED` e a flag 13 do status nativo forem
  iguais antes e depois do grupo. Se mudou → descarta e relê (a ECU acabou de fazer AutoMatch).
- Troca de época = AutoMatch nativo aconteceu: a Referência congelada **não** muda; o Hub avisa
  "A ECU refez o AutoMatch" e mostra a deriva.
- Telemetria continua intercalada (prioridade de leitura), nunca durante uma escrita.

## 5. Snapshot (`autocal` no StateStore)

```
autocal = {
  enabled, epoch:{automatchCount, flag13}, maturity:{petrol:[18], gas:[18]}  // SEM_DADOS|PARCIAL|MADURA
  bands:[18]{thresholdBar, petrol:{n, injMs, mapBar}, gas:{n, injMs, mapBar}, prev:{injMs, mapBar}}
  zones:{petrol:[4], gas:[4]}, curve:{axisMs[30], k[30], petrolMapBar[30], gasMapBar[30]}
  lastReadAtMs, coherent, partial
}
```
