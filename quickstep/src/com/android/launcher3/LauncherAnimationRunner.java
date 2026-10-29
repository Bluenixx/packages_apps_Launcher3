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
package com.android.launcher3;

import static com.android.launcher3.Utilities.postAsyncCallback;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.launcher3.util.window.RefreshRateTracker.getSingleFrameMs;
import static com.android.systemui.shared.recents.utilities.Utilities.postAtFrontOfQueueAsynchronously;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationTarget;

import androidx.annotation.BinderThread;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.systemui.animation.RemoteAnimationDelegate;
import com.android.systemui.animation.RemoteAnimationRunnerCompat;

import java.lang.ref.WeakReference;

/**
 * This class is needed to wrap any animation runner that is a part of the
 * RemoteAnimationDefinition:
 * - Launcher creates a new instance of the LauncherAppTransitionManagerImpl whenever it is
 *   created, which in turn registers a new definition
 * - When the definition is registered, window manager retains a strong binder reference to the
 *   runner passed in
 * - If the Launcher activity is recreated, the new definition registered will replace the old
 *   reference in the system's activity record, but until the system server is GC'd, the binder
 *   reference will still exist, which references the runner in the Launcher process, which
 *   references the (old) Launcher activity through this class
 *
 * Instead we make the runner provided to the definition static only holding a weak reference to
 * the runner implementation.  When this animation manager is destroyed, we remove the Launcher
 * reference to the runner, leaving only the weak ref from the runner.
 */
public class LauncherAnimationRunner extends RemoteAnimationRunnerCompat {

    private static final RemoteAnimationFactory DEFAULT_FACTORY =
            (transit, appTargets, wallpaperTargets, nonAppTargets, result) ->
                    result.setAnimation(null, null);

    private final Handler mHandler;
    private final boolean mStartAtFrontOfQueue;
    private final WeakReference<RemoteAnimationFactory> mFactory;

    private AnimationResult mAnimationResult;

    /**
     * @param startAtFrontOfQueue If true, the animation start will be posted at the front of the
     *                            queue to minimize latency.
     */
    public LauncherAnimationRunner(Handler handler, RemoteAnimationFactory factory,
            boolean startAtFrontOfQueue) {
        mHandler = handler;
        mFactory = new WeakReference<>(factory);
        mStartAtFrontOfQueue = startAtFrontOfQueue;
    }

    // Called only in S+ platform
    @BinderThread
    public void onAnimationStart(
            int transit,
            RemoteAnimationTarget[] appTargets,
            RemoteAnimationTarget[] wallpaperTargets,
            RemoteAnimationTarget[] nonAppTargets,
            Runnable runnable) {
        Runnable r = () -> {
            finishExistingAnimation();
            mAnimationResult = new AnimationResult(() -> mAnimationResult = null, runnable);
            getFactory().onAnimationStart(transit, appTargets, wallpaperTargets, nonAppTargets,
                    mAnimationResult);
        };
        if (mStartAtFrontOfQueue) {
            postAtFrontOfQueueAsynchronously(mHandler, r);
        } else {
            postAsyncCallback(mHandler, r);
        }
    }

    private RemoteAnimationFactory getFactory() {
        RemoteAnimationFactory factory = mFactory.get();
        return factory != null ? factory : DEFAULT_FACTORY;
    }

    @UiThread
    private void finishExistingAnimation() {
        if (mAnimationResult != null) {
            // A new WindowManager transition supersedes the previous one. Finishing only the
            // remote callback leaves the old AnimatorSet running against stale SurfaceControl
            // leashes, so both animations can write transforms for the same frame. Cancel the
            // previous animation before releasing its transition.
            AnimationResult previousResult = mAnimationResult;
            mAnimationResult = null;
            previousResult.cancel();
        }
    }

    /**
     * Called by the system
     */
    @BinderThread
    @Override
    public void onAnimationCancelled() {
        postAsyncCallback(mHandler, () -> {
            finishExistingAnimation();
            getFactory().onAnimationCancelled();
        });
    }

