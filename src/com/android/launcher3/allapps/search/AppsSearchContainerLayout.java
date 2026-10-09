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
    private static final int SEARCH_BAR_BG_ALPHA = 115;
    private final int mMaxBarWidth;
    private android.graphics.drawable.Drawable mBaseBackground;
    private boolean mBlurEnabled;
    private final android.graphics.RenderNode mBlurNode =
            new android.graphics.RenderNode("searchBarBackdropBlur");
    private final android.graphics.Path mClipPath = new android.graphics.Path();
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
        android.graphics.drawable.Drawable bg = getBackground();
        if (bg != null) {
            mBaseBackground = bg.mutate();
            mBaseBackground.setAlpha(SEARCH_BAR_BG_ALPHA);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
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
        final float radius = getResources()
                .getDimensionPixelSize(R.dimen.all_apps_search_bar_blur_radius);

        mBlurNode.setPosition(0, 0, w, h);
        android.graphics.RecordingCanvas rc = mBlurNode.beginRecording(w, h);
        rc.translate(mListLoc[0] - mBarLoc[0], mListLoc[1] - mBarLoc[1]);
        list.draw(rc);
        mBlurNode.endRecording();
        mBlurNode.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                radius, radius, android.graphics.Shader.TileMode.CLAMP));

        int base = com.android.launcher3.util.Themes.getAttrColor(
                getContext(), R.attr.allappsHeaderProtectionColor);
        int save = canvas.save();
        canvas.clipPath(mClipPath);
        // opaque panel-colored backing hides the sharp icons that sit under the bar
        canvas.drawColor(base | 0xFF000000);
        canvas.drawRenderNode(mBlurNode);
        canvas.restoreToCount(save);
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        int save = canvas.save();
        CharSequence hint = getHint();
        android.graphics.drawable.Drawable icon = getCompoundDrawablesRelative()[0];
        if (length() == 0 && hint != null && icon != null) {
            float groupWidth = icon.getIntrinsicWidth() + getCompoundDrawablePadding()
                    + getPaint().measureText(hint, 0, hint.length());
            float usable = getWidth() - getPaddingLeft() - getPaddingRight();
            float dx = Math.max(0f, (usable - groupWidth) / 2f);
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            canvas.translate(rtl ? -dx : dx, 0);
        }
        super.onDraw(canvas);
        canvas.restoreToCount(save);
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

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // The bar already rests above the nav bar, and the IME inset includes the nav bar.
        int ime = insets.isVisible(WindowInsets.Type.ime())
                ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
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
