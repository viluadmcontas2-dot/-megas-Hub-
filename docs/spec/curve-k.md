# curve-k — Curva K (30 pontos) · ler, fotografar, gravar, zerar, restaurar

Fonte: Lognovo (`29 61 01 8B`, `29 4B 01 75` lidos 439/440×), Platina (bytes de escrita e critério de
readback), ProgBase.

## 1. Dados

- Eixo: PETR_INJ_TBP `0x014B`, 30 tempos de injeção de gasolina, bruto ÷ 512 ms, crescente.
- Fatores: MUL_ACT `0x0161`, 30 fatores Q14 (`0x4000` = 1.000). Fator = quanto a ECU multiplica o
  tempo de gasolina para injetar gás naquele ponto.
- Leitura: `29 61 01 8B` → eco + `53` + `3C` (LEN 60) + 60 bytes (30 × U16 LE) + ck.
  Eixo: `29 4B 01 75` → LEN 60 idem.
- Resposta real Lognovo (`29 61 01 8B`): começa `B1 35 AE 35 A9 35 B6 33 …` → 0x35B1 = 0.839, 0x35AE = 0.839,
  0x35A9 = 0.838, 0x33B6 = 0.808 … (fatores entre ~0.80 e ~1.33 nesta ECU). Eixo real: 0.5 → 10.0 ms em passos de 0.5 ms,
  11 → 18 ms em passos de 1 ms, depois 20 e 22 ms (ver `autocal.md`).

## 2. Escrita de um ponto

`14 61 01 idx lo hi ck` — U16 indexado, little-endian, ck = soma mod 256.

Exemplos canônicos (todo teste do P1 fixa estes bytes):

| Operação | Bytes |
|---|---|
| ponto 9 ← 0x34DD (0.8260) | `14 61 01 09 DD 34 90` |
| ponto 0 ← 1.000 | `14 61 01 00 00 40 B6` |
| ponto 29 ← 1.000 | `14 61 01 1D 00 40 D3` |

Resposta esperada: eco + `53 00 53`. Qualquer outra → `FALHOU` (ECU) e parar o lote.

Conversão fator → bruto: `trunc(fator × 16384)` (truncar em direção a zero), limitado a
`[0x2666 (0.60), 0xFFFF]`. Fator abaixo de **0.60** nunca é enviado (MIN_SAFE_FACTOR).

## 3. Operações (uma por vez na fila)

Ciclo comum: `RECEBIDO → PREPARANDO (foto) → EXECUTANDO → CONFERINDO (readback) → CONCLUÍDO | FALHOU`.

| Intent | Preparando | Executando | Conferindo |
|---|---|---|---|
| `CURVE_WRITE {points:[{index, factor}]}` | lê `29 61` (foto = backup automático) | 1..30 escritas `14 61 01 …`, ACK em cada | relê `29 61`; cada índice gravado deve bater **byte a byte**; demais iguais à foto |
| `CURVE_RESET` | foto | 30 escritas de `0x4000` (índices 0..29, em ordem) | relê: 30 × `0x4000` |
| `CURVE_RESTORE {backupId}` | foto; **recusa** se eixo do backup ≠ eixo atual | escreve só os índices que diferem | relê: igual ao backup |
| `CURVE_BACKUP_SAVE {name}` | — | lê `29 61` e `29 4B`, grava backup `MANUAL-<nome>` | — |
| `UNDO` (após CURVE_*) | — | = `CURVE_RESTORE` da foto da última operação | idem |

Readback divergente = `FALHOU` com texto "A ECU não confirmou o ponto N (esperado X, lido Y)". A foto
continua disponível para Desfazer.

## 4. Backups

Formato `omegas-k-backup-v1`: `{id, createdAtMs, name, origin: FOTO|MANUAL, axisRaw[30], factorsRaw[30], note}`.
Automáticos (`FOTO-…`): manter 30 mais recentes. Manuais (`MANUAL-…`): nunca apagar sozinho.
Guardados em `filesDir/backups/curve/` como JSON; listados em **03 Curva K › Backups**.

## 5. O que o dono vê (03 Curva K)

Gráfico: eixo X = tempo de gasolina (ms), eixo Y = fator K. Referência congelada tracejada, Curva
Própria sólida, 30 pontos coloridos pelo estado de equivalência, ▲ AGORA no ponto ativo.
Subpáginas: **Equivalência** (leitura) · **Editar** (toque num ponto → ajusta ±1% · Gravar) · **Backups**.
