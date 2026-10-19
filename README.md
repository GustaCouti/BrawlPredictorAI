# Brawl Coach AI

Aplicativo Android independente de assistência visual para Brawl Stars. O objetivo desta revisão é detectar linhas que parecem nomes de adversários em vermelho/rosa, acompanhar a posição estimada e mostrar previsões curtas. O app não controla o jogo, não automatiza toques e não altera o cliente do Brawl Stars.

## Recursos implementados no código

- Captura local de tela após consentimento explícito do Android, com serviço em primeiro plano e reajuste quando o conteúdo capturado muda de tamanho.
- OCR local com o modelo latino agrupado do ML Kit; usa linhas de texto completas para reduzir a fragmentação de nomes.
- Filtro conjunto de tom vermelho/rosa, forma e tamanho do texto e indício visual de barra de vida próxima; candidatos pouco claros precisam de confirmação temporal.
- Rastreamento com IDs internos, associação por posição prevista e semelhança do nome, suavização de velocidade, retenção curta durante oclusões e expiração de rastros antigos.
- Rastreamento visual limitado a 48 rastros internos, até 48 regiões candidatas por quadro e até 12 previsões visíveis; regiões de movimento não equivalem necessariamente a inimigos identificados.
- Marcadores vermelhos de baixa opacidade, dimensionados a partir da altura do nome. O tamanho não representa a hitbox real do jogo.
- Previsão curta de movimento e redução individual do avanço previsto quando o rastreador observa reversões frequentes de direção.
- Armazenamento local de experiências visuais, erros de rastreamento e mudanças de direção; ajuste gradual do horizonte de previsão. O armazenamento não guarda clipes nem nomes persistentes como perfil de jogador.
- Laboratório offline determinístico que varia velocidade, estilos de movimento, quantidade de alvos, obstáculos, oclusões, ruído de OCR e proporção de tela. Relatórios são explicitamente identificados como sintéticos.
- Testes do núcleo de rastreamento, validação do filtro de nomes, cenários sintéticos e testes Android/Robolectric para a lógica de persistência.
- Workflow de integração contínua para executar testes Android e montar APK em um executor com Android SDK e acesso às dependências.

## Resultados medidos no ambiente disponível (19/10/2026)

Os números abaixo são de simulação sintética executada com Kotlin/JVM; não são resultados de partidas reais nem medição do OCR Android.

- Movimento variado padrão: 5 sementes independentes, 50.000 cenários, 1.400.000 frames; recall médio de associação ≈ 97,20%, erro médio de posição ≈ 0,00942 em coordenadas normalizadas e taxa média de troca de identidade ≈ 0,00932.
- Melhor horizonte de previsão entre os testados: 90 ms. O erro médio sintético aumentou de ≈ 0,0128 em 90 ms para ≈ 0,0334 em 260 ms, reforçando que previsões mais longas ficam menos confiáveis.
- Movimento extremo/caótico: 3 sementes, 30.000 cenários, 840.000 frames; recall médio ≈ 74,46%, taxa de troca de identidade ≈ 34,67% e erro médio de posição ≈ 0,0179. **Os limites de robustez não foram atingidos nesse teste.** O rastreador não prevê movimentos genuinamente aleatórios com confiabilidade; esse resultado é uma falha identificada, não um sucesso disfarçado.

## O que ainda não é possível afirmar

- A detecção por OCR ainda não foi validada em uma coleção representativa de capturas reais do Brawl Stars. Nomes pequenos, efeitos visuais, idiomas, cores diferentes e texto da interface podem gerar falsos positivos ou negativos.
- Não há medição da hitbox real nem confirmação oficial da posição do personagem; o centro do marcador é estimado a partir do nome e da tela.
- Regiões em movimento ainda não são classificadas de forma confiável como personagem, projétil ou efeito. Por isso, o app não pode afirmar que detecta todos os ataques recebidos, conhece a mira do adversário ou prevê com certeza esquivas.
- O aprendizado online ajusta o horizonte por erros de rastreamento e reversões de direção; ainda não aprende uma estratégia de combate a partir de acertos/derrotas nem escolhe movimentos automaticamente.
- Simulações não são partidas reais nem agentes humanos. Métricas sintéticas não garantem a mesma precisão, latência ou consumo de bateria em um dispositivo Android.
- A compilação Android completa e o teste no dispositivo ainda precisam ser executados em ambiente com Android SDK/Gradle. Este ambiente de desenvolvimento não tem esses componentes instalados e não consegue resolver hosts externos para baixá-los; portanto, não existe evidência local de APK compilado ou de funcionamento real em celular.

## Testes de núcleo que podem ser executados sem Android SDK

