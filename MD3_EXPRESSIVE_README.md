# Continuação: Launcher3 -> MD3 Expressive (para outra IA)

Base: packages_apps_Launcher3 (LineageOS/AOSP). Não foi compilado (sem ambiente AOSP/rede). Valide com `m Launcher3QuickStep`.

## Feito (commit "MD3 Expressive: fase 1")
- WorkspaceSelectionManager.kt: implementada seleção em intervalo (RANGE), antes era TODO vazio; âncora fixa durante o range.
- Busca da gaveta: sem stroke, raio 28dp, altura 56dp, cor SurfaceContainerHigh, texto 16sp.
- Pastas: raio 28dp via `folder_bg_round_rect_radius` (round_rect_folder.xml).

## Fase 2
- Realce da multisseleção (UtilitiesKt.kt): Paints reutilizados (antes alocava em onDraw), raio em dp, cor SecondaryContainer.
- Pastas: interpolators emphasized_decelerate/accelerate e duração 300ms.

## Fase 3
- FolderGridOrganizer: grade antiga é limitada aos máximos atuais (bug ao recolher pasta expandida).
- Pasta grande: diálogo "Tamanho da pasta" com strings localizadas (antes texto fixo em inglês); rótulo do atalho agora é fixo "Folder size".
- ShapesProvider: novas formas Flower e Soft burst (paths gerados por curva polar), strings en + pt-BR.

## Fase 4
- Causa de "pasta grande continua pequena": FolderGridOrganizer.calculateGridSize encolhia a grade ao nº de itens mesmo expandida. Agora, expandida usa sempre colunas x linhas configuradas.
- FolderPagedView.arrangeChildren: colunas/linhas limitadas ao que cabe na tela (largura do bounding box, altura máxima do conteúdo); mínimo 2.

## Fase 5
- AppsSearchContainerLayout: RenderEffect de blur criado uma vez (antes a cada frame); animador de largura cancelado em onDetachedFromWindow.
- bg_all_apps_searchbox.xml: raio 100dp (pílula) para casar com o recorte do blur (h/2).
- Observação (não alterado): KeyboardInsetAnimationCallback usa -ime.bottom no arrasto manual, sem descontar a barra de navegação (diferente de onApplyWindowInsets da busca); callback é compartilhado com Folder e WorkUtilityView.

## Pendente
- Formas expressivas (cookie/squircle) nos ícones e nas pastas (PreviewBackground, FolderIcon).
- Springs/animações expressivas em FolderAnimationManager e AllAppsTransitionController.
- Pastas grandes: paginação/scroll em FolderPagedView, revisar clipping em FolderAnimationManager.
- Barra de seleção/ações da multisseleção (UI), realce com SecondaryContainer em drawWorkspaceItemSelectionHighlight.
- Tipografia e cores dinâmicas em todo res/values*/styles.xml.
