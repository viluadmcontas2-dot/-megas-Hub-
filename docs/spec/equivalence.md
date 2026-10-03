# equivalence — o cérebro: Referência, Curva Própria, 30 pontos, índice, próxima ação

Meta: no GNV o motor se comporta como na gasolina. "Comportar-se igual" é mensurável: para o mesmo
MAP (carga) e rpm, o tempo de injeção de gasolina que a ECU calcula em GNV deve ser o mesmo que
calcula em gasolina. A ECU já faz isso grosso (18 bandas de MAP, AutoMatch nativo). O Hub observa
com mais densidade (células de 0.02 bar, ~40 células), herda a curva da ECU como ponto de partida e
refina os 30 pontos da Curva K — só propõe; o dono grava.

Oráculo: `tools/equivalence/oracle.py` (escrito do zero, P2). Kotlin (`com.omegas.hub.equivalence`)
tem paridade bit a bit com o oráculo em `fixtures/equivalence/*.json` (gerados pelo oráculo).

## 1. Entradas

| Entrada | Origem | Cadência |
|---|---|---|
| telemetria `{rpm, mapBar, petrolMs, gasMs, fuel, tMs}` | `48 01 49` | ~10 Hz |
| curva atual `{axisMs[30], k[30]}` | `29 4B`, `29 61` | 4 s |
| buffers nativos por banda (gasolina e gás: n, injMs, mapBar) | grupo aquisição | 1–2 s |
| época `{automatchCount, flag13}` | `09 74`, `48 0B` | 1 s |

## 2. Observação estável (amostra)

Uma **amostra** nasce de 3 quadros seguidos, mesmo combustível, dentro de 1200 ms, com
rpm máx−mín ≤ 150, MAP máx−mín ≤ 0.03 bar e gasolina ≥ 1.0 ms. Valor = média dos 3.
Quadros em TRANSIÇÃO, CUTOFF ou DESLIGADO não geram amostra. Em marcha lenta (rpm < 1000) a amostra
vale só para o painel, não para equivalência (tp < 3 ms também é descartado para o cálculo).

Retenção: por célula `(150 rpm × 0.02 bar)`, no máximo 30 amostras, as mais novas vencem.
Limite total: 20 000 gasolina, 10 000 gás. Troca de curva/mapa (gravação pelo Hub ou época nova) apaga
a pista de gás (as amostras de gasolina continuam válidas).

## 3. Referência (`reference`)

```
Reference = { id, frozenAtMs, fingerprint,            // fingerprint = sha256(axis + k + bandas)
              bands:[18]{mapBar, petrolMs, n, maturity}, source: "ECU_AUTOCAL",
              ownPetrol: cells[~40]{mapBar, petrolMs, n, dispersion} }
```

- Nasce de `REFERENCE_FREEZE`: copia os buffers nativos de **gasolina** (PETR_INJ_TBUF/MNFLD_PRESS_BUF)
  e a Curva Própria de gasolina do momento. Uma Referência por vez; a anterior vira `undo` até o fim
  da sessão.
- Enquanto não há Referência: índice "—", próxima ação = "Rode em gasolina até a ECU completar as
  zonas e toque em Congelar".
- `ecuDrift` = diferença (em %) entre a Referência e os buffers nativos atuais de gasolina, por banda.
  Se a mediana passa de 3 % → aviso "ECU mudou, congele de novo" (não congela sozinho).

## 4. Curva Própria (gasolina e gás)

Para cada combustível, uma função `T(map)` por células de 0.02 bar entre LIMIT_PRESSURE_MIN e MAX:

```
célula c: n_c amostras, média m_c (ms), dispersão d_c (desvio-padrão relativo)
prior p_c = interpolação linear da Referência (gasolina) ou da Referência × K atual (gás)
peso do prior w_c = 1 − min(n_c / BAND_MATURE_COUNT, 1) × (1 − min(d_c / 0.08, 1) × 0.5)
T_c = w_c · p_c + (1 − w_c) · m_c
```
BAND_MATURE_COUNT = 12. Depois um ajuste **isotônico** (T cresce com MAP) em `ln T`, com rejeição
robusta de outliers (|resíduo| > max(0.05, 3·MAD), até 3 passes). Célula sem amostra e sem prior = vazia.

## 5. Os 30 pontos (`points[30]`)

Para cada ponto i da curva (eixo t_i em ms de gasolina):

