# ReiVex — auditoria de confiabilidade (2026-11-17)

## Escopo e decisões
Esta revisão está restrita ao repositório **ReiVex**. Nenhum código do ReiAnix foi alterado. Mantidas a identidade visual, a arquitetura com WebView/Compose, o bloqueador equilibrado/avançado, a fila de downloads e as funções HTTP, HLS e DASH. Não foram adicionadas bibliotecas nem técnicas de bypass de DRM.

## Problemas confirmados no código e alterações
1. **Perda de progresso quando uma faixa paralela falha**. Em `DownloadEngine.transfer`, o tratamento genérico de falha após `DownloadRanges.receive` chamava `restart(id, target)` incondicionalmente; isso zerava o arquivo, inclusive quando as ondas anteriores já haviam sido gravadas, sincronizadas e registradas no banco. Agora o fallback serial preserva a quantidade de bytes efetivamente confirmada quando o arquivo ainda contém esse prefixo e o validador ETag forte não mudou. Caso a integridade não seja demonstrável, o arquivo ainda recomeça do zero. Os downloads paralelos são desativados para o item após falha, evitando o mesmo ciclo.
2. **Opções de mídia omitidas por falta de rótulo de resolução**. A apresentação das opções filtrava por rótulos como `720p`, embora a própria descoberta já validasse links de vídeos que só anunciam `video/mp4` ou não expõem resolução. Agora todas as opções reconhecidas pelo conjunto já existente de critérios de elegibilidade são apresentadas, inclusive `Qualidade não informada`; nenhuma opção desconhecida é inventada.
3. **Processo WebView encerrado pelo Android**. A atividade não implementava `WebViewClient.onRenderProcessGone`. Adicionada recuperação da guia visível, reconstrução controlada do `AndroidView`/WebView, descarte do estado incompatível e liberação da instância encerrada; janelas popup também recebem tratamento do encerramento. O callback do Android ocorre fora da thread de UI, então as alterações de estado Compose e a destruição de popups são encaminhadas para a thread principal.
4. **Nome corrigido ausente na notificação ativa**. O cache de deduplicação das notificações não incluía `item.name`; se a resposta HTTP refinasse o nome depois do início, a notificação poderia manter o rótulo antigo enquanto os demais campos permanecessem iguais. O marcador agora inclui o nome e tem teste de regressão.
5. **Notificação concluída reaparecia depois de dispensada e reiniciado o processo**. O estado de publicação deduplicada era mantido apenas em memória. Agora a ação de dispensa grava a preferência no item do banco local e a publicação posterior respeita esse estado, inclusive após reinício do processo; teste cobre a política de persistência.

## Segurança e compatibilidade
- A retomada só reaproveita bytes com validador forte da mesma representação e tamanho persistido coerente. O servidor ainda deve aceitar `If-Range`, `206` e o intervalo solicitado.
- A coleta de mídia continua limitada à inspeção de recursos de páginas carregadas no navegador. DRM e controles de acesso não são contornados.
- As exceções existentes para Cloudflare/Turnstile, reCAPTCHA e hCaptcha foram preservadas.
- Nenhuma dependência foi adicionada, nem IDs, permissões, assinatura de release ou armazenamento foram alterados.

## Sinais de uso e decisões de produto
- A página oficial do 1DM posiciona navegador integrado, captura de mídia e gerenciador de downloads como um fluxo único; um pedido público no projeto AB Download Manager também solicita detecção de mídia HLS/DASH e um indicador claro de links encontrados. Isso apoia manter a captura contextual no navegador e não esconder links válidos sem rótulo de resolução.
- Relatos públicos no Reddit e no Brave Community descrevem travamentos do navegador e falhas/baixa velocidade em downloads grandes ou em segundo plano. São relatos individuais, não uma pesquisa representativa; usei-os como sinais para priorizar recuperação do renderer e retomada segura em vez de acrescentar recursos pesados.
- Referências: [1DM no Google Play](https://play.google.com/store/apps/details?id=idm.internet.download.manager), [pedido de captura de mídia no AB Download Manager](https://github.com/amir1376/ab-download-manager/issues/1264), [relato sobre estabilidade do navegador 1DM](https://www.reddit.com/r/androidapps/comments/1ubb7v3/is_1dm_abandoned_if_so_then_are_there_any/), [pedido sobre download externo e confiabilidade no Brave para Android](https://github.com/brave/brave-browser/issues/53840).

## Testes de regressão
- `DownloadParallelRecoveryTest`: prefixo confiável, bytes extras não confirmados, progresso inválido e ETags fracos/diferentes.
- `DownloadNotificationIconTest`: marcador atualizado após correção de nome, política de dispensa persistente e presença de ação de dispensa.
- `BrowserMediaOptionTest`: opção de vídeo direto sem rótulo de qualidade, ao lado dos testes existentes de deduplicação, prioridade, filtros e HLS.
- A CI da PR também invoca `DownloadHttpNaviBehaviorTest`, para resguardar as respostas HTTP, cookies e cabeçalhos entre redirecionamentos.
- **Validação a executar na PR:** testes JVM, compilação do APK e teste manual em dispositivo Android; nenhum resultado é declarado aprovado antecipadamente.

## Como verificar manualmente
1. Inicie download HTTP grande via servidor que oferece `Accept-Ranges: bytes` e ETag forte; provoque falha em uma faixa secundária depois de 1+ MB concluídos. Confirme que a transição para uma conexão conserva bytes confirmados e chega a 100%, com hash do arquivo correto.
2. Teste um vídeo legítimo direto com MIME `video/mp4` ou URL `.mp4` mas sem resolução anunciada. Confirme que aparece com qualidade não informada, sem criar qualidades fictícias.
3. Abra uma página em guia ativa, force o encerramento do renderizador WebView num dispositivo de teste Android 8+ e confirme que a guia é reconstruída sem fechar o app.
4. Teste popups, alternância de guias, links Nuvio/Anikku, desafios Cloudflare, HLS/DASH, pausa/retomada, notificações e economia de memória, pois dependem de servidor e dispositivo real.

## Referências técnicas
- [Android Developers: Manage WebView objects](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview)
- [Android Developers: Handle WebView termination](https://developer.android.com/develop/ui/views/layout/webapps/handle-termination)
- [Android Developers: User-initiated data transfer](https://developer.android.com/develop/background-work/background-tasks/uidt)
- [Android Developers: Long-running WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)
- [Android Developers: notification delete intent](https://developer.android.com/reference/android/app/Notification#deleteIntent)
- [RFC 9110: Range, ETag, If-Range and 206](https://www.rfc-editor.org/rfc/rfc9110)
- [Reddit: usuários valorizam a captura integrada de links pelo 1DM](https://www.reddit.com/r/androidapps/comments/1nhz4ef/which_better_1dm_or_adm_or_is_there_any_better/)
- [Reddit: reclamações de travamento em navegadores de download](https://www.reddit.com/r/androidapps/comments/1ubb7v3/is_1dm_abandoned_if_so_then_are_there_any/)

## Próximos aprofundamentos (não implementados nesta PR)
- Testes instrumentados de WebView e transferências reais sob perda de rede.
- Auditoria do fluxo de gravação/publicação de arquivos grandes e retomada após reboot.
- Perfilamento de CPU, RAM, consumo energético e uso do armazenamento em aparelhos de entrada.
- Testes de downloads públicos HLS/DASH variando cabeçalhos, cookies, origem e alternância de qualidade.
