/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.touch;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static com.android.launcher3.LauncherState.ALL_APPS;
import static com.android.launcher3.LauncherState.EDIT_MODE;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.logging.StatsLogManager.LauncherEvent.LAUNCHER_ALLAPPS_ITEM_LONG_PRESSED;

import android.app.AlertDialog;
import android.view.View;
import android.view.View.OnLongClickListener;

import com.android.launcher3.DropTarget;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.WorkspaceSelectionManager;
import com.android.launcher3.CellLayout;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.celllayout.CellInfo;
import com.android.launcher3.dragndrop.DragController;
import com.android.launcher3.dragndrop.DragOptions;
import com.android.launcher3.folder.Folder;
import com.android.launcher3.logging.StatsLogManager.StatsLogger;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.PrivateSpaceInstallAppButtonInfo;
import com.android.launcher3.testing.TestLogging;
import com.android.launcher3.testing.shared.TestProtocol;
import com.android.launcher3.views.BubbleTextHolder;
import com.android.launcher3.widget.LauncherAppWidgetHostView;

/**
 * Class to handle long-clicks on workspace items and start drag as a result.
 */
public class ItemLongClickListener {

    public static final OnLongClickListener INSTANCE_WORKSPACE =
            ItemLongClickListener::onWorkspaceItemLongClick;

    public static final OnLongClickListener INSTANCE_ALL_APPS =
            ItemLongClickListener::onAllAppsItemLongClick;

    private static boolean onWorkspaceItemLongClick(View v) {
        if (v instanceof LauncherAppWidgetHostView) {
            TestLogging.recordEvent(TestProtocol.SEQUENCE_MAIN, "Widgets.onLongClick");
        } else {
            TestLogging.recordEvent(TestProtocol.SEQUENCE_MAIN, "onWorkspaceItemLongClick");
        }
        Launcher launcher = Launcher.getLauncher(v.getContext());
        if (!canStartDrag(launcher)) return false;
        if (launcher.isInState(EDIT_MODE)) {
            showSelectionActions(launcher, v);
            return true;
        }
        if (!launcher.isInState(NORMAL) && !launcher.isInState(OVERVIEW)) {
            return false;
        }
        if (!(v.getTag() instanceof ItemInfo)) return false;

        launcher.setWaitingForResult(null);
        beginDrag(v, launcher, (ItemInfo) v.getTag(), new DragOptions());
        return true;
    }


