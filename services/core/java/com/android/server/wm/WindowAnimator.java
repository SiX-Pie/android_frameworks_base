/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_TRACE;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowSurfacePlacer.SET_ORIENTATION_CHANGE_COMPLETE;
import static com.android.server.wm.WindowSurfacePlacer.SET_UPDATE_ROTATION;

import android.content.Context;
import android.os.Trace;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.WindowManagerPolicy;

import com.android.internal.view.SurfaceFlingerVsyncChoreographer;
import com.android.server.AnimationThread;

import java.io.PrintWriter;

/**
 * Singleton class that carries out the animations and Surface operations in a separate task
 * on behalf of WindowManagerService.
 */
public class WindowAnimator {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowAnimator" : TAG_WM;

    final WindowManagerService mService;
    final Context mContext;
    final WindowManagerPolicy mPolicy;
    private final WindowSurfacePlacer mWindowPlacerLocked;

    /** Is any window animating? */
    private boolean mAnimating;

    /** Is any app window animating? */
    boolean mAppWindowAnimating;

    final Choreographer.FrameCallback mAnimationFrameCallback;

    /** Time of current animation step. Reset on each iteration */
    long mCurrentTime;

    /** Skip repeated AppWindowTokens initialization. Note that AppWindowsToken's version of this
     * is a long initialized to Long.MIN_VALUE so that it doesn't match this value on startup. */
    int mAnimTransactionSequence;

    /** Window currently running an animation that has requested it be detached
     * from the wallpaper.  This means we need to ensure the wallpaper is
     * visible behind it in case it animates in a way that would allow it to be
     * seen. If multiple windows satisfy this, use the lowest window. */
    WindowState mWindowDetachedWallpaper = null;

    int mBulkUpdateParams = 0;
    Object mLastWindowFreezeSource;

    SparseArray<DisplayContentsAnimator> mDisplayContentsAnimators = new SparseArray<>(2);

    boolean mInitialized = false;

    // When set to true the animator will go over all windows after an animation frame is posted and
    // check if some got replaced and can be removed.
    private boolean mRemoveReplacedWindows = false;

    private long mCurrentFrameTime;
    private final Runnable mAnimationTick;
    private final SurfaceFlingerVsyncChoreographer mSfChoreographer;

    private Choreographer mChoreographer;

    /**
     * Indicates whether we have an animation frame callback scheduled, which will happen at
     * vsync-app and then schedule the animation tick at the right time (vsync-sf).
     */
    private boolean mAnimationFrameCallbackScheduled;

    /**
     * Indicates whether we have an animation tick scheduled. The tick is the thing that actually
     * executes the animation step, which will happen at vsync-sf.
     */
    private boolean mAnimationTickScheduled;

    WindowAnimator(final WindowManagerService service) {
        mService = service;
        mContext = service.mContext;
        mPolicy = service.mPolicy;
        mWindowPlacerLocked = service.mWindowPlacerLocked;
        AnimationThread.getHandler().runWithScissors(
                () -> mChoreographer = Choreographer.getInstance(), 0 /* timeout */);

        // TODO: Multi-display: If displays have different vsync tick, have a separate tick per
        // display.
        mSfChoreographer = new SurfaceFlingerVsyncChoreographer(AnimationThread.getHandler(),
                mService.getDefaultDisplayContentLocked().getDisplay(), mChoreographer);
        mAnimationTick = () -> {
            synchronized (mService.mWindowMap) {
                mAnimationTickScheduled = false;
                animateLocked(mCurrentFrameTime);
            }
        };
        mAnimationFrameCallback = frameTimeNs -> {
            synchronized (mService.mWindowMap) {
                mCurrentFrameTime = frameTimeNs;
                mAnimationFrameCallbackScheduled = false;
                if (mAnimationTickScheduled) {
                    return;
                }
                mAnimationTickScheduled = true;
                mSfChoreographer.scheduleAtSfVsync(mAnimationTick);
            }
        };
    }

    void addDisplayLocked(final int displayId) {
        // Create the DisplayContentsAnimator object by retrieving it.
        getDisplayContentsAnimatorLocked(displayId);
        if (displayId == DEFAULT_DISPLAY) {
            mInitialized = true;
        }
    }

    void removeDisplayLocked(final int displayId) {
        final DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.get(displayId);
        if (displayAnimator != null) {
            if (displayAnimator.mScreenRotationAnimation != null) {
                displayAnimator.mScreenRotationAnimation.kill();
                displayAnimator.mScreenRotationAnimation = null;
            }
        }

        mDisplayContentsAnimators.delete(displayId);
    }

