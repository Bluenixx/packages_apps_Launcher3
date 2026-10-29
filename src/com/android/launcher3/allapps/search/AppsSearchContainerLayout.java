/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.allapps.search;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getSize;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.Utilities.prefixTextWithIcon;
import static com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.widget.PopupMenu;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import com.android.launcher3.model.data.AppInfo;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Insettable;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.allapps.AllAppsStore;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.PrivateProfileManager;
import com.android.launcher3.allapps.SearchUiManager;
import com.android.launcher3.anim.KeyboardInsetAnimationCallback;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;

/**
 * Layout to contain the All-apps search UI.
 */
public class AppsSearchContainerLayout extends ExtendedEditText
        implements SearchUiManager, SearchCallback<AdapterItem>,
        AllAppsStore.OnUpdateListener, Insettable {

    private final ActivityContext mLauncher;
    private final AllAppsSearchBarController mSearchBarController;
    private final SpannableStringBuilder mSearchQueryBuilder;

    private ActivityAllAppsContainerView<?> mAppsView;

    // The amount of pixels to shift down and overlap with the rest of the content.
    private final int mContentOverlap;

    // Bluenixx: fundo translucido (~45% opaco) + blur, hint e lupa centralizados como grupo.
    private static final int SEARCH_BAR_BG_ALPHA = 255;
    private final int mMaxBarWidth;
    private float mBluenixxWidthExpansion = 0.88f;
    private android.animation.ValueAnimator mBluenixxWidthAnimator;
    private boolean mBluenixxImeVisible;

    private android.graphics.drawable.Drawable mSearchIcon;
    private android.graphics.drawable.Drawable mBaseBackground;
    private boolean mBlurEnabled;
    private final Set<AppInfo> mSelectedApps = new LinkedHashSet<>();
    private static final String DRAWER_PREFS = "bluenixx_drawer_folders";
    private final android.graphics.RenderNode mBlurNode =
            new android.graphics.RenderNode("searchBarBackdropBlur");
    private final android.graphics.Path mClipPath = new android.graphics.Path();
    private float mBlurRadiusPx = -1f;
    private final int[] mBarLoc = new int[2];
    private final int[] mListLoc = new int[2];
    private androidx.recyclerview.widget.RecyclerView mObservedList;
    private final androidx.recyclerview.widget.RecyclerView.OnScrollListener mScrollListener =
            new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(androidx.recyclerview.widget.RecyclerView rv,
                        int dx, int dy) {
                    invalidate();
                }
            };
    private final java.util.function.Consumer<Boolean> mBlurListener =
            this::onBlurEnabledChanged;

    public AppsSearchContainerLayout(Context context) {
        this(context, null);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mLauncher = ActivityContext.lookupContext(context);
        mSearchBarController = new AllAppsSearchBarController();

        mSearchQueryBuilder = new SpannableStringBuilder();
        Selection.setSelection(mSearchQueryBuilder, 0);

        mContentOverlap =
                getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_content_overlap);

        // Bluenixx: lift the bottom search bar above the keyboard.
        setWindowInsetsAnimationCallback(new KeyboardInsetAnimationCallback(this));

        mMaxBarWidth = getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_max_width);
        setElevation(0f);
        setTranslationZ(0f);

        android.graphics.drawable.Drawable bg = getBackground();
        if (bg != null) {
            mBaseBackground = bg.mutate();
            mBaseBackground.setAlpha(SEARCH_BAR_BG_ALPHA);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) getParent()).setClipChildren(false);
        }
        mAppsView.getAppsStore().addUpdateListener(this);
        android.view.CrossWindowBlurListeners blurListeners =
                android.view.CrossWindowBlurListeners.getInstance();
        blurListeners.addListener(getContext().getMainExecutor(), mBlurListener);
        onBlurEnabledChanged(blurListeners.isCrossWindowBlurEnabled());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAppsView.getAppsStore().removeUpdateListener(this);
        android.view.CrossWindowBlurListeners.getInstance().removeListener(mBlurListener);
        onBlurEnabledChanged(false);
        if (mBluenixxWidthAnimator != null) {
            mBluenixxWidthAnimator.cancel();
            mBluenixxWidthAnimator = null;
        }
        if (mObservedList != null) {
            mObservedList.removeOnScrollListener(mScrollListener);
            mObservedList = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Update the width to match the grid padding
        DeviceProfile dp = mLauncher.getDeviceProfile();
        int myRequestedWidth = getSize(widthMeasureSpec);
        int rowWidth = myRequestedWidth - mAppsView.getActiveRecyclerView().getPaddingLeft()
                - mAppsView.getActiveRecyclerView().getPaddingRight();

        int cellWidth = DeviceProfile.calculateCellWidth(rowWidth,
                dp.getWorkspaceProfile().getCellLayoutBorderSpacePx().x,
                dp.getHotseatProfile().getNumShownIcons());
        int iconVisibleSize =
                Math.round(ICON_VISIBLE_AREA_FACTOR * dp.getWorkspaceProfile().getIconSizePx());
        int iconPadding = cellWidth - iconVisibleSize;

        int myWidth = rowWidth - iconPadding + getPaddingLeft() + getPaddingRight();
        myWidth = Math.min(myWidth, mMaxBarWidth);
        myWidth = (int) (myWidth * mBluenixxWidthExpansion);

        myWidth = Math.min(myWidth, myRequestedWidth
                - 2 * (int) (16 * getResources().getDisplayMetrics().density));
        super.onMeasure(makeMeasureSpec(myWidth, EXACTLY), heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Shift the widget horizontally so that its centered in the parent (b/63428078)
        View parent = (View) getParent();
        int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        int myWidth = right - left;
        int expectedLeft = parent.getPaddingLeft() + (availableWidth - myWidth) / 2;
        int shift = expectedLeft - left;
        setTranslationX(shift);
    }

    private void onBlurEnabledChanged(boolean enabled) {
        if (mBlurEnabled == enabled) return;
        mBlurEnabled = enabled;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mClipPath.reset();
        mClipPath.addRoundRect(0f, 0f, w, h, h / 2f, h / 2f, android.graphics.Path.Direction.CW);
    }

    @Override
    public void draw(android.graphics.Canvas canvas) {
        drawBackdropBlur(canvas);
        super.draw(canvas);
    }

    /** Bluenixx: blurs the app list content that scrolls under the search bar. */
    private void drawBackdropBlur(android.graphics.Canvas canvas) {
        if (!mBlurEnabled || mAppsView == null || !canvas.isHardwareAccelerated()
                || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        View list = mAppsView.getActiveRecyclerView();
        if (list == null || list.getWidth() <= 0) {
            return;
        }
        if (list instanceof androidx.recyclerview.widget.RecyclerView && list != mObservedList) {
            if (mObservedList != null) {
                mObservedList.removeOnScrollListener(mScrollListener);
            }
            mObservedList = (androidx.recyclerview.widget.RecyclerView) list;
            mObservedList.addOnScrollListener(mScrollListener);
        }
        getLocationInWindow(mBarLoc);
        list.getLocationInWindow(mListLoc);
        final int w = getWidth();
        final int h = getHeight();
        boolean effectNeeded = mBlurRadiusPx < 0f;
        if (effectNeeded) {
            mBlurRadiusPx = getResources()
                    .getDimensionPixelSize(R.dimen.all_apps_search_bar_blur_radius);
        }

        mBlurNode.setPosition(0, 0, w, h);
        android.graphics.RecordingCanvas rc = mBlurNode.beginRecording(w, h);
        rc.translate(mListLoc[0] - mBarLoc[0], mListLoc[1] - mBarLoc[1]);
        list.draw(rc);
        mBlurNode.endRecording();
        if (effectNeeded) {
            // The blur effect is constant, so create it once instead of on every frame.
            mBlurNode.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    mBlurRadiusPx, mBlurRadiusPx, android.graphics.Shader.TileMode.CLAMP));
        }

        int base = com.android.launcher3.util.Themes.getAttrColor(
                getContext(), R.attr.allappsHeaderProtectionColor);
        int save = canvas.save();
        canvas.clipPath(mClipPath);
        // opaque panel-colored backing hides the sharp icons that sit under the bar
        canvas.drawColor(base | 0xFF000000);
        canvas.drawRenderNode(mBlurNode);
        canvas.restoreToCount(save);
    }

    /** Bluenixx: lupa fixa no inicio da barra. */
    private void drawSearchIcon(android.graphics.Canvas canvas) {
        if (mSearchIcon == null) {
            mSearchIcon = getContext().getDrawable(R.drawable.ic_allapps_search);
            if (mSearchIcon == null) return;
        }
        int size = mSearchIcon.getIntrinsicWidth();
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        int top = (getHeight() - size) / 2;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int left = (rtl ? getWidth() - margin - size : margin) + getScrollX();
        mSearchIcon.setBounds(left, top, left + size, top + size);
        mSearchIcon.draw(canvas);
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        int save = canvas.save();
        CharSequence hint = getHint();
        if (length() == 0 && hint != null) {
            // Bluenixx: hint centralizado na barra inteira (a lupa fica no canto)
            float hintWidth = getPaint().measureText(hint, 0, hint.length());
            float dx = Math.max(0f, (getWidth() - hintWidth) / 2f - getPaddingStart());
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            canvas.translate(rtl ? -dx : dx, 0);
        }
        super.onDraw(canvas);
        canvas.restoreToCount(save);
        drawSearchIcon(canvas);
        drawOptionsIcon(canvas);
    }

    /** Draws the drawer actions affordance at the trailing edge of the search field. */
    private void drawOptionsIcon(android.graphics.Canvas canvas) {
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(getCurrentTextColor());
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        float cx = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? getPaddingStart() / 2f : getWidth() - getPaddingEnd() / 2f;
        float cy = getHeight() / 2f;
        float radius = 1.5f * getResources().getDisplayMetrics().density;
        for (int i = -1; i <= 1; i++) {
            canvas.drawCircle(cx, cy + i * 6f * getResources().getDisplayMetrics().density,
                    radius, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float edge = 48f * getResources().getDisplayMetrics().density;
            boolean onOptions = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? event.getX() <= edge : event.getX() >= getWidth() - edge;
            if (onOptions) {
                showDrawerOptions();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void showDrawerOptions() {
        PopupMenu menu = new PopupMenu(getContext(), this);
        menu.getMenu().add(0, 1, 0, R.string.drawer_edit_apps);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showAppSelectionDialog();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showAppSelectionDialog() {
        AppInfo[] apps = mAppsView.getAppsStore().getApps();
        java.util.ArrayList<AppInfo> selectable = new java.util.ArrayList<>();
        java.util.ArrayList<CharSequence> labels = new java.util.ArrayList<>();
        for (AppInfo app : apps) {
            if (app.componentName != null && app.user != null) {
                selectable.add(app);
                labels.add(app.title == null ? app.componentName.getPackageName() : app.title);
            }
        }
        if (selectable.isEmpty()) {
            Toast.makeText(getContext(), R.string.drawer_no_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean[] checked = new boolean[selectable.size()];
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.drawer_select_apps)
                .setMultiChoiceItems(labels.toArray(new CharSequence[0]), checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.drawer_create_folder, (dialog, which) -> {
                    java.util.ArrayList<AppInfo> selected = selectedApps(selectable, checked);
                    if (selected.size() < 2) {
                        Toast.makeText(getContext(), R.string.drawer_folder_needs_two_apps,
                                Toast.LENGTH_LONG).show();
                    } else {
                        promptFolderName(selected);
                    }
                })
                .setPositiveButton(R.string.drawer_uninstall, (dialog, which) -> {
                    java.util.ArrayList<AppInfo> selected = selectedApps(selectable, checked);
                    if (selected.isEmpty()) {
                        Toast.makeText(getContext(), R.string.drawer_select_at_least_one,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        confirmUninstall(selected);
                    }
                }).show();
    }

    private java.util.ArrayList<AppInfo> selectedApps(List<AppInfo> apps, boolean[] checked) {
        java.util.ArrayList<AppInfo> selected = new java.util.ArrayList<>();
        for (int i = 0; i < apps.size(); i++) if (checked[i]) selected.add(apps.get(i));
        return selected;
    }

    private void confirmUninstall(java.util.ArrayList<AppInfo> selected) {
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.drawer_uninstall)
                .setMessage(getResources().getString(R.string.drawer_uninstall_confirm, selected.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.drawer_uninstall, (dialog, which) -> {
                    // Android requires a separate system confirmation for each package.
                    for (AppInfo app : selected) {
                        if (app.componentName == null) continue;
                        Intent intent = new Intent(Intent.ACTION_DELETE,
                                android.net.Uri.parse("package:" + app.componentName.getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            getContext().startActivity(intent);
                        } catch (android.content.ActivityNotFoundException e) {
                            Toast.makeText(getContext(), R.string.drawer_uninstall_unavailable,
                                    Toast.LENGTH_SHORT).show();
                            break;
                        }
                    }
                }).show();
    }

    private void promptFolderName(java.util.ArrayList<AppInfo> selected) {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setHint(R.string.drawer_folder_name_hint);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.drawer_create_folder)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(getContext(), R.string.drawer_folder_name_required,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    saveDrawerFolder(name, selected);
                }).show();
    }

    /** Persists folder membership so it survives launcher restarts. */
    private void saveDrawerFolder(String name, java.util.ArrayList<AppInfo> selected) {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(
                DRAWER_PREFS, Context.MODE_PRIVATE);
        JSONArray folders;
        try {
            folders = new JSONArray(prefs.getString("folders", "[]"));
        } catch (JSONException e) {
            folders = new JSONArray();
        }
        JSONArray packages = new JSONArray();
        for (AppInfo app : selected) {
            if (app.componentName != null) packages.put(app.componentName.flattenToString());
        }
        org.json.JSONObject folder = new org.json.JSONObject();
        try {
            folder.put("name", name);
            folder.put("apps", packages);
            folders.put(folder);
            prefs.edit().putString("folders", folders.toString()).apply();
            Toast.makeText(getContext(), R.string.drawer_folder_saved, Toast.LENGTH_LONG).show();
        } catch (JSONException e) {
            Toast.makeText(getContext(), R.string.drawer_folder_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void initializeSearch(ActivityAllAppsContainerView<?> appsView) {
        mAppsView = appsView;
        mSearchBarController.initialize(
                new DefaultAppSearchAlgorithm(getContext(), mLauncher.getUiExecutor(), true),
                this, mLauncher, this);
    }

    @Override
    public void onAppsUpdated() {
        mSearchBarController.refreshSearchResult();
    }

    @Override
    public void resetSearch() {
        mSearchBarController.reset();
    }

    @Override
    public void focusSearchField() {
        mSearchBarController.focusSearchField();
    }

    @Override
    public boolean isSearchQueryEmpty() {
        String query = Utilities.trim(getEditableText().toString());
        return query.isEmpty();
    }

    @Override
    public void preDispatchKeyEvent(KeyEvent event) {
        // Determine if the key event was actual text, if so, focus the search bar and then dispatch
        // the key normally so that it can process this key event
        if (!mSearchBarController.isSearchFieldFocused() &&
                event.getAction() == KeyEvent.ACTION_DOWN) {
            final int unicodeChar = event.getUnicodeChar();
            final boolean isKeyNotWhitespace = unicodeChar > 0 &&
                    !Character.isWhitespace(unicodeChar) && !Character.isSpaceChar(unicodeChar);
            if (isKeyNotWhitespace) {
                boolean gotKey = TextKeyListener.getInstance().onKeyDown(this, mSearchQueryBuilder,
                        event.getKeyCode(), event);
                if (gotKey && mSearchQueryBuilder.length() > 0) {
                    mSearchBarController.focusSearchField();
                }
            }
        }
    }

    @Override
    public void onSearchResult(String query, ArrayList<AdapterItem> items) {
        if (query.equalsIgnoreCase(getContext().getString(R.string.private_space_label))) {
            privateSpaceQuery();
            return;
        }
        if (items != null) {
            mAppsView.setSearchResults(items);
        }
    }

    @Override
    public void clearSearchResult() {
        // Clear the search query
        mSearchQueryBuilder.clear();
        mSearchQueryBuilder.clearSpans();
        Selection.setSelection(mSearchQueryBuilder, 0);
        mAppsView.onClearSearchResult();
    }

    private void animateBluenixxWidth(boolean imeVisible) {
        if (mBluenixxImeVisible == imeVisible) {
            return;
        }
        mBluenixxImeVisible = imeVisible;

        if (mBluenixxWidthAnimator != null) {
            mBluenixxWidthAnimator.cancel();
        }

        float target = imeVisible ? 1.0f : 0.88f;
        mBluenixxWidthAnimator = android.animation.ValueAnimator.ofFloat(
                mBluenixxWidthExpansion, target);
        mBluenixxWidthAnimator.setDuration(220);
        mBluenixxWidthAnimator.addUpdateListener(animator -> {
            mBluenixxWidthExpansion = (float) animator.getAnimatedValue();
            requestLayout();
        });
        mBluenixxWidthAnimator.start();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        boolean imeVisible = insets.isVisible(WindowInsets.Type.ime());
        animateBluenixxWidth(imeVisible);

        // The bar already rests above the nav bar, and the IME inset includes the nav bar.
        int ime = imeVisible ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
        int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        setTranslationY(-Math.max(0, ime - nav));
        return super.onApplyWindowInsets(insets);
    }

    @Override
    public void setInsets(Rect insets) {
        MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
        if (mAppsView.getSearchUiDelegate().isSearchBarFloating()
                || mLauncher.getDeviceProfile().getDeviceProperties().isLargeScreen()) {
            mlp.topMargin = insets.top;
        }
        requestLayout();
    }

    @Override
    public ExtendedEditText getEditText() {
        return this;
    }

    private void privateSpaceQuery() {
        PrivateProfileManager privateProfileManager = mAppsView.getPrivateProfileManager();
        if (privateProfileManager.isPrivateSpaceHidden()) {
            privateProfileManager.setQuietMode(false);
        } else if (!mAppsView.hasPrivateProfile()) {
            final Intent privateSpaceSettingsIntent =
                    ApiWrapper.INSTANCE.get(getContext()).getPrivateSpaceSettingsIntent();
            if (privateSpaceSettingsIntent != null) {
                mLauncher.startActivitySafely(mAppsView, privateSpaceSettingsIntent, null);
            }
        }
    }
}