```
mapEquivalente_i : MAP onde a Curva Própria de gasolina dá t_i  (inversa de T_gas(map))
tGas_i          : tempo de gasolina que a ECU calcula em GNV nesse MAP = T_gnv(mapEquivalente_i)
kTarget_i       : kCurrent_i × t_i / tGas_i           (quanto K deveria ser para igualar)
mixture_i       : (kTarget_i / kCurrent_i) − 1        (+ = POBRE: falta gás; − = RICO)
tolerance_i     : max(0.04, 2 × dispersão_i)
usage_i         : amostras em GNV perto de t_i (±1 ponto), normalizado pelo total
```

Estados:

```
SEM_DADOS → APRENDENDO (n < 4) → MEDIDO (n ≥ 4) → EQUIVALENTE (|mixture| ≤ tolerance)
                                              → POBRE n% | RICO n%  (|mixture| > tolerance)
após gravação de K: EM_PROVA → CONFIRMADO (novo |mixture| ≤ tolerance com n ≥ 4)
                              → CONTESTADO (piorou > 0.04 e |mixture| > 0.05)
                              → INCONCLUSIVO (40 min sem n ≥ 4)
```
Rótulo "leve" quando |mixture| ≤ 8 % (âmbar); acima disso vermelho.

## 6. Proposta de K (só a Mixture propõe, só o dono grava)

Entrada: `ln(kTarget_i)` com pesos `g_i` (evidência: `min(n_i,6)/6` espalhada pelo kernel
`(0.25, 0.5, 0.25)`), prior `ln(kCurrent_i)` com peso `1·(1−g_i) + 0.05·g_i`.
Suavização de Whittaker em `ln K` sobre `u = ln t`, λ = 0.3, 6 iterações IRLS com Tukey (c = 4.685),
segundas diferenças normalizadas por `h²` médio. Restrições (projeção alternada, ≤ 50 passes):
passo entre vizinhos ≤ ln 1.15; elasticidade `|Δln K / Δln t|` ≤ 0.35; faixa `[0.60, 0xFFFF/16384]`;
abaixo de 3.5 ms nunca mais pobre que o atual (LOW_GUARD). Resultado em Q14 truncado.
Origem por ponto: MEDIDO (g ≥ 0.5) · MISTO · SUAVIZADO (|Δ| > 0.0025) · MANTIDO.
Métricas no recibo: maior passo, maior elasticidade, rugosidade, trocas de sinal (índices 2..22).

## 7. Índice (`index`)

```
value     = Σ(usage_i · [state_i ∈ {EQUIVALENTE, CONFIRMADO}]) / Σ(usage_i)   sobre pontos ≥ MEDIDO
coverage  = pontos ≥ MEDIDO / 30
provisional = coverage < 0.4
trend     = value agora − value há 15 min (quando ambos existem)
```
Sem Referência → `null`. Mostrado como "87 % equivalente · 21 de 30 pontos".

## 8. Próxima ação (`nextAction`)

Prioridade, a primeira que valer:
1. operação em curso → "Aguarde: gravando…"
2. sem Referência → "Congelar referência" (habilitado quando ≥ 10 bandas maduras em gasolina)
3. ponto CONTESTADO → "Desfazer a gravação do ponto N" (Undo)
4. POBRE/RICO ponderado por uso → "Gravar proposta (N pontos, maior ajuste +x %)"
5. EM_PROVA → "Rode em GNV entre a e b ms para confirmar"
6. buraco de dados (célula vazia com uso previsto) → "Rode em GNV entre a e b ms"
7. nada → "Equivalente. Nada a fazer."

## 9. Fases (05 Refino › Fases)

`BASE` (sem Referência) → `MAPEANDO` (coverage < 0.4) → `AJUSTANDO` (há POBRE/RICO) → `PROVANDO`
(há EM_PROVA) → `EQUIVALENTE` (index ≥ 0.9 e sem CONTESTADO). Tolerância de fase ±3 %
(6 % quando ≥ 50 % dos pontos dependem só do prior). Cada fase tem orçamento de tempo (15 min online,
40 min para INCONCLUSIVO).

## 10. Parada e quase-parada (StallWatch)

rpm < 300 por 800 ms → DESLIGADO (fecha amostra, não encerra sessão). rpm entre 300 e 600 → quase
parada: amostras descartadas. Religar em < 60 s = mesma sessão. Marcha lenta constante por > 5 s não
entra no índice.

## 11. Portões de CI (P2)

- Replay de `fixtures/autocal/real/automatch_2026-10-01_1301.json.gz`: o índice após o AutoMatch
  (época nova) é maior que antes.
- Validação cruzada em `ref_2026-10-01_1719`: a Curva Própria prevê a banda escondida com erro menor
  que a Referência interpolada.
- Paridade: `oracle.py` e Kotlin produzem o mesmo `kTarget`, `mixture`, estados, índice e proposta Q14
  para os mesmos quadros (fixtures gerados pelo oráculo).
