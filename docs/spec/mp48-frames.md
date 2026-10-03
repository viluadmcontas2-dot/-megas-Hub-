# mp48-frames — quadros, comandos e decodificação

Fonte: corpus Lognovo (ProgBase ↔ ECU real), ProgBase (`fixtures/progbase/`), sessões reais.
Checksum de pedido = soma dos bytes do corpo mod 256; o último byte de cada comando abaixo já é o checksum.

## 1. Famílias de comando (primeiro byte)

| Byte | Família | Forma do pedido | Observado (Lognovo) |
|---|---|---|---|
| `00` | sessão | `00 op ck` | `00 02 02` init, `00 25 25` ident, `00 01 01` fecha, `00 14 14`, `00 13 13` |
| `01` | sessão / commit | `01 … ck` | `01 00 3A 3B` init 2, `01 04 54 59` |
| `02` | ação | `02 24 04 modo ck` | `02 24 04 04 2E` (reset total — **não exposto**) |
| `09` | leitura escalar | `09 addrLo addrHi ck` | `09 74 01 7E`, `09 7B 01 85`, `09 21 00 2A` |
| `0A` | leitura indexada | `0A addrLo addrHi idx ck` | `0A 73 01 00 7E`, `0A 1B 00 idx ck` |
| `12` | escrita U8 | `12 addrLo addrHi val ck` | `12 4A 01 01 5E` liga AutoCal, `12 4A 01 00 5D` desliga |
| `13` | escrita U8 indexada | `13 addrLo addrHi idx val ck` | (Platina: apagar pontos) |
| `14` | escrita U16/célula indexada | `14 addrLo addrHi idx lo hi ck` ou `14 54 00 lin col val ck` | 144× Mapa K, `14 3D 00 …`, `14 37 00 …` |
| `29` | leitura de vetor | `29 addrLo addrHi ck` | `29 61 01 8B` curva K, `29 5F 01 89` buffers… |
| `2A` | leitura de linha de tabela | `2A 54 00 lin ck` | 13 linhas do Mapa K |
| `35` | escrita de palavra de modo | `35 03 00 86 modo 51 10 ck` | `2C` on, `24` off, `3C`, `34` |
| `37` | escrita de vetor estendida | `37 addrLo len+2 addrHi payload ck` | (Platina) |
| `31`–`36` | escrita de vetor compacta (`0x30+n`, n ≤ 5) | `(30+n) addrLo addrHi bytes ck` | (Platina) |
| `48` | telemetria | `48 01 49` | 20 451×; `48 0B 53` status nativo 434× |

Endereços são little-endian: `09 74 01` lê `0x0174`.

## 2. Telemetria `48 01 49`

Resposta: eco (3) + `53` + `22` (LEN 34) + 34 bytes + checksum = 40 bytes.

| Offset | Tam. | Campo | Escala | Observação |
|---|---|---|---|---|
| 0 | u16 LE | rpm | ×1 | 0 = motor parado |
| 6 | u16 LE | injeção gás (bruto) | ×0.00256 ms | 0 quando em gasolina |
| 8 | u16 LE | injeção gasolina (bruto) | ×0.00256 ms | |
| 11 | u8 | combustível | enum | `0x80/0xA0` GASOLINA · `0x88/0xA8` TRANSIÇÃO · `0x90/0xB0` GNV · `0x00` DESLIGADO |
| 12 | u8 | água (bruto) | 109 − bruto °C | |
| 13 | u8 | nível do tanque (bruto) | — | mostrar como barra, não como número |
| 14 | u16 LE | pressão do gás | ÷800 bar | |
| 16 | u8 | temperatura do gás | bruto − 20 °C | |
| 17 | s16 LE | MAP | ÷1000 bar | |
| 19 | u8 | desconhecido | — | não mostrar |
| 24 | u16 LE | injeção gás 2 (diagnóstico) | ×0.00256 ms | |
| 28 | u16 LE | injeção gasolina 2 (diagnóstico) | ×0.00256 ms | |

Quadro de referência (ProgBase): rpm 875 · GNV · gasolina 4.800 ms · gás 11.195 ms · água 65 °C ·
gás 49 °C · nível 126 · pressão 2.251 bar · MAP 0.452 bar. O teste de decodificação do P1 fixa estes valores.

**Corte físico (CUTOFF)**: rpm ≥ 1200 ∧ gasolina < 0.70 ms ∧ gás bruto = 0 ∧ MAP < 0.35 bar.
Vale mais que o byte 11.

Exemplo Lognovo (seq 719): payload começa `43 03 95 1D 00 00 E2 0C 74 06 00 90 …` → rpm 0x0343 = 835,
gás 0x0CE2 ×0.00256 = 8.43 ms, gasolina 0x0674 ×0.00256 = 4.23 ms, byte 11 `0x90` = GNV.

## 3. Status nativo `48 0B 53`

Resposta: eco + `53` + `0E` (LEN 14) + 14 bytes. Byte 12 = flag nativa 13; byte 13 = contador de
AutoMatch executados (`0x03` no Lognovo: `… 01 03 65`).

## 4. Modo de inserção `35 03 00 86 modo 51 10 ck`

Palavra de modo em `0x0003` (lida por `29 03 00 2C` → `86 24 51 10`). Valores observados do ProgBase:

| modo | ck | Observado | Efeito visto na telemetria seguinte |
|---|---|---|---|
| `0x24` | `43` | 4× | combustível volta ao automático |
| `0x2C` | `4B` | 8× | ECU em gasolina (`0x80`) — modo de inserção K usado antes de gravar mapa |
| `0x3C` | `5B` | 6× | ECU em GNV (`0x90`) |
| `0x34` | `53` | 1× | ECU em GNV |

**Decisão:** o Hub usa só `2C` (liga) e `24` (desliga), como a Platina, dentro da gravação de mapa.
`3C`/`34` ficam registrados como evidência; expor "forçar combustível" muda o que a ECU recebe → só com o dono.

## 5. Comandos que o Hub recebe do ProgBase mas não usa

`00 14 14` (4693×, responde `02 00`), `29 1D 00 46` (5 bytes zero), `09 21 00 2A`, `48 08 50`
(erro `CA 01 10`). Sem significado conhecido; não enviados.
