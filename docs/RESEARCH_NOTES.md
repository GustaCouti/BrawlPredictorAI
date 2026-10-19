# Notas de pesquisa técnica — Brawl Coach AI

## Fontes consultadas

1. **ML Kit — reconhecimento de texto no Android**  
   https://developers.google.com/ml-kit/vision/text-recognition/v2/android?hl=pt-br  
   A documentação diferencia o modelo agrupado no aplicativo do modelo fornecido pelo Google Play Services. Este projeto usa `com.google.mlkit:text-recognition:16.0.1`, a variante agrupada, para não depender de baixar o modelo no primeiro uso. O ML Kit reconhece texto; ele não determina que um texto pertence a um inimigo, então o app acrescenta filtros visuais e temporais próprios.

2. **Android Developers — projeção de mídia**  
   https://developer.android.com/media/grow/media-projection?hl=pt-br  
   A captura deve começar após o consentimento explícito, usando o tipo e as permissões de serviço em primeiro plano exigidos pela versão do Android. O callback `onCapturedContentResize` é usado para reajustar a superfície quando o conteúdo capturado muda de dimensões, inclusive ao girar o dispositivo ou capturar uma janela de aplicativo no Android 14+.

3. **Portal oficial de suporte do Brawl Stars — modos de jogo**  
   https://support.supercell.com/brawl-stars/pt/articles/game-modes-13.html  
   A lista de modos e eventos é dinâmica. Portanto, o sistema não depende de uma lista fixa de mapas/modos nem declara que treinou a física de um mapa específico.

4. **Supercell — notícias oficiais do Brawl Stars**  
   https://supercell.com/en/games/brawlstars/pt/blog/  
   Serve como referência para acompanhar alterações oficiais de mecânicas/modos. Notícias e notas públicas não fornecem, por si só, coordenadas de personagens em tempo real, colisores privados ou telemetria de projéteis.

## Como as fontes influenciam a implementação

- O OCR usa linhas completas, pois nomes podem conter espaços e reconhecer palavra por palavra pode fragmentar uma identidade.
- Um candidato combina proporção de pixels vermelho/rosa, comprimento e geometria do texto, e evidência opcional de uma barra vermelha logo abaixo. Cor não é uma classificação suficiente por si só.
- Candidatos com evidência visual fraca precisam aparecer em leituras sucessivas antes de receber um marcador. O rastreador associa posições previstas e nomes semelhantes, suaviza velocidades e mantém uma previsão curta durante oclusões.
- O sinal de zigue-zague é apenas uma estimativa de reversões observadas. Ele encurta a linha de previsão para alvos erráticos, mas não prova que o inimigo reagiu a um disparo nem prevê com certeza uma esquiva.
- A sobreposição é somente visual: não injeta entradas, não toca automaticamente na tela e não altera o Brawl Stars.

## Limitações de dados

Não existe, neste projeto, uma interface oficial de telemetria para ler diretamente a posição real dos personagens, a hitbox de colisão, a trajetória de cada projétil, o resultado de um disparo ou a intenção do jogador. Esses elementos só podem ser estimados na imagem. O tamanho do marcador é uma aproximação visual a partir da altura do texto, não a hitbox interna do jogo.

O laboratório offline cria alvos sintéticos com padrões de movimento, obstáculos, oclusões e ruído de OCR. Ele valida lógica de rastreamento contra seus próprios dados gerados; não é uma partida real, não representa 10.000 pessoas reais e não prova desempenho em um telefone.
