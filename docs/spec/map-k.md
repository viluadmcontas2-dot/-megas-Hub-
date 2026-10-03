# map-k — Mapa K 12×12 · ler, gravar por lote, conferir

Fonte: Lognovo (3 leituras completas das 13 linhas `2A 54 00 lin`; 144 escritas `14 54 00 lin col val`
com modo de inserção em volta), Platina (fluxo), eixos físicos `fixtures/progbase/mp48-k-map-physical-axes.lock.json`.

## 1. Dados

- Tabela em `0x0054`. 13 linhas de protocolo (`0x00`–`0x0C`): 12 editáveis + linha técnica `0x0C`
  (lida, nunca gravada). 12 colunas. Célula = U8, K em %, 100 = neutro.
- Leitura de linha: `2A 54 00 lin ck` → eco + `53` + `0C` (LEN 12) + 12 bytes + ck.
  Lognovo: `2A 54 00 00 7E` → `… 53 0C A2 A2 A2 A2 A2 A2 A2 A2 A2 A2 A2 A2 F7` (linha 0 toda 162).
- Eixos físicos (lock SHA-256 `0cc72731…`, arquivo em fixtures):
  RPM `[850,1350,1850,2500,3000,3500,4000,4500,5000,5500,6000,6500]`;
  injeção gasolina ms `[2.0,2.5,3.0,3.5,4.5,6.0,8.0,10.0,12.0,14.0,16.0,18.0]`.
  Linha = faixa de injeção, coluna = faixa de rpm (confirmar orientação no replay do P1 contra o lock).

## 2. Escrita de célula

`14 54 00 lin col val ck`. Exemplo Lognovo seq 1056: `14 54 00 00 00 64 CC` → `… 53 00 53`
(linha 0, coluna 0 ← 100). O ProgBase grava a tabela inteira em ordem linha→coluna.

Valor mínimo enviado: **100** (MIN_SAFE_K). O Hub nunca grava célula < 100.

## 3. Fluxo `MAP_WRITE {cells:[{row, col, value}]}` (1 a 16 células por operação)

1. **PREPARANDO** — se o mapa em cache não é desta sessão, relê 13 linhas; foto = linhas tocadas.
2. **EXECUTANDO** —
   1. `35 03 00 86 2C 51 10 4B` (modo de inserção ligado) → espera `53 00`.
   2. para cada célula: `14 54 00 lin col val ck` → espera `53 00 53`; falha → aborta, vai ao passo 3.
   3. `35 03 00 86 24 51 10 43` (modo desligado) → **sempre** enviado, mesmo após falha (prioridade segurança).
3. **CONFERINDO** — relê cada linha tocada (`2A 54 00 lin`); célula gravada deve bater byte a byte;
   as outras 11 iguais à foto.
4. `CONCLUÍDO` com recibo (células, bytes, readback) ou `FALHOU` (qual célula, esperado/lido).

Sequência real (Lognovo seq 721 → 1056…1199 → 1334): `35 …2C…` · 144 × `14 54 …` · `35 …24…`.

`UNDO` = `MAP_WRITE` com os valores da foto, mesmo fluxo.

## 4. O que o dono vê (02 Mapa K)

Grade 12×12 com cor por valor (100 = neutro), eixos com rótulos físicos, célula ativa (rpm/ms da
telemetria) destacada, toque ≥ 44 px por célula. Toque em célula → ±1 · Gravar (um toque) · Desfazer.
Linha técnica `0x0C` só em "Detalhes técnicos".
