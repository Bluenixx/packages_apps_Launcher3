/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.launcher3.views;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.launcher3.InsettableFrameLayout;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.WorkspaceSelectionManager;
import com.android.launcher3.touch.ItemLongClickListener;

import java.util.List;

/**
 * MD3 Expressive contextual action bar shown at the top of the home screen while the user is
 * selecting items in edit mode. It shows how many items are selected and offers Remove,
 * New folder and Cancel without needing a dialog.
 */
public class SelectionActionBar extends LinearLayout {

    private final Launcher mLauncher;
    private final WorkspaceSelectionManager mSelectionManager;
    private final TextView mCount;
    private final TextView mRemove;
    private final TextView mFolder;

    private SelectionActionBar(Launcher launcher) {
        super(launcher);
        mLauncher = launcher;
        mSelectionManager = launcher.getActivityComponent().getWorkspaceSelectionManager();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setElevation(dp(6));

        // Fully rounded container (MD3 Expressive), SurfaceContainerHigh.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(launcher.getColor(R.color.materialColorSurfaceContainerHigh));
        bg.setCornerRadius(dp(28));
        setBackground(bg);
        setPadding(dp(20), 0, dp(8), 0);

        mCount = createLabel(launcher, false);
        mCount.setPadding(0, 0, dp(12), 0);
        addView(mCount);

        mRemove = createLabel(launcher, true);
        mRemove.setText(R.string.remove_drop_target_label);
        mRemove.setOnClickListener(v -> ItemLongClickListener.removeSelection(
                mLauncher, ItemLongClickListener.sortedSelection(mSelectionManager),
                mSelectionManager));
        addView(mRemove);

        mFolder = createLabel(launcher, true);
        mFolder.setText(R.string.create_new_folder_button_text);
        mFolder.setOnClickListener(v -> {
            List<View> selected = ItemLongClickListener.sortedSelection(mSelectionManager);
            if (ItemLongClickListener.canCreateFolder(selected)) {
                ItemLongClickListener.createFolderFromSelection(
                        mLauncher, selected, mSelectionManager);
            }
        });
        addView(mFolder);

        TextView cancel = createLabel(launcher, true);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(v -> {
            mSelectionManager.clearSelection();
            mLauncher.getStateManager().goToState(LauncherState.NORMAL);
        });
        addView(cancel);

        refresh();
    }

    private TextView createLabel(Context context, boolean clickable) {
        TextView tv = new TextView(context);
        tv.setTextSize(14);
        tv.setTextColor(context.getColor(R.color.materialColorOnSurface));
        tv.setGravity(Gravity.CENTER);
        tv.setMinHeight(dp(48));
        if (clickable) {
            tv.setPadding(dp(12), 0, dp(12), 0);
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Updates the counter and which actions are available for the current selection. */
    private void refresh() {
        List<View> selected = ItemLongClickListener.sortedSelection(mSelectionManager);
        int count = selected.size();
        mCount.setText(count == 0
                ? getContext().getString(R.string.selection_bar_hint)
                : getContext().getString(R.string.selection_bar_count, count));
        mRemove.setEnabled(count > 0);
        mRemove.setAlpha(count > 0 ? 1f : 0.38f);
        mFolder.setVisibility(ItemLongClickListener.canCreateFolder(selected) ? VISIBLE : GONE);
    }

    /** Adds the bar to the launcher's drag layer and starts listening to selection changes. */
    public static SelectionActionBar attach(Launcher launcher) {
        SelectionActionBar bar = new SelectionActionBar(launcher);
        InsettableFrameLayout.LayoutParams lp = new InsettableFrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, bar.dp(56));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.ignoreInsets = true;
        WindowInsets insets = launcher.getDragLayer().getRootWindowInsets();
        int top = insets == null ? 0 : insets.getInsets(WindowInsets.Type.statusBars()).top;
        lp.topMargin = top + bar.dp(8);
        launcher.getDragLayer().addView(bar, lp);
        bar.mSelectionManager.setSelectionListener(bar::refresh);
        return bar;
    }

    /** Removes the bar and stops listening to selection changes. */
    public void detach() {
        mSelectionManager.setSelectionListener(null);
        if (getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) getParent()).removeView(this);
        }
    }
}