Com Kotlin/JVM instalado:

```bash
kotlinc app/src/main/java/com/brawlpredictor/ai/core/*.kt tools/CoreSimulationMain.kt -include-runtime -d /tmp/brawl-core-sim.jar
java -jar /tmp/brawl-core-sim.jar

kotlinc app/src/main/java/com/brawlpredictor/ai/core/*.kt tools/CoreStressMain.kt -include-runtime -d /tmp/brawl-core-stress.jar
java -jar /tmp/brawl-core-stress.jar

kotlinc app/src/main/java/com/brawlpredictor/ai/core/*.kt tools/ExtremeMovementMain.kt -include-runtime -d /tmp/brawl-extreme.jar
java -jar /tmp/brawl-extreme.jar
```

Os testes de `MotionLearningStore` dependem do framework Android/Robolectric e devem ser executados por `gradle testDebugUnitTest` em um executor com Android SDK e resolução de dependências.

## Orientação geral para futura validação em aparelho

1. Instalar um APK gerado pela integração contínua e abrir o app.
2. Autorizar **Exibir sobre outros apps** e conceder o consentimento de captura.
3. Testar primeiro gravações que cubram interfaces diferentes e depois partidas, anotando detecções corretas, falsos marcadores, perda de alvos, atraso e impacto no FPS.
4. Encerrar captura e sobreposição pelo botão **PARAR TUDO**.

Consulte [`docs/RESEARCH_NOTES.md`](docs/RESEARCH_NOTES.md) para as fontes oficiais e para entender quais inferências vêm dos pixels e quais dados o jogo não expõe ao aplicativo.

## Motor avançado de previsão (adição 0.4-dev)

Foi acrescentado `core/AdvancedPredictionEngine.kt` (e sua cópia no source set Android), ligado ao fluxo de alvos OCR. O overlay continua somente visual e agora pode desenhar alternativas âmbar, porcentagens heurísticas e círculos de incerteza. A porcentagem exibida ainda **não é uma probabilidade calibrada em partidas reais**.

### Componentes implementados como algoritmos leves / baselines

- Comitê de modelos, pesos adaptados por erro recente, árbitro/consenso e previsões multimodais (até cinco alternativas); abstenção quando os modelos discordam ou a confiança cai.
- Filtro de partículas simples, baseline de sequência com ponderação temporal, modelos de velocidade constante, curva coordenada e aceleração tipo Singer; filtro de Kalman linear e banco IMM leve com probabilidades de modo.
- Calibração conformal empírica de resíduos, compensação de latência medida, covariância adaptativa, HMM de manobras, suavização retroativa RTS simplificada e perfil de movimento por alvo.
- Fusão probabilística de evidências OCR/cor/movimento/barra de vida, gating, associação global por DP exata para conjuntos pequenos (fallback guloso para conjuntos grandes), pesos JPDA aproximados, evidência acumulada antes da confirmação e estimativa PHD simples.
- Reachability, interceptação de alvo em movimento, avaliação de ameaça, ajuste por interação entre agentes, Monte Carlo de destinos, A*, flow field, campo de potencial, seleção por utility e atualização de imitação.
- Hooks de evento para disparo/projétil/proximidade/dano, assinatura visual para reidentificação e retenção de histórico por ID.

### O que NÃO deve ser confundido com implementação completa

- O banco IMM, JPDA, PHD, filtro de partículas, Kalman, calibração conformal e associação são implementações didáticas/compactas, não substitutos validados de bibliotecas de rastreamento aeronáutico ou militar.
- A rotina de sequência é um baseline temporal leve, **não** uma GRU/Transformer treinada. Não foram incluídos modelos neurais profundos pré-treinados, Trajectron++, Social-LSTM, difusão de trajetórias, MC Dropout/ensemble profundo, nem OC-SORT/ByteTrack completos.
- A associação atual não implementa um MHT completo; o PHD não é CPHD; o Kalman disponibilizado é linear e não um UKF/EKF não linear completo. A grade A* usa uma máscara de obstáculos fornecida pelo chamador: não existe acesso automático à NavMesh/mapa interno do Brawl Stars.
- As reações a eventos dependem de eventos detectados/fornecidos; o pipeline atual não identifica de forma confiável todos os tiros ou projéteis em capturas. A inferência de objetivo é uma heurística opcional, não telemetria do modo de jogo.
- Domain randomization e cenários sintéticos já existem no laboratório offline, mas self-play multiagente completo e ajuste sim-to-real com conjunto rotulado de partidas ainda não estão implementados/validados.
- O intervalo conformal só tende a representar a cobertura pedida depois de acumular dados representativos e independentes. Antes disso usa um raio inicial conservador; não se deve interpretar “80%” como garantia já demonstrada.

