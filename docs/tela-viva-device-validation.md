# Validação do Tela Viva em dispositivos

Data: 21/10/2026 (America/Sao_Paulo)

## Build validado

- Revisão do código: `76d7b0e43a666f67a5e9d8f6f0c851a27424df2e`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `46ad5df23a475e341a47a3f69ad60088a0155e13454db31dd7707dcb8267670a`.
- Verificação automatizada: 25/25 testes unitários, 20/20 testes instrumentados no Fire TV, `lintDebug` e `assembleDebug` passaram.

## Fire TV Stick

- Modelo: Fire TV Stick 3ª geração, `AFTSSS`.
- Firmware: `PS7716.5666N`; Android 9, API 28.
- Resolução reportada: 1920 × 1080.
- O APK instalou e abriu na tela inicial vazia. A interface exibiu as opções 1, 2 e 4 câmeras, os controles de organização e o espaço da câmera; a captura mostrou foco visível no botão `1 CÂMERA`.
- No Android 9, o fluxo abriu sem solicitar permissão de rede local em tempo de execução. O teste instrumentado `api28DoesNotRequestRuntimePermission` também passou.
- `dumpsys meminfo` na tela inicial vazia, sem câmeras configuradas: PSS total 45.372 KB, PSS de heap Java 5.920 KB, heap nativo 4.380 KB e gráficos 7.909 KB; Swap PSS total 66 KB. Este valor descreve apenas a tela vazia e não mede o consumo durante reprodução.
- O processo tinha zero sockets OpenSSL abertos na coleta. Nenhuma câmera foi configurada, então não houve conexão RTSP nem medição de primeiro quadro.
- Após a validação, o pacote Tela Viva foi desinstalado. A lista de pacotes de terceiros coincidiu com o snapshot anterior, e `com.global.veloplaytv/com.main.ui.activity.HomeActivity` voltou ao primeiro plano.

## Cobertura ainda pendente

A aceitação de hardware permanece incompleta. Só havia um Fire TV conectado; não havia um segundo Android TV/TV Box nem endereço, credenciais ou informações de codec de uma câmera disponíveis para este teste. Nenhum resultado de vídeo foi presumido.

- Segundo Android TV/TV Box: pendente.
- Descoberta ONVIF e cadastro RTSP manual em câmera real: pendentes.
- Codecs e resoluções da câmera, primeiro quadro, áudio em tela cheia, estabilidade e uso de memória com 1/2/4 streams H.264: pendentes; quatro streams e escolha de substream não foram medidos.
- Câmera offline, senha incorreta, câmera H.265, retry isolado e continuidade das demais câmeras: pendentes em hardware.
- Operação com Internet bloqueada e inspeção de logs durante um stream autenticado: pendentes em hardware.
- Navegação D-pad e retorno de tela têm cobertura nos testes instrumentados (incluindo `TvNavigationTest`); o controle remoto físico não foi testado manualmente.
- Redação de dados sensíveis tem cobertura automatizada em `CredentialLogRedactionTest`; não foram usados dados de câmera reais neste teste físico.

Para concluir a aceitação, repetir o roteiro com uma câmera RTSP H.264 local e um segundo aparelho Android TV/TV Box. Registrar os codecs, as resoluções, o primeiro quadro e a saída completa de `dumpsys meminfo` durante 1, 2 e 4 streams antes de declarar a validação de hardware completa.
