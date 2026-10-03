# Origem das evidências

Dados copiados **como dados** (não código). Tudo o que está aqui foi medido na ECU real ou extraído do
ProgBase; nada foi escrito à mão. SHA-256 conferido por `tests/test_fixtures_integrity.py`.

Fonte **P** = repositório `viluadmcontas2-dot/OMEGAS-V8.2`, branch `OmegasPlatina`, SHA `a125436`.
Fonte **L** = log Portmon `PortmonLOGNOVO.LOG` enviado pelo dono em 2026-10-03
(ZIP SHA-256 `6879fa2a7931d22c207cd7fa47dffb59e1df0fe1de216e34e3f11e0c08cc1c17`; LOG `43a632724182c72cbd4f386ea0f7421e01d38242b48b919705671751e9eb8a64`),
convertido por `tools/portmon_parse.py` (parser próprio do Hub).

| Arquivo | Fonte | Conteúdo | SHA-256 |
|---|---|---|---|
| `portmon/progbase-lognovo-transactions.jsonl.gz` | L | 39 520 transações ProgBase ↔ ECU (pedido, resposta, status do driver), 568 pedidos distintos | `00c2426f3a91b1a775badc06826cc4454dcc6b26f0aeb83446e8432794b5ce01` |
| `autocal/real/ref_2026-10-01_1719.json.gz` | P `fixtures/autocal/real/` | sessão real: 2 356 quadros, 5 snapshots AutoCal, inclui reset de gás | `140a0ec4753eaed6e027abc8b8d91c76ffa700c521c5a481ef44d932af08e603` |
| `autocal/real/gnv_only_2026-09-30_0931.json.gz` | P idem | sessão real só em GNV: 2 218 quadros, 13 snapshots | `4b5b57190e6d307f1193cab7f678f8f9ba5934f29c738dda71442123e3fcb8d5` |
| `autocal/real/automatch_2026-10-01_1301.json.gz` | P idem | sessão real com AutoMatch nativo: 2 078 quadros, 12 snapshots, troca de época | `1293fc14e199126c74eaf4d400e6de485a176af8c7f090d9e9409fb7455aa65b` |
| `progbase/progbase-autocal-action-map-v1.json` | P `tests/fixtures/` | ações `02 24 04 modo` do ProgBase | `1192e03c63729f272173b1b99ad6ef279857f5f493234ed4fbd0ae8534bd6348` |
| `progbase/progbase-autocal-consumer-map-v1.json` | P idem | quem consome cada campo AutoCal | `e4bc301a47f2c0e41365f48298a8562f2f4faa90d6f19a17ec02c1818bda38f9` |
| `progbase/progbase-autocal-dump-contract-v1.json` | P idem | contrato do dump AutoCal (v1) | `55f0e27484c4b3875bd4c5845b764c819d0b39cb2c0c0683ac5fd6d1c0dc913c` |
| `progbase/progbase-autocal-dump-contract-v2.json` | P idem | contrato do dump AutoCal (v2) | `49e5c33ba1dcd6eab44ae2f80e0aff38a5a98b44b1de26c45987e8cf86316ee0` |
| `progbase/progbase-autocal-resource-defaults-v1.json` | P idem | padrões do ProgBase (MAX_AUTOMATCH, eixo, CALIBRATION_VAL_1, limiares) | `ba3d4c9c262d6a319a91636d52b6ba3df78c3004ef87db399f5409e6ba8a5c0d` |
| `progbase/progbase-autocal-scale-dfm-v1.json` | P idem | escalas (÷512 ms, ÷1024 bar, Q14) | `628a8fe00db7fab68d3cbb6ae56f2d042b6afd128466fb1c3cbb347b57445287` |
| `progbase/platinum-progbase-dump-autocal-v1.json` | P idem | dump AutoCal completo lido pelo ProgBase | `b3cde3bc2cbe5c1492b764bb0c0e98bf71ff3b86a8de6f0177207e260b34a638` |
| `progbase/progbase-autocal-grid-510df8.bin.b64` | P idem | recurso binário do ProgBase (grade AutoCal) | `2d5de6325d7ccea68761195db760c848a9d1f3867c51d4cd0154d271cd65ddc6` |
| `progbase/progbase-finish-text-51a390.bin.b64` | P idem | recurso binário do ProgBase (texto de fim) | `c093a41a83edca0c5b8e74bbf956a3857344c5485fc02ac71514eb4d9317da1d` |
| `progbase/progbase-tautocaldm-dfm.bin.b64` | P idem | formulário AutoCal do ProgBase (DFM) | `f895c97260618505bc0c125b2013443665c78c1c7d73c335de7b372c866db04f` |
| `progbase/mp48-k-map-physical-axes.lock.json` | P `config/` | eixos físicos do Mapa K (rpm × ms) com lock | `34e462cc89ab9c7843f56a117a8bd3c6135dc92bf8d3973d6abb44d1ca51d81d` |
| `equivalence/parity.json` | gerado | saída do oráculo (`tools/equivalence/oracle.py cases`) nas 3 sessões reais: pontos, índice, ação e proposta nos quadros 599/1199/1999/fim — o Kotlin deve reproduzir | `1e5fb9f9602186d673115c84f1ba7316c502528a322c902b4b8ad774071f112f` |

Não trazidos (decisão 2026-10-03, dono: "não precisa do portmon, só ProgBase"): os derivados Portmon da
Platina (`tests/fixtures/portmon-*.json`, `evidence/portmon/`). O corpus Lognovo acima os substitui com
a fonte bruta.