### Verificações executadas neste ambiente

- `AdvancedEngineSmokeMain.kt`: passou em Kotlin/JVM, incluindo previsões multimodais, soma das probabilidades, reachability, interceptação, associação, A*, HMM, fusão de evidências, Monte Carlo, RTS e filtros Kalman/IMM.
- `CoreSimulationMain.kt`: passou em 10.000 cenários sintéticos / 280.000 frames: recall 0,971913; erro médio normalizado 0,009424; troca de identidade 0,009313; melhor horizonte testado 90 ms.
- `CoreStressMain.kt`: passou em 5 sementes / 50.000 cenários / 1.400.000 frames. Recall médio 0,97197, erro médio 0,00942 e troca de identidade média 0,00932.
- `ExtremeMovementMain.kt`: **não atingiu os limites de robustez** em 30.000 cenários caóticos / 840.000 frames: recall médio 0,74464 e troca de identidade 0,34671. Movimento imprevisível continua sendo uma limitação real.
- O ambiente não tem Gradle/Android SDK configurados. Portanto, **não foi possível compilar o APK, executar testes Android/Robolectric nem validar a sobreposição num telefone**. O teste Kotlin/JVM do motor não prova que o app Android inteiro compile ou funcione sem regressões.

Para repetir o smoke test avançado com Kotlin instalado:

```bash
kotlinc core/*.kt tools/AdvancedEngineSmokeMain.kt -include-runtime -d /tmp/brawl-advanced-smoke.jar
java -jar /tmp/brawl-advanced-smoke.jar
```

## Advanced research systems add-on (0.5-dev)

`core/AdvancedResearchSystems.kt` and its Android source-set copy add:

- **Exact O(n³) Hungarian assignment** for rectangular global matching (replaces the old small-matrix DP/greedy fallback when this utility is used).
- **MHT-style track hypotheses** with gating, missed-detection retention, velocity updates, track history and an ambiguity indicator. This is a compact hypothesis tracker, not a full production MHT implementation with exhaustive global hypothesis trees.
- **Tiny GRU recurrent model** with reset/update/candidate gates, sequence encoding and an online-trained output head. It is a small trainable baseline; it is not a pretrained Transformer, nor a full backpropagation-through-time training pipeline.
- **Empirical conformal calibration** using stored prediction residuals and finite-sample quantiles. Coverage only has a statistical interpretation when calibration data are representative and exchangeability assumptions are reasonable; it does not guarantee 80% coverage on shifted or unseen matches.
- **Domain-randomized synthetic motion rollouts** and a repeatable synthetic evaluation harness. These generate test data only; they are not real human demonstrations and do not themselves constitute strategic self-play.

### Re-run the additional JVM checks

```bash
kotlinc core/*.kt tools/AdvancedEngineSmokeMain.kt -include-runtime -d /tmp/advanced-smoke.jar
java -jar /tmp/advanced-smoke.jar

kotlinc core/*.kt tools/AdvancedResearchSystemsSmokeMain.kt -include-runtime -d /tmp/research-smoke.jar
java -jar /tmp/research-smoke.jar
```

The full project still requires Android SDK/Gradle and an Android device for APK/build/UI validation. Heavy architectures named in the research list (full Trajectron++, Social-LSTM, diffusion trajectory networks, OC-SORT/ByteTrack integrations, deep MC-dropout ensembles, a complete CPHD filter and a full nonlinear UKF) are **not** represented as completed merely by these lightweight components. The app must not display uncalibrated heuristic scores as guaranteed probabilities.


Consulte `docs/IMPLEMENTATION_STATUS.md` para distinguir sistemas leves já presentes de implementações de pesquisa ainda incompletas e para verificar as limitações de validação.

### Expanded research components
This revision adds lightweight UKF-style estimation, a tiny causal self-attention model, ensemble uncertainty sampling, diffusion-inspired trajectory denoising, PHD/CPHD-inspired target-count utilities, and adaptive frame-budget sizing. These are experimental approximations; neural components are not pretrained, and synthetic validation is not proof of real-match accuracy. See `docs/IMPLEMENTATION_STATUS.md` for exact limitations and validation status.

### 0.8 hardening
Forecast input is sanitized (finite coordinates, duplicate timestamps removed), invalid candidate weights are filtered, and per-track model state can be pruned through `forgetTrack` / `retainTracks`. The new variable-target smoke test exercises eight simultaneous target histories and stale-state cleanup. These checks validate software behavior on synthetic inputs; they do not establish enemy-classification accuracy in real matches.
