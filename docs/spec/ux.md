# ux — as 7 abas, estados, fluxos de um toque, direção visual

Viewport 1280×720 (multimídia fraca, Android 8+). WebView + HTML/CSS/JS puro. Sem framework, sem
bundler, sem fonte web, sem `backdrop-filter`, **zero timers** na UI: tudo redesenha por revisão
(`window.OmegasOnRevision(rev)` → `Omegas.snapshot(rev)`).

## 1. Tokens (`ui/tokens.css`, único lugar com cor)

| Token | RGB | Uso |
|---|---|---|
| `--bg` | 8 12 18 | fundo |
| `--surface` | 16 23 33 | cartões |
| `--surface-2` | 22 31 44 | cartão elevado / linha ativa |
| `--stroke` | 41 55 73 | bordas, grades |
| `--text` | 245 248 252 | texto |
| `--text-2` | 150 166 187 | texto secundário |
| `--accent` | 116 92 255 | **só** ação primária e ▲ AGORA |
| `--ok` | 56 211 159 | equivalente, confirmado, gravado |
| `--warn` | 247 185 85 | leve (≤ 8 %), CONTESTADO, EM_PROVA |
| `--danger` | 255 107 107 | grande (> 8 %), FALHOU, sem cabo |

Sem dados = contorno oco em `--stroke`. Normalidade é compacta: quando tudo vai bem a tela é
silenciosa; cor forte só quando há o que fazer. Toque ≥ 76 px (células do Mapa K ≥ 44 px); texto
crítico ≥ 24 px; tipografia do sistema (`system-ui`), pesos 400/600.

## 2. Moldura comum

```
┌ barra superior (56 px): ECU ligada · GNV · 2 350 rpm · 0.48 bar · 4.2 ms      [índice 87 %] ┐
│ conteúdo da aba                                                                              │
│                                                                                              │
├ próxima ação (76 px): frase + um botão (accent)                                              ┤
└ abas (76 px): 01 Agora · 02 Mapa K · 03 Curva K · 04 AutoCal · 05 Refino · 06 Sessões · 07 Ferramentas ┘
```
Overlay de operação (único): cobre o conteúdo, mostra o estágio `RECEBIDO → PREPARANDO → EXECUTANDO →
CONFERINDO → CONCLUÍDO | FALHOU` como trilho, frase humana, botão **Desfazer** quando `undoable`.
Fecha com um toque depois de CONCLUÍDO/FALHOU; o recibo vai para o histórico.
"Detalhes técnicos" é sempre a última seção de qualquer tela/overlay, recolhida por padrão.

## 3. As 7 abas

| Aba | Pergunta que responde | Conteúdo | Subpáginas |
|---|---|---|---|
| **01 Agora** | Como está o motor agora? | mostradores grandes (rpm, MAP, gasolina ms, gás ms, combustível, água, gás °C, pressão), ponto ativo da curva com estado, índice, próxima ação | — |
| **02 Mapa K** | Onde a ECU está a trabalhar no mapa? | grade 12×12 com eixos físicos, célula ativa, toque → ±1 → Gravar | — |
| **03 Curva K** | Minha curva está equivalente? | canvas: Referência tracejada, Própria sólida, 30 pontos por estado, ▲ AGORA | Equivalência · Editar · Backups |
| **04 AutoCal** | O que a ECU já aprendeu? | 18 bandas gasolina/gás com maturidade, zonas, época, botões Pausar/Retomar/Reaprender gás/Zerar gasolina, Congelar referência | Aquisição · Referência · Épocas |
| **05 Refino** | Em que fase estou e o que falta? | fase atual, orçamento, lista dos 30 pontos com estado/uso/mixture, proposta com "Gravar proposta" | Fases · Pontos |
| **06 Sessões** | O que mudou com o tempo? | evolução do índice por sessão, lista com exportar (um toque) | Evolução · Lista |
| **07 Ferramentas** | Ajustes e diagnóstico | retenção, overlay flutuante, bateria, permissão USB, autoteste, versão, SHA do APK | — |

Removido em relação à Platina: Sugestões, AutoMatch manual, "Nova aquisição completa",
exportar/importar aprendizado, LAN/rede, OBD, Bluetooth, Predictor.

## 4. Estados por tela (cada um tem teste `node --test`)

- **Sem cabo**: todas as abas mostram dados "—", frase "Sem cabo" na barra, próxima ação "Ligue o cabo".
- **Conectando**: barra em `--warn`, conteúdo mantém último valor com `ageMs` visível ("há 3 s").
- **Conectado sem Referência**: 03/05 mostram só a Curva atual e "Congelar referência" como próxima ação.
- **Operação em curso**: overlay; abas bloqueadas para novos intents (botões desabilitados, não escondidos).
- **FALHOU**: overlay em `--danger` com frase e "Detalhes técnicos" (pedido, resposta, esperado/lido) e botão Desfazer se houver foto.
- **Dado desconhecido nunca é 0**: mostra "—".

## 5. Fluxos de um toque

| Toque | Resultado |
|---|---|
| Gravar (03 Editar / 02 / 05) | `CURVE_WRITE` / `MAP_WRITE` → overlay → recibo; Desfazer disponível até o fim da sessão |
| Desfazer | `UNDO` → restaura foto byte a byte → readback |
| Zerar curva (03 Editar) | `CURVE_RESET` (foto antes) |
| Restaurar backup | `CURVE_RESTORE {backupId}` |
| Congelar referência (04) | `REFERENCE_FREEZE` — sem ECU envolvida; undo até fim da sessão |
| Pausar / Retomar / Reaprender gás / Zerar gasolina (04) | `AUTOCAL_PAUSE` / `_RESUME` / `_RELEARN` / `_RESET_PETROL` |
| Exportar (06) | `SESSION_EXPORT` → ZIP em Download/Omegas |
| Overlay flutuante (07) | `OVERLAY_TOGGLE` |

Nenhum botão pede confirmação, nenhum exige segurar. Proteção = foto antes + Desfazer depois.

## 6. Gráficos (`<canvas>`)

- Antialias ligado, `devicePixelRatio` respeitado, redesenho completo só por revisão; ▲ AGORA redesenha
  só a sua camada (canvas sobreposto).
- Eixo X em `ln t` (igual ao cérebro), rótulos em ms; eixo Y K de 0.6 a 1.4 (auto-expande).
- Cursor: toque num ponto mostra cartão com `t`, `kCurrent`, `kTarget`, `mixture`, estado, amostras.
- Metas (classe 4, no emulador): primeiro quadro < 300 ms, resposta ao toque < 100 ms.

## 7. Regras de exibição

Combustível: `GASOLINA · GNV · CUTOFF · TRANSIÇÃO · DESLIGADO`. Percentual com sinal e 0 casas
("+6 %"), ms com 2 casas, bar com 2 casas, rpm sem decimais e com espaço de milhar. Tempo relativo
("há 3 s") nunca absoluto na barra. Frase humana primeiro, sempre; bytes só em Detalhes técnicos.

## 8. Referência visual

Screenshots da Platina (run `37133472236` de `OMEGAS-V8.2`, artefatos `android-render-*`) não puderam ser
baixados nesta sessão (proxy bloqueia o armazenamento de artefatos). Ver `docs/spec/ux/README.md`.
A direção visual acima é a do spec Platina §3; o Hub a refaz do zero (clean-room).
