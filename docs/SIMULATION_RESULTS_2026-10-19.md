# BrawlCoachAI — relatório de simulação

Data de execução: 2026-10-19. Ambiente: Kotlin/JVM, sem Android SDK; portanto estes testes validam apenas o núcleo puro Kotlin.

## Movimento variado padrão

Cinco sementes independentes, 10.000 cenários por semente: **50.000 cenários, 1.400.000 frames**.

| Métrica | Resultado médio aproximado |
|---|---:|
| Associação de observações visíveis (recall sintético) | 97,20% |
| Erro médio de posição (coordenadas normalizadas) | 0,00942 |
| Taxa de troca de identidade | 0,932% |
| Horizonte com menor erro entre os testados | 90 ms |

Erro médio da previsão por horizonte, na primeira semente:

| Horizonte | Erro médio |
|---|---:|
| 90 ms | 0,01278 |
| 120 ms | 0,01580 |
| 150 ms | 0,01885 |
| 180 ms | 0,02209 |
| 220 ms | 0,02753 |
| 260 ms | 0,03337 |

## Movimento extremo e errático

Três sementes independentes, 10.000 cenários por semente: **30.000 cenários, 840.000 frames**. A velocidade e a frequência de mudança de direção foram aumentadas, incluindo uma política de viradas aleatórias frequentes.

| Métrica | Resultado médio aproximado |
|---|---:|
| Associação de observações visíveis (recall sintético) | 74,46% |
| Erro médio de posição | 0,01792 |
| Taxa de troca de identidade | 34,67% |
| Erro da previsão a 90 ms | 0,04350 |
| Erro da previsão a 260 ms | 0,12302 |

**Resultado: não passou nos limites de robustez para movimento extremo.** A associação de alvos piora muito quando os alvos mudam de direção quase aleatoriamente. Uma previsão não pode saber com certeza uma direção futura que é realmente aleatória; para melhorar seria necessário mais observações por segundo, uma política de incerteza mais conservadora e testes com capturas reais.

## Escopo e limitações

- Estes são dados gerados por simulador, não partidas reais, jogadores reais, capturas reais ou testes do OCR ML Kit.
- O simulador mede associação e erro usando a verdade-terreno gerada por ele; não mede FPS, consumo de bateria nem latência num telefone.
- Não foi possível executar `gradle testDebugUnitTest` ou `gradle assembleDebug`: o ambiente não tem Android SDK/Gradle instalado nem acesso funcional para resolver dependências. Nenhum APK foi produzido ou validado.