    /** Locked on mService.mWindowMap. */
    private void animateLocked(long frameTimeNs) {
        if (!mInitialized) {
            return;
        }

        mCurrentTime = frameTimeNs / TimeUtils.NANOS_PER_MS;
        mBulkUpdateParams = SET_ORIENTATION_CHANGE_COMPLETE;
        boolean wasAnimating = mAnimating;
        setAnimating(false);
        mAppWindowAnimating = false;
        if (DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: entry time=" + mCurrentTime);
        }

        if (SHOW_TRANSACTIONS) Slog.i(TAG, ">>> OPEN TRANSACTION animateLocked");
        mService.openSurfaceTransaction();
        SurfaceControl.setAnimationTransaction();
        try {
            final AccessibilityController accessibilityController =
                    mService.mAccessibilityController;
            final int numDisplays = mDisplayContentsAnimators.size();
            for (int i = 0; i < numDisplays; i++) {
                final int displayId = mDisplayContentsAnimators.keyAt(i);
                final DisplayContent dc = mService.mRoot.getDisplayContentOrCreate(displayId);
                dc.stepAppWindowsAnimation(mCurrentTime);
                DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.valueAt(i);

                final ScreenRotationAnimation screenRotationAnimation =
                        displayAnimator.mScreenRotationAnimation;
                if (screenRotationAnimation != null && screenRotationAnimation.isAnimating()) {
                    if (screenRotationAnimation.stepAnimationLocked(mCurrentTime)) {
                        setAnimating(true);
                    } else {
                        mBulkUpdateParams |= SET_UPDATE_ROTATION;
                        screenRotationAnimation.kill();
                        displayAnimator.mScreenRotationAnimation = null;

                        //TODO (multidisplay): Accessibility supported only for the default display.
                        if (accessibilityController != null && dc.isDefaultDisplay) {
                            // We just finished rotation animation which means we did not announce
                            // the rotation and waited for it to end, announce now.
                            accessibilityController.onRotationChangedLocked(
                                    mService.getDefaultDisplayContentLocked());
                        }
                    }
                }

                // Update animations of all applications, including those
                // associated with exiting/removed apps
                ++mAnimTransactionSequence;
                dc.updateWindowsForAnimator(this);
                dc.updateWallpaperForAnimator(this);
                dc.prepareWindowSurfaces();
            }

            for (int i = 0; i < numDisplays; i++) {
                final int displayId = mDisplayContentsAnimators.keyAt(i);
                final DisplayContent dc = mService.mRoot.getDisplayContentOrCreate(displayId);

                dc.checkAppWindowsReadyToShow();

                final ScreenRotationAnimation screenRotationAnimation =
                        mDisplayContentsAnimators.valueAt(i).mScreenRotationAnimation;
                if (screenRotationAnimation != null) {
                    screenRotationAnimation.updateSurfacesInTransaction();
                }

                orAnimating(dc.animateDimLayers());
                orAnimating(dc.getDockedDividerController().animate(mCurrentTime));
                //TODO (multidisplay): Magnification is supported only for the default display.
                if (accessibilityController != null && dc.isDefaultDisplay) {
                    accessibilityController.drawMagnifiedRegionBorderIfNeededLocked();
                }
            }

            if (mService.mDragState != null) {
                mAnimating |= mService.mDragState.stepAnimationLocked(mCurrentTime);
            }

            if (mAnimating) {
                mService.scheduleAnimationLocked();
            }

            if (mService.mWatermark != null) {
                mService.mWatermark.drawIfNeeded();
            }
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "Unhandled exception in Window Manager", e);
        } finally {
            mService.closeSurfaceTransaction();
            if (SHOW_TRANSACTIONS) Slog.i(TAG, "<<< CLOSE TRANSACTION animateLocked");
        }

        boolean hasPendingLayoutChanges = mService.mRoot.hasPendingLayoutChanges(this);
        boolean doRequest = false;
        if (mBulkUpdateParams != 0) {
            doRequest = mService.mRoot.copyAnimToLayoutParams();
        }

        if (hasPendingLayoutChanges || doRequest) {
            mWindowPlacerLocked.requestTraversal();
        }