    private static void showSelectionActions(Launcher launcher, View pressed) {
        WorkspaceSelectionManager selectionManager = launcher.getActivityComponent()
                .getWorkspaceSelectionManager();
        // A long press on an item that is not selected yet selects it first, so the press
        // always acts on something instead of silently doing nothing.
        // If something is already selected, offer "select up to here" (range from the last
        // tapped item) instead of silently adding the pressed item.
        final boolean offerRange = !pressed.isSelected()
                && !selectionManager.getSelectedViews().isEmpty();
        if (!pressed.isSelected() && !offerRange) {
            selectionManager.updateItemSelection(pressed,
                    WorkspaceSelectionManager.ItemSelectionType.TOGGLE_SINGLE);
        }
        java.util.List<View> selected = new java.util.ArrayList<>(
                selectionManager.getSelectedViews());
        if (selected.isEmpty()) {
            return;
        }
        // Keep a stable reading order (screen, row, column) so the first item is top-left.
        selected.sort(java.util.Comparator
                .comparingInt((View view) -> view.getTag() instanceof ItemInfo info
                        ? info.screenId : 0)
                .thenComparingInt(view -> view.getTag() instanceof ItemInfo info
                        ? info.cellY : 0)
                .thenComparingInt(view -> view.getTag() instanceof ItemInfo info
                        ? info.cellX : 0));

        boolean canCreateFolder = selected.size() >= 2;
        for (View view : selected) {
            // Only loose workspace/hotseat shortcuts can be merged; items already inside a folder
            // (container >= 0) are excluded.
            if (!(view.getTag() instanceof WorkspaceItemInfo item)
                    || item.container >= 0) {
                canCreateFolder = false;
                break;
            }
        }
        java.util.List<String> actions = new java.util.ArrayList<>();
        if (offerRange) {
            actions.add(launcher.getString(
                    com.android.launcher3.R.string.select_up_to_here));
        }
        actions.add(launcher.getString(com.android.launcher3.R.string.remove_drop_target_label));
        if (canCreateFolder) {
            actions.add(launcher.getString(com.android.launcher3.R.string.create_new_folder_button_text));
        }
        actions.add(launcher.getString(android.R.string.cancel));

        final boolean allowFolder = canCreateFolder;
        final int base = offerRange ? 1 : 0;
        new AlertDialog.Builder(launcher)
                .setTitle(launcher.getString(com.android.launcher3.R.string.edit_home_screen)
                        + " (" + selected.size() + ")")
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (offerRange && which == 0) {
                        selectionManager.updateItemSelection(pressed,
                                WorkspaceSelectionManager.ItemSelectionType.RANGE);
                    } else if (which == base) {
                        removeSelection(launcher, selected, selectionManager);
                    } else if (allowFolder && which == base + 1) {
                        createFolderFromSelection(launcher, selected, selectionManager);
                    }
                })
                .show();
    }

    /** Removes all the given selected views from the workspace and leaves edit mode. */
    public static void removeSelection(Launcher launcher, java.util.List<View> selected,
            WorkspaceSelectionManager selectionManager) {
        for (View itemView : selected) {
            if (itemView.getTag() instanceof ItemInfo itemInfo) {
                launcher.removeItem(itemView, itemInfo, true);
            }
        }
        selectionManager.clearSelection();
        launcher.getStateManager().goToState(LauncherState.NORMAL);
    }

    /** Whether the selection only has loose shortcuts, so it can be merged into a new folder. */
    public static boolean canCreateFolder(java.util.List<View> selected) {
        if (selected.size() < 2) return false;
        for (View view : selected) {
            if (!(view.getTag() instanceof WorkspaceItemInfo item) || item.container >= 0) {
                return false;
            }
        }
        return true;
    }

    /** Returns the selected views sorted by screen, row and column. */
    public static java.util.List<View> sortedSelection(WorkspaceSelectionManager manager) {
        java.util.List<View> selected = new java.util.ArrayList<>(manager.getSelectedViews());
        selected.sort(java.util.Comparator
                .comparingInt((View view) -> view.getTag() instanceof ItemInfo info
                        ? info.screenId : 0)
                .thenComparingInt(view -> view.getTag() instanceof ItemInfo info
                        ? info.cellY : 0)
                .thenComparingInt(view -> view.getTag() instanceof ItemInfo info
                        ? info.cellX : 0));
        return selected;
    }

    public static void createFolderFromSelection(Launcher launcher, java.util.List<View> selected,
            WorkspaceSelectionManager selectionManager) {
        if (selected.size() < 2) return;
        View firstView = selected.get(0);
        if (!(firstView.getTag() instanceof WorkspaceItemInfo firstInfo)) return;
        com.android.launcher3.celllayout.CellPosMapper.CellPos presenterPos =
                launcher.getCellPosMapper().mapModelToPresenter(firstInfo);
        int container = firstInfo.container;
        int screenId = presenterPos.screenId;
        int cellX = presenterPos.cellX;
        int cellY = presenterPos.cellY;
        CellLayout layout = launcher.getCellLayout(container, screenId);
        if (layout == null) return;
        java.util.List<WorkspaceItemInfo> items = new java.util.ArrayList<>();
        for (View view : selected) {
            if (view.getTag() instanceof WorkspaceItemInfo item) {
                items.add(item);
                launcher.getWorkspace().removeWorkspaceItem(view);
            }
        }
        FolderIcon folderIcon = launcher.addFolder(layout, container, screenId, cellX, cellY);
        FolderInfo folderInfo = folderIcon.mInfo;
        for (int i = 0; i < items.size(); i++) {
            WorkspaceItemInfo item = items.get(i);
            folderInfo.add(item);
            launcher.getModelWriter().moveItemInDatabase(item, folderInfo.id, 0, i, 0);
            item.container = folderInfo.id;
            item.screenId = 0;
            item.cellX = i;
            item.cellY = 0;
        }
        folderIcon.onItemsChanged(false);
        selectionManager.clearSelection();
        launcher.getStateManager().goToState(LauncherState.NORMAL);
    }

    public static void beginDrag(View v, Launcher launcher, ItemInfo info,
            DragOptions dragOptions) {
        if (info.container >= 0) {
            Folder folder = Folder.getOpen(launcher);
            if (folder != null) {
                if (!folder.getIconsInReadingOrder().contains(v)) {
                    folder.close(true);
                } else {
                    folder.startDrag(v, dragOptions);
                    return;
                }
            }
        }

        CellInfo longClickCellInfo = new CellInfo(v, info,
                launcher.getCellPosMapper().mapModelToPresenter(info));
        launcher.getWorkspace().startDrag(longClickCellInfo, dragOptions);
    }

    private static boolean onAllAppsItemLongClick(View view) {
        TestLogging.recordEvent(TestProtocol.SEQUENCE_MAIN, "onAllAppsItemLongClick");
        view.cancelLongPress();
        View v = (view instanceof BubbleTextHolder)
                ? ((BubbleTextHolder) view).getBubbleText()
                : view;
        Launcher launcher = Launcher.getLauncher(v.getContext());
        if (!canStartAllAppsItemDrag(launcher)) return false;

        StatsLogger logger = launcher.getStatsLogManager().logger();
        if (v.getTag() instanceof ItemInfo itemInfo) {
            if (itemInfo instanceof PrivateSpaceInstallAppButtonInfo) {
                return false;
            }
            logger.withItemInfo((ItemInfo) v.getTag());
        }
        logger.log(LAUNCHER_ALLAPPS_ITEM_LONG_PRESSED);

        // Start the drag
        final DragController dragController = launcher.getDragController();
        dragController.addDragSessionListener(new DragController.DragSessionListener() {
            @Override
            public void onDragSessionStart(DropTarget.DragObject dragObject, DragOptions options) {
                v.setVisibility(INVISIBLE);
            }

            @Override
            public void onDragSessionEnd() {
                v.setVisibility(VISIBLE);
                dragController.removeDragSessionListener(this);
            }
        });

        launcher.getWorkspace().beginDragShared(v, launcher.getAppsView(), new DragOptions());
        return false;
    }

    /**
     * Returns true if an item drag can be started from All Apps.
     */
    public static boolean canStartAllAppsItemDrag(Launcher launcher) {
        return canStartDrag(launcher) && (launcher.isInState(ALL_APPS) || launcher.isInState(
                OVERVIEW)) && !launcher.getWorkspace().isSwitchingState();
    }

    public static boolean canStartDrag(Launcher launcher) {
        if (launcher == null) {
            return false;
        }
        // We prevent dragging when we are loading the workspace as it is possible to pick up a view
        // that is subsequently removed from the workspace in startBinding().
        if (launcher.isWorkspaceLocked()) return false;
        // Return early if an item is already being dragged (e.g. when long-pressing two shortcuts)
        if (launcher.getDragController().isDragging()) return false;
        // Return early if user is in the middle of selecting split-screen apps
        if (launcher.isSplitSelectionActive()) {
            return false;
        }

        return true;
    }
}
