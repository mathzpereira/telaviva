# Especificação de design: Tela Viva

- **Data:** 2026-10-21
- **Estado:** proposta escrita para revisão do usuário
- **Nome do app:** Tela Viva

## 1. Objetivo

Criar o Tela Viva, um app nativo e leve para Fire TV e Android TV que exiba câmeras IP na mesma rede local. A experiência principal é assistir a uma câmera em tela cheia ou a um mosaico com até quatro câmeras, usando o controle remoto da TV.

O app prioriza inicialização rápida, baixo consumo de memória e funcionamento direto na rede local, inclusive em aparelhos modestos. Não depende de conta, nuvem ou servidor intermediário.

## 2. Público e dispositivos-alvo

- Fire TV Stick de 3ª geração, Fire OS baseado em Android 9 / API 28.
- Outros aparelhos Android TV ou TV Box com Android 9 ou superior.
- Navegação por controle remoto D-pad, selecionar e voltar.

O desempenho deve ser medido no Fire TV Stick de referência e em pelo menos um TV Box real. Emuladores não substituem a validação de vídeo e memória no hardware-alvo.

## 3. Escopo da primeira versão

### Incluído

- Descoberta automática de câmeras ONVIF na rede local.
- Cadastro manual por endereço IP e URL RTSP, com usuário e senha da câmera.
- Lista local de câmeras cadastradas.
- Layouts predefinidos para 1, 2 e 4 câmeras; no máximo quatro streams simultâneos.
- Seleção da câmera exibida em cada espaço do mosaico.
- Seleção de uma câmera para visualização em tela cheia e retorno ao mosaico.
- Áudio somente da câmera em tela cheia; mosaicos sem áudio para evitar mistura de fontes.
- Estados por câmera para carregamento, conexão perdida, autenticação inválida ou stream incompatível, com opção de tentar novamente.

### Fora do escopo inicial

- Acesso remoto, nuvem, conta de usuário ou relay externo.
- Gravação de vídeo, captura de imagens ou reprodução de gravações.
- Protocolos proprietários que não exponham ONVIF ou RTSP.
- RTSP com vídeo H.265 no caminho inicial Media3.
- Controle PTZ e ajustes de imagem; o foco inicial de ajustes é a organização do layout.

## 4. Experiência e navegação

1. Na primeira abertura, o app mostra a ação de cadastrar uma câmera.
2. A pessoa pode buscar dispositivos ONVIF na rede ou preencher manualmente o IP e a URL RTSP.
3. Após cadastrar câmeras, a tela inicial mostra o layout escolhido: 1 tela, 2 lado a lado ou 4 em grade 2×2.
4. Pelo D-pad, a pessoa navega entre os espaços, escolhe qual câmera aparece em cada um e seleciona um espaço para abrir aquela câmera em tela cheia.
5. O botão Voltar sai da tela cheia e retorna ao mosaico anterior.
6. Os fluxos de cadastro, seleção e ajustes são acessíveis pelo controle remoto e mostram claramente qual item está em foco.

## 5. Arquitetura proposta

O app será desenvolvido em Kotlin como aplicativo Android nativo, sem WebView ou framework multiplataforma. A separação lógica terá quatro responsabilidades:

- **Interface de TV:** layouts, foco visual e navegação por D-pad.
- **Catálogo de câmeras:** nomes, endereços e seleção dos dispositivos cadastrados.
- **Descoberta ONVIF:** busca na rede local e consulta dos perfis/URLs de vídeo disponíveis.
- **Reprodução RTSP:** gerenciamento dos players Media3 ativos na tela.

Fluxo ONVIF: descoberta local → escolha do dispositivo → autenticação → consulta do perfil e da URL RTSP → reprodução pelo Media3.

Fluxo manual: preenchimento de IP/URL RTSP e credenciais → validação da conexão → salvamento local → reprodução pelo Media3.