        if (mAnimating && !wasAnimating && Trace.isTagEnabled(Trace.TRACE_TAG_WINDOW_MANAGER)) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "animating", 0);
        }

        if (!mAnimating && wasAnimating) {
            mWindowPlacerLocked.requestTraversal();
            if (Trace.isTagEnabled(Trace.TRACE_TAG_WINDOW_MANAGER)) {
                Trace.asyncTraceEnd(Trace.TRACE_TAG_WINDOW_MANAGER, "animating", 0);
            }
        }

        if (mRemoveReplacedWindows) {
            mService.mRoot.removeReplacedWindows();
            mRemoveReplacedWindows = false;
        }

        mService.stopUsingSavedSurfaceLocked();
        mService.destroyPreservedSurfaceLocked();
        mService.mWindowPlacerLocked.destroyPendingSurfaces();

        if (DEBUG_WINDOW_TRACE) {
            Slog.i(TAG, "!!! animate: exit mAnimating=" + mAnimating
                    + " mBulkUpdateParams=" + Integer.toHexString(mBulkUpdateParams)
                    + " mPendingLayoutChanges(DEFAULT_DISPLAY)="
                    + Integer.toHexString(getPendingLayoutChanges(DEFAULT_DISPLAY)));
        }
    }

    private static String bulkUpdateParamsToString(int bulkUpdateParams) {
        StringBuilder builder = new StringBuilder(128);
        if ((bulkUpdateParams & WindowSurfacePlacer.SET_UPDATE_ROTATION) != 0) {
            builder.append(" UPDATE_ROTATION");
        }
        if ((bulkUpdateParams & WindowSurfacePlacer.SET_WALLPAPER_MAY_CHANGE) != 0) {
            builder.append(" WALLPAPER_MAY_CHANGE");
        }
        if ((bulkUpdateParams & WindowSurfacePlacer.SET_FORCE_HIDING_CHANGED) != 0) {
            builder.append(" FORCE_HIDING_CHANGED");
        }
        if ((bulkUpdateParams & WindowSurfacePlacer.SET_ORIENTATION_CHANGE_COMPLETE) != 0) {
            builder.append(" ORIENTATION_CHANGE_COMPLETE");
        }
        if ((bulkUpdateParams & WindowSurfacePlacer.SET_TURN_ON_SCREEN) != 0) {
            builder.append(" TURN_ON_SCREEN");
        }
        return builder.toString();
    }

    public void dumpLocked(PrintWriter pw, String prefix, boolean dumpAll) {
        final String subPrefix = "  " + prefix;
        final String subSubPrefix = "  " + subPrefix;

        for (int i = 0; i < mDisplayContentsAnimators.size(); i++) {
            pw.print(prefix); pw.print("DisplayContentsAnimator #");
                    pw.print(mDisplayContentsAnimators.keyAt(i));
                    pw.println(":");
            final DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.valueAt(i);
            final DisplayContent dc =
                    mService.mRoot.getDisplayContentOrCreate(mDisplayContentsAnimators.keyAt(i));
            dc.dumpWindowAnimators(pw, subPrefix);
            if (displayAnimator.mScreenRotationAnimation != null) {
                pw.print(subPrefix); pw.println("mScreenRotationAnimation:");
                displayAnimator.mScreenRotationAnimation.printTo(subSubPrefix, pw);
            } else if (dumpAll) {
                pw.print(subPrefix); pw.println("no ScreenRotationAnimation ");
            }
            pw.println();
        }

        pw.println();

        if (dumpAll) {
            pw.print(prefix); pw.print("mAnimTransactionSequence=");
                    pw.print(mAnimTransactionSequence);
            pw.print(prefix); pw.print("mCurrentTime=");
                    pw.println(TimeUtils.formatUptime(mCurrentTime));
        }
        if (mBulkUpdateParams != 0) {
            pw.print(prefix); pw.print("mBulkUpdateParams=0x");
                    pw.print(Integer.toHexString(mBulkUpdateParams));
                    pw.println(bulkUpdateParamsToString(mBulkUpdateParams));
        }
        if (mWindowDetachedWallpaper != null) {
            pw.print(prefix); pw.print("mWindowDetachedWallpaper=");
                pw.println(mWindowDetachedWallpaper);
        }
    }

    int getPendingLayoutChanges(final int displayId) {
        if (displayId < 0) {
            return 0;
        }
        final DisplayContent displayContent = mService.mRoot.getDisplayContentOrCreate(displayId);
        return (displayContent != null) ? displayContent.pendingLayoutChanges : 0;
    }

    void setPendingLayoutChanges(final int displayId, final int changes) {
        if (displayId < 0) {
            return;
        }
        final DisplayContent displayContent = mService.mRoot.getDisplayContentOrCreate(displayId);
        if (displayContent != null) {
            displayContent.pendingLayoutChanges |= changes;
        }
    }

    private DisplayContentsAnimator getDisplayContentsAnimatorLocked(int displayId) {
        DisplayContentsAnimator displayAnimator = mDisplayContentsAnimators.get(displayId);
        if (displayAnimator == null) {
            displayAnimator = new DisplayContentsAnimator();
            mDisplayContentsAnimators.put(displayId, displayAnimator);
        }
        return displayAnimator;
    }

    void setScreenRotationAnimationLocked(int displayId, ScreenRotationAnimation animation) {
        if (displayId >= 0) {
            getDisplayContentsAnimatorLocked(displayId).mScreenRotationAnimation = animation;
        }
    }

    ScreenRotationAnimation getScreenRotationAnimationLocked(int displayId) {
        if (displayId < 0) {
            return null;
        }
        return getDisplayContentsAnimatorLocked(displayId).mScreenRotationAnimation;
    }

    void requestRemovalOfReplacedWindows(WindowState win) {
        mRemoveReplacedWindows = true;
    }

    void scheduleAnimation() {
        if (!mAnimationFrameCallbackScheduled) {
            mAnimationFrameCallbackScheduled = true;
            mChoreographer.postFrameCallback(mAnimationFrameCallback);
        }
    }

    private class DisplayContentsAnimator {
        ScreenRotationAnimation mScreenRotationAnimation = null;
    }

    boolean isAnimating() {
        return mAnimating;
    }

    boolean isAnimationScheduled() {
        return mAnimationFrameCallbackScheduled || mAnimationTickScheduled;
    }

    Choreographer getChoreographer() {
        return mChoreographer;
    }

    void setAnimating(boolean animating) {
        mAnimating = animating;
    }

    void orAnimating(boolean animating) {
        mAnimating |= animating;
    }
}
