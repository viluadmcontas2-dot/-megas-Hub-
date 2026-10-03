# session — gravação de sessão `omegas-session-log-v1`

Toda sessão (da ligação do app ao fim) é gravada no disco, sem perda mesmo com SIGKILL.
Formato compatível com a Platina para que as ferramentas de replay existentes leiam os arquivos do Hub.

## 1. Pasta

```
filesDir/sessions/<sessionId>/
  manifest.json               // escrito no início, atualizado a cada segmento
  events_0000.jsonl           // segmentos de até 64 MB
  events_0001.jsonl
  session_summary.json        // schema omegas-session-semantic-v1, no fim (ou na recuperação)
  RESUMO.md                   // humano: o que aconteceu, 1 tela
  README_PARA_IA.txt          // como ler os arquivos
```
`sessionId = AAAA-MM-DD_HHMM_<4 hex>`. Exportação: um ZIP por sessão em
`Download/Omegas/<sessionId>/<sessionId>.zip` via MediaStore (Android 10+) ou caminho legado.

## 2. Evento (uma linha JSON)

```
{ "sequence": 1234,                 // inteiro, crescente, sem buracos
  "recordedAtMs": 1759512345678,     // epoch ms
  "recordedAtUtc": "2026-10-03T18:05:45.678Z",
  "type": "TELEMETRY",               // ver tabela
  "source": "ECU" | "HUB" | "DONO",
  "data": { … } }
```

| type | source | data | Flush |
|---|---|---|---|
| `SESSION_START` | HUB | `{appVersion, device, sdk, sessionId}` | fsync |
| `CONNECTION` | HUB | `{state, reason, bytes?}` | fsync |
| `TELEMETRY` | ECU | `{rpm, mapBar, petrolMs, gasMs, fuel, waterC, gasC, gasBar, levelRaw, raw}` | buffer 1 s |
| `AUTOCAL_SNAPSHOT` | ECU | `{epoch, fields:[{key, raw[]}], coherent}` | buffer |
| `CURVE_READ` / `MAP_READ` | ECU | `{raw}` | fsync |
| `INTENT` | DONO | `{intent, payload, receiptId}` | fsync |
| `OPERATION` | HUB | `Receipt` (cada mudança de estágio) | fsync |
| `ECU_TX` | HUB | `{request, response, status, ms}` só em operações de escrita | fsync |
| `REFERENCE` | HUB | `Reference` ao congelar | fsync |
| `EQUIVALENCE` | HUB | `{index, points[30] resumidos}` a cada 60 s ou mudança de estado | buffer |
| `ERROR` | HUB | `{kind: TRANSPORTE|ECU|APP, text, detail}` | fsync |
| `SESSION_END` | HUB | `{reason}` | fsync |

Tipos "fsync" são escritos e sincronizados antes de a operação prosseguir. Telemetria agrupa em 1 s.

## 3. manifest.json

```
{ "schema": "omegas-session-log-v1", "sessionId", "startedAtUtc", "app": {"id":"com.omegas.hub","version"},
  "segments": [{"file":"events_0000.jsonl","firstSequence","lastSequence","bytes"}],
  "status": "ABERTA" | "FECHADA" | "RECUPERADA" }
```

## 4. session_summary.json (`omegas-session-semantic-v1`)

`{ sessionId, durationMs, frames, fuelShare:{GASOLINA, GNV, …}, operations:[Receipt…], reference?,
indexStart, indexEnd, pointsChanged:[…], errors:[…], unproven:[…] }`.

## 5. RESUMO.md (uma tela, português)

Início/fim · tempo em GNV e gasolina · índice no início e no fim · o que o dono gravou (e se a ECU
confirmou) · o que falhou · próxima ação sugerida ao sair.

## 6. Recuperação

No próximo arranque, toda pasta com `status: ABERTA` é fechada: lê o último `sequence`, gera
`session_summary.json` e `RESUMO.md`, marca `RECUPERADA`. Mantém ≥ 20 sessões; apaga as mais antigas
só acima disso e só se o espaço livre < 200 MB.

## 7. Compatibilidade com os fixtures `omegas-autocal-replay-v1`

Os replays reais (`fixtures/autocal/real/*.json.gz`) têm `telemetry[]`, `snapshots[]` e `kFactorWrites[]`.
Um conversor em `tools/session/replay_from_log.py` (P2) transforma um log do Hub nesse formato para que
o mesmo teste de replay rode sobre sessões novas.