A TV se conecta diretamente aos endereços locais das câmeras. A documentação atual do Media3 lista RTSP ao vivo com vídeo H.264 e áudio AAC ou AC3. Câmeras que oferecem apenas H.265 ficam fora do suporte inicial. A descoberta ONVIF não é obrigatória para o cadastro manual. [Media3 RTSP](https://developer.android.com/media/media3/exoplayer/rtsp) · [ONVIF Profile S](https://www.onvif.org/wp-content/uploads/2018/08/ONVIF_Profile_-S_Specification_v1-2.pdf)

## 6. Uso de recursos e falhas

- No mosaico, o app usa o stream secundário/de menor resolução da câmera quando disponível.
- Em tela cheia, o app pode usar o stream principal da câmera selecionada e encerra os outros players para liberar memória e decodificadores.
- Somente os streams visíveis permanecem conectados. Ao trocar de layout ou sair do app, os players que deixarem de estar visíveis são encerrados.
- Se a câmera não oferecer um stream de menor resolução, o app tenta reproduzir o stream cadastrado e informa que o mosaico pode exigir mais recursos.
- Falhas em uma câmera são mostradas no espaço correspondente e não encerram os outros players nem o app.
- Mensagens de erro distinguem câmera indisponível, credenciais rejeitadas e formato não suportado, sem expor senhas.

Quatro streams simultâneos são o limite funcional. A reprodução fluida depende da resolução, codec e quantidade de streams que cada câmera disponibiliza e da capacidade de decodificação do TV Box.

## 7. Privacidade e armazenamento

- A visualização funciona somente na rede local; o app não cria conexões de acesso remoto.
- Não há conta, sincronização em nuvem, telemetria ou gravação de vídeo na primeira versão.
- Nomes e endereços de câmeras são guardados localmente.
- Usuários e senhas são cifrados localmente com uma chave do Android Keystore, que protege a chave criptográfica contra extração do dispositivo. [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- Senhas não aparecem após o cadastro nem são gravadas em logs.
- Remover uma câmera apaga também as credenciais correspondentes.
- A cifra em repouso não garante proteção do transporte: RTSP direto na rede local pode não usar TLS, conforme a câmera. A primeira versão não promete criptografia entre a TV e a câmera.

## 8. Critérios de validação

A versão só será considerada pronta para avaliação quando estes fluxos forem validados em hardware real:

1. Instalar e abrir no Fire TV Stick Android 9/API 28 e em um segundo Android TV/TV Box.
2. Encontrar e cadastrar uma câmera compatível por ONVIF e cadastrar outra por IP/RTSP manual.
3. Exibir layouts de 1, 2 e 4 câmeras H.264 e abrir qualquer espaço em tela cheia.
4. Navegar e operar os fluxos principais apenas com o controle remoto.
5. Confirmar que o áudio vem somente da câmera em tela cheia.
6. Confirmar visualização local com acesso à internet bloqueado, mantendo TV e câmeras na mesma rede.
7. Simular câmera offline, senha incorreta e codec não suportado; a falha deve permanecer isolada naquela câmera e permitir nova tentativa.
8. Medir memória, tempo até o primeiro quadro e estabilidade com quatro streams no aparelho de referência. O relatório deve registrar se são necessários substreams de baixa resolução para manter o mosaico fluido.
9. Confirmar que os logs não contêm credenciais e que remover um dispositivo elimina as credenciais salvas.

## 9. Limites de compatibilidade e riscos

- “Qualquer câmera” significa câmera IP que forneça RTSP acessível na rede local; ONVIF permite descoberta e obtenção de perfis quando implementado pela câmera. Câmeras que só funcionam pelo app/nuvem proprietária não são cobertas.
- A primeira versão suporta vídeo RTSP H.264. Uma câmera que ofereça somente H.265 precisará expor um perfil H.264 ou será incompatível com a reprodução inicial Media3.
- Descoberta ONVIF pode não atravessar redes de convidados, VLANs ou isolamento de clientes Wi-Fi; o cadastro manual por IP/RTSP permanece como alternativa.
- O limite de quatro streams não garante fluidez com qualquer resolução. O requisito de desempenho será avaliado com substreams de baixa resolução no hardware-alvo.

## 10. Decisões aprovadas durante o brainstorming

- App novo e nativo para Android TV/Fire TV.
- Uso restrito à rede local.
- Compatibilidade baseada em ONVIF/RTSP, com busca automática e cadastro manual.
- Layouts prontos de 1, 2 e 4 câmeras; máximo de quatro streams simultâneos.
- Seleção de câmera em tela cheia.
- Live view apenas; sem gravação na primeira versão.
- Áudio somente da câmera em tela cheia.
- Media3 e suporte inicial a RTSP H.264, priorizando baixo consumo no Fire TV Stick Android 9.
- Credenciais guardadas localmente e cifradas com Android Keystore.