    /**
     * Used by RemoteAnimationFactory implementations to run the actual animation and its lifecycle
     * callbacks.
     */
    public static final class AnimationResult extends IRemoteAnimationFinishedCallback.Stub {

        private final Runnable mSyncFinishRunnable;
        private final Runnable mASyncFinishRunnable;

        private Runnable mOnCompleteCallback;
        private AnimatorSet mAnimation;
        private boolean mFinished = false;
        private boolean mInitialized = false;

        private AnimationResult(Runnable syncFinishRunnable, Runnable asyncFinishRunnable) {
            mSyncFinishRunnable = syncFinishRunnable;
            mASyncFinishRunnable = asyncFinishRunnable;
        }

        @UiThread
        private void finish() {
            complete(false);
        }

        /** Cancels an animation whose surfaces are being replaced by a newer transition. */
        @UiThread
        private void cancel() {
            complete(true);
        }

        private void complete(boolean cancelAnimation) {
            if (mFinished) return;
            // Mark finished before cancel(): Animator.cancel() synchronously dispatches
            // onAnimationEnd(), which must not complete this result a second time.
            mFinished = true;
            if (cancelAnimation && mAnimation != null && mAnimation.isStarted()) {
                mAnimation.cancel();
            }
            mSyncFinishRunnable.run();
            UI_HELPER_EXECUTOR.execute(() -> {
                mASyncFinishRunnable.run();
                if (mOnCompleteCallback != null) {
                    MAIN_EXECUTOR.execute(mOnCompleteCallback);
                }
            });
        }

        @UiThread
        public void setAnimation(AnimatorSet animation, Context context) {
            setAnimation(animation, context, null, true);
        }

        /**
         * Sets the animation to play for this app launch
         * @param skipFirstFrame Iff true, we skip the first frame of the animation.
         *                       We set to false when skipping first frame causes jank.
         */
        @UiThread
        public void setAnimation(AnimatorSet animation, Context context,
                @Nullable Runnable onCompleteCallback, boolean skipFirstFrame) {
            if (mInitialized) {
                throw new IllegalStateException("Animation already initialized");
            }
            mInitialized = true;
            mOnCompleteCallback = onCompleteCallback;
            if (animation == null) {
                finish();
            } else if (mFinished) {
                // The transition was superseded before its animation was initialized. Do not
                // start stale surface transactions after WindowManager has moved on.
                if (mOnCompleteCallback != null) {
                    mOnCompleteCallback.run();
                }
            } else {
                mAnimation = animation;
                // Start the animation
                animation.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        finish();
                    }
                });
                if (skipFirstFrame) {
                    // Because t=0 has the app icon in its original spot, we can skip the
                    // first frame and have the same movement one frame earlier.
                    Log.d("b/311077782", "LauncherAnimationRunner.setAnimation");
                    animation.setCurrentPlayTime(
                            Math.min(getSingleFrameMs(context), animation.getTotalDuration()));
                }
                animation.start();
            }
        }

        /**
         * When used as a simple IRemoteAnimationFinishedCallback, this method is used to run the
         * animation finished runnable.
         */
        @Override
        public void onAnimationFinished() {
            mASyncFinishRunnable.run();
        }
    }

    /**
     * Used with LauncherAnimationRunner as an interface for the runner to call back to the
     * implementation.
     */
    public interface RemoteAnimationFactory extends RemoteAnimationDelegate<AnimationResult> {

        /**
         * Called on the UI thread when the animation targets are received. The implementation must
         * call {@link AnimationResult#setAnimation} with the target animation to be run.
         */
        @Override
        @UiThread
        void onAnimationStart(int transit,
                RemoteAnimationTarget[] appTargets,
                RemoteAnimationTarget[] wallpaperTargets,
                RemoteAnimationTarget[] nonAppTargets,
                LauncherAnimationRunner.AnimationResult result);

        /**
         * Called when the animation is cancelled. This can happen with or without
         * the create being called.
         */
        @Override
        @UiThread
        default void onAnimationCancelled() {}
    }
}
