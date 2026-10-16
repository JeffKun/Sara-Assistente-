# Sara — assistente Android (v0.3, online e offline)

App nativo em Kotlin (minSdk 23 / Android 6+, targetSdk 35), sem bibliotecas externas.

## Como gerar o APK
- **GitHub (sem instalar nada):** suba esta pasta num repositório; a aba *Actions* roda o "Build APK" e gera o artefato `Sara-debug-apk` (`app-debug.apk`).
- **Android Studio:** abra a pasta e use *Build → Build APK(s)*.

## O que a Sara faz
- **Conversa com IA:** em ⚙ Configurações, cole sua chave da API da Anthropic (fica só no celular). Modelo padrão `claude-haiku-5-5`, editável. Sem chave, só executa comandos.
- **Visual animado:** a foto da Sara reage ao estado — respirando (parada), ondas rosa que seguem sua voz (ouvindo), pontinhos/arcos (pensando) e brilho com barras de áudio (falando). A mesma animação aparece na bolha.
- **Bolha flutuante:** arrastável (gruda na borda), lembra a posição; tocar nela abre a Sara já ouvindo. Notificação com botão "Desativar".
- **Comandos de voz/texto:** abrir qualquer app pelo nome, ligar para contato ou número, horas, data, bateria, lanterna, volume, timer, pesquisar na web, tocar/procurar no YouTube, abrir Wi-Fi/Bluetooth/Configurações, "desligar bolha".
- **Polimento:** ícone próprio, pedido de permissão de notificação (Android 13+), tratamento de barras do sistema (Android 15), botão de parar.

## Online e offline
| Recurso | Com internet | Sem internet |
|---|---|---|
| Conversa livre (IA) | sim, com sua chave | não; respostas simples locais (oi, piada, como vai…) |
| Comandos (apps, ligar, horas, lanterna, volume, timer, Bluetooth) | sim | sim |
| Pesquisar na web / YouTube | sim | avisa que está sem internet |
| Ouvir sua voz | reconhecimento do Google | usa o pacote de voz offline do Google, se baixado (Configurações de voz → Português offline) |
| Falar (TTS) | sim | sim, se a voz em português estiver instalada |
| Histórico de conversa | salvo no aparelho | salvo no aparelho |

O selo "● Online / ● Offline" no topo mostra o estado em tempo real. Se a internet cair no meio da conversa com a IA, a Sara responde com a conversa local.

## Limites
- O microfone só liga quando você toca no botão ou na bolha.
- O Android não permite que apps liguem Wi-Fi/Bluetooth sozinhos; a Sara abre o painel.
- Chave de IA dentro do app serve para uso pessoal. Para distribuir a outras pessoas, use um backend seu que guarde a chave.
- O código foi verificado só por compilação do Kotlin; teste no aparelho (o comportamento varia por fabricante/versão).
