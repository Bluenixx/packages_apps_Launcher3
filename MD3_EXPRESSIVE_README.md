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

## Fase 6
- ItemLongClickListener: toque longo em EDIT_MODE agora seleciona o item pressionado (antes não fazia nada com seleção vazia); seleção ordenada por tela/linha/coluna; "Criar pasta" só com atalhos soltos (container < 0).
- Launcher.onStateSetEnd: limpa a multisseleção ao sair de EDIT_MODE.
- Observação: ItemSelectionType.RANGE (fase 1) ainda não é acionado por nenhum gesto; falta ligar shift+clique/teclado em ItemClickHandler.

## Fase 7
- (Revertido na fase 8: Shift+clique não faz sentido em celular.)
- Springs expressivos de pasta (FolderSpringAnimatorSet) e a lista completa de formas (ShapesProvider, incl. Flower/Soft burst) JÁ EXISTEM, mas só ativam com as flags aconfig `enable_launcher_icon_shapes` e `enable_expressive_folder_expansion`. NÃO foram forçadas no código: `enable_launcher_icon_shapes` muda DatabaseHelper.SCHEMA_VERSION (34 vs 32). Ative via release config / valores de flag do build.

## Fase 8
- Removido o rastreamento de Shift (Launcher.java / ItemClickHandler.java).
- Seleção em intervalo por toque: em EDIT_MODE, com algo já selecionado, o toque longo em outro item oferece "Selecionar até aqui" (ItemSelectionType.RANGE a partir do último item tocado). Strings em/pt-BR: select_up_to_here.

## Fase 9
- Nova barra de ações MD3 no topo em EDIT_MODE: views/SelectionActionBar.java (contador, Remover, Nova pasta, Cancelar). Anexada/removida em Launcher.onStateSetEnd.
- WorkspaceSelectionManager.setSelectionListener (default no-op) notifica mudanças de seleção.
- ItemLongClickListener: helpers públicos removeSelection/canCreateFolder/sortedSelection. O diálogo por toque longo continua, agora também útil para "Selecionar até aqui".
- Não testado visualmente: posição (topMargin = status bar + 8dp) e cores (SurfaceContainerHigh/OnSurface) precisam ser conferidos no aparelho.

## Pendente
- Formas expressivas (cookie/squircle) nos ícones e nas pastas (PreviewBackground, FolderIcon).
- Springs/animações expressivas em FolderAnimationManager e AllAppsTransitionController.
- Pastas grandes: paginação/scroll em FolderPagedView, revisar clipping em FolderAnimationManager.
- Tipografia e cores dinâmicas em todo res/values*/styles.xml.

## Fase 10 (redesign visual, somente recursos XML)
Não compilado (sem AOSP). Valide com `m Launcher3QuickStep` e confira no aparelho.
- Configurações: linhas viram tiles tonais MD3 (raio 20dp, SurfaceContainer) via `layout/bluenixx_preference_tile.xml`, aplicado em `values-v31/styles.xml` (PreferenceStyle, PreferenceScreenStyle, SwitchPreferenceStyle). Se algum Preference quebrar, remova o item `android:layout` do estilo correspondente.
- Slider (`bluenixx_expressive_slider`): trilha tonal 16dp (SecondaryContainer/Primary) e handle em pílula de 4dp, com tokens materialColor em vez de colorAccent.
- Gaveta: aba selecionada em PrimaryContainer, pílula completa (`all_apps_header_pill_corner_radius` 100dp); texto das abas deixou de ser branco/#1F1F1F fixo (ilegível no modo escuro) e usa OnPrimaryContainer / OnSurfaceVariant.
- Letra da barra de rolagem rápida: bolha em formato de cookie de 8 lobos (vector gerado de curva polar) em `bg_letter_list_text.xml`.
- Menus de toque longo: `popup_smaller_radius` 4dp -> 10dp; `default_dialog_corner_radius` 26dp -> 28dp (afeta também diálogos e bottom sheets).
- Não alterado: formas dos ícones (continuam atrás das flags aconfig da fase 7), pacote de ícones (IconPackManager/SettingsActivity), transições da gaveta (AllAppsTransitionController).
