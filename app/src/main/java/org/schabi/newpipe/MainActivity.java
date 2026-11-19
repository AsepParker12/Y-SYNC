/*
 * Created by Christian Schabesberger on 02.08.16.
 * <p>
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
 * DownloadActivity.java is part of NewPipe.
 * <p>
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.ViewOutlineProvider;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.GravityCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.widget.NestedScrollView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;

import eightbitlab.com.blurview.RenderEffectBlur;

import org.schabi.newpipe.databinding.ActivityMainBinding;
import org.schabi.newpipe.databinding.DrawerHeaderBinding;
import org.schabi.newpipe.databinding.DrawerLayoutBinding;
import org.schabi.newpipe.databinding.InstanceSpinnerLayoutBinding;
import org.schabi.newpipe.databinding.ToolbarLayoutBinding;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.services.peertube.PeertubeInstance;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.fragments.MainFragment;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.fragments.list.channel.ChannelFragment;
import org.schabi.newpipe.local.bookmark.BookmarkFragment;
import org.schabi.newpipe.local.feed.FeedFragment;
import org.schabi.newpipe.local.subscription.SubscriptionFragment;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.fragments.list.comments.CommentRepliesFragment;
import org.schabi.newpipe.fragments.list.search.SearchFragment;
import org.schabi.newpipe.local.feed.notifications.NotificationWorker;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.event.OnKeyDownListener;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.settings.UpdateSettingsFragment;
import org.schabi.newpipe.settings.migration.MigrationManager;
import org.schabi.newpipe.shorts.ShortsFragment;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.KioskTranslator;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PeertubeHelper;
import org.schabi.newpipe.util.PermissionHelper;
import org.schabi.newpipe.util.ReleaseVersionUtil;
import org.schabi.newpipe.util.SerializedCache;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.StateSaver;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.views.FocusOverlayView;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    @SuppressWarnings("ConstantConditions")
    public static final boolean DEBUG = !BuildConfig.BUILD_TYPE.equals("release");

    private ActivityMainBinding mainBinding;
    private DrawerHeaderBinding drawerHeaderBinding;
    private DrawerLayoutBinding drawerLayoutBinding;
    private ToolbarLayoutBinding toolbarLayoutBinding;

    private ActionBarDrawerToggle toggle;

    private boolean servicesShown = false;

    private BroadcastReceiver broadcastReceiver;

    private static final int ITEM_ID_SUBSCRIPTIONS = -1;
    private static final int ITEM_ID_FEED = -2;
    private static final int ITEM_ID_BOOKMARKS = -3;
    private static final int ITEM_ID_DOWNLOADS = -4;
    private static final int ITEM_ID_HISTORY = -5;
    private static final int ITEM_ID_SHORTS = -6;
    private static final int ITEM_ID_SETTINGS = 0;
    private static final int ITEM_ID_DONATION = 1;
    private static final int ITEM_ID_ABOUT = 2;

    private static final int ORDER = 0;
    public static final String KEY_IS_IN_BACKGROUND = "is_in_background";

    private SharedPreferences sharedPreferences;
    private SharedPreferences.Editor sharedPrefEditor;
    /*//////////////////////////////////////////////////////////////////////////
    // Activity's LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (DEBUG) {
            Log.d(TAG, "onCreate() called with: "
                    + "savedInstanceState = [" + savedInstanceState + "]");
        }

        Localization.migrateAppLanguageSettingIfNecessary(getApplicationContext());
        ThemeHelper.setDayNightMode(this);
        ThemeHelper.setTheme(this, ServiceHelper.getSelectedServiceId(this));

        // Fixes text color turning black in dark/black mode:
        // https://github.com/TeamNewPipe/NewPipe/issues/12016
        // For further reference see: https://issuetracker.google.com/issues/37124582
        if (DeviceUtils.supportsWebView()) {
            try {
                new WebView(this);
            } catch (final Throwable e) {
                if (DEBUG) {
                    Log.e(TAG, "Failed to create WebView", e);
                }
            }
        }

        super.onCreate(savedInstanceState);

        // Y-SYNC: personal access gate \u2014 ask for the access code once per install.
        if (!AccessGate.INSTANCE.isUnlocked(this)) {
            startActivity(new Intent(this, LockActivity.class));
            finish();
            return;
        }
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefEditor = sharedPreferences.edit();

        mainBinding = ActivityMainBinding.inflate(getLayoutInflater());
        drawerLayoutBinding = mainBinding.drawerLayout;
        drawerHeaderBinding = DrawerHeaderBinding.bind(drawerLayoutBinding.navigation
                .getHeaderView(0));
        toolbarLayoutBinding = mainBinding.toolbarLayout;
        setContentView(mainBinding.getRoot());

        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            initFragments();
        }

        setSupportActionBar(toolbarLayoutBinding.toolbar);
        try {
            setupDrawer();
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Setting up drawer", e);
        }
        setupBottomNav();
        setupTopGlass();
        if (DeviceUtils.isTv(this)) {
            FocusOverlayView.setupFocusObserver(this);
        }
        openMiniPlayerUponPlayerStarted();

        if (PermissionHelper.checkPostNotificationsPermission(this,
                PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE)) {
            // Schedule worker for checking for new streams and creating corresponding notifications
            // if this is enabled by the user.
            NotificationWorker.initialize(this);
        }
        if (!UpdateSettingsFragment.wasUserAskedForConsent(this)
                && !App.getInstance().isFirstRun()
                && ReleaseVersionUtil.INSTANCE.isReleaseApk()) {
            UpdateSettingsFragment.askForConsentToUpdateChecks(this);
        }

        // ReleaseVersionUtil.INSTANCE.isReleaseApk() will be true only for main official build
        // We want every release build (nightly, nightly-refactor) to show the popup
        if (!DEBUG) {
            showKeepAndroidDialog();
            showApi23RequirementDialog();
        }

        MigrationManager.showUserInfoIfPresent(this);
    }

    @Override
    protected void onPostCreate(final Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        final App app = App.getInstance();

        if (sharedPreferences.getBoolean(app.getString(R.string.update_app_key), true)
                && sharedPreferences
                .getBoolean(app.getString(R.string.update_check_consent_key), true)) {
            // Start the worker which is checking all conditions
            // and eventually searching for a new version.
            NewVersionWorker.enqueueNewVersionCheckingWork(app, false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        sharedPrefEditor.putBoolean(KEY_IS_IN_BACKGROUND, false).apply();
        Log.d(TAG, "App moved to foreground");
    }

    @Override
    protected void onStop() {
        super.onStop();
        sharedPrefEditor.putBoolean(KEY_IS_IN_BACKGROUND, true).apply();
        Log.d(TAG, "App moved to background");
    }
    /*//////////////////////////////////////////////////////////////////////////
    // Bottom navigation (Y-SYNC: floating glass bar, YouTube-style)
    //////////////////////////////////////////////////////////////////////////*/

    private void setupBottomNav() {
        if (DeviceUtils.isTv(this)) {
            // TVs never show the bar: this is a one-time, permanent disable
            // at setup (not a runtime show/hide flip), so GONE is right here.
            mainBinding.bottomBlurFrame.setVisibility(View.GONE);
            return;
        }
        mainBinding.bottomNav.setOnItemSelectedListener(this::onBottomNavItemSelected);
        mainBinding.bottomNav.setOnItemReselectedListener(item -> {
            // Y-SYNC: tapping Menu while it is already checked must still open the drawer.
            if (item.getItemId() == R.id.nav_menu) {
                mainBinding.getRoot().openDrawer(drawerLayoutBinding.navigation);
            }
        });
        getSupportFragmentManager().addOnBackStackChangedListener(
                this::updateBottomNavVisibility);
        // Y-SYNC: after a rotation the activity is recreated and the
        // player fragment in fragment_player_holder is restored
        // asynchronously; while it is missing, updateBottomNavVisibility
        // cannot see a fullscreen player and shows the bar over the
        // video — and no later event corrects that while the user just
        // watches. Re-run the update once the player fragment resumes.
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(@NonNull final FragmentManager fm,
                                                  @NonNull final Fragment fragment) {
                        if (fragment instanceof VideoDetailFragment) {
                            updateBottomNavVisibility();
                        }
                    }
                }, false);
        final BottomSheetBehavior<?> sheetBehavior =
                BottomSheetBehavior.from(mainBinding.fragmentPlayerHolder);
        playerSlideOffset = sheetOffsetForState(sheetBehavior.getState());
        sheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull final View bottomSheet,
                                       final int newState) {
                if (newState != BottomSheetBehavior.STATE_DRAGGING
                        && newState != BottomSheetBehavior.STATE_SETTLING) {
                    playerSlideOffset = sheetOffsetForState(newState);
                }
                updateBottomNavVisibility();
            }

            @Override
            public void onSlide(@NonNull final View bottomSheet,
                                final float slideOffset) {
                playerSlideOffset = slideOffset;
                updateBottomNavVisibility();
            }
        });
        updateBottomNavVisibility();
        mainBinding.bottomNav.post(this::updateBottomNavVisibility);
        setupBottomBlur();
        hideToolbarHamburger();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Top bar (Y-SYNC: real liquid glass — content scrolls behind and blurs)
    //////////////////////////////////////////////////////////////////////////*/

    private final WeakHashMap<View, Boolean> topGlassPaddedViews = new WeakHashMap<>();

    // Y-SYNC: guarded blur setup state (see ensureTopBlurSetup). The
    // recorded size is the wrapper frame's size at the last setupWith.
    private boolean topBlurReady;
    private int topBlurSetupWidth;
    private int topBlurSetupHeight;

    /**
     * Y-SYNC: runs the top bar's setupWith exactly once per wrapper size.
     * A setup at measured size 0 (what the old post()-deferred call still
     * hit: post() on an unattached view runs at attach time, before the
     * first layout) puts BlurView's controller into its zero-size branch
     * — willNotDraw, never initialised — and nothing ever set it up
     * again, so the bar stayed plain until player geometry churn revived
     * the library's own size-change realloc. Only set up when the frame
     * is attached with a real size; re-setup happens solely through the
     * layout-change trigger when the size actually changes.
     */
    private void ensureTopBlurSetup() {
        if (topBlurReady || toolbarLayoutBinding == null) {
            return;
        }
        final View frame = toolbarLayoutBinding.getRoot();
        if (!frame.isAttachedToWindow() || frame.getWidth() <= 0
                || frame.getHeight() <= 0) {
            return;
        }
        setupTopBlur();
        topBlurReady = true;
        topBlurSetupWidth = frame.getWidth();
        topBlurSetupHeight = frame.getHeight();
    }

    private void setupTopBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        toolbarLayoutBinding.topBlur
                .setupWith(mainBinding.getRoot(), new RenderEffectBlur())
                .setFrameClearDrawable(ContextCompat.getDrawable(this,
                        R.drawable.glass_nav_tint))
                .setBlurRadius(18f);
    }

    private void setupTopGlass() {
        // Y-SYNC: the top bar floats like the bottom bar — pill outline.
        // The outline/clip live on the wrapping frame (which also carries
        // the solid fallback background), not on the BlurView itself.
        final View topBarFrame = toolbarLayoutBinding.getRoot();
        final float radiusPx = 32 * getResources().getDisplayMetrics().density;
        topBarFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(final View view, final Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        topBarFrame.setClipToOutline(true);
        // Y-SYNC: guarded first-frame blur setup (see ensureTopBlurSetup):
        // try right away, again at the first drawn frame (when the frame
        // definitely has its real size), and again whenever the frame's
        // size actually changes afterwards. Visibility flips never change
        // the size (the bar hides with INVISIBLE), so they never retrigger
        // a setup — no return of the setup-churn garbage frames.
        topBarFrame.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (topBarFrame.getViewTreeObserver().isAlive()) {
                            topBarFrame.getViewTreeObserver()
                                    .removeOnPreDrawListener(this);
                        }
                        ensureTopBlurSetup();
                        return true;
                    }
                });
        topBarFrame.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            final int width = right - left;
            final int height = bottom - top;
            if (width > 0 && height > 0
                    && (width != topBlurSetupWidth || height != topBlurSetupHeight)) {
                topBlurReady = false;
                ensureTopBlurSetup();
            }
        });
        ensureTopBlurSetup();
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(@NonNull final FragmentManager fm,
                                                      @NonNull final Fragment fragment,
                                                      @NonNull final View view,
                                                      @Nullable final Bundle savedInstanceState) {
                        applyTopGlassPadding(fragment, view);
                    }
                }, true);
    }

    private void applyTopGlassPadding(final Fragment fragment, final View view) {
        // Dialogs float in their own window above the screen: padding
        // their first scroll view only leaves a dead gap at the top of
        // the dialog (e.g. the playlist dialog), so they are skipped.
        if (fragment instanceof DialogFragment) {
            return;
        }
        // The player sheet and the video detail page manage their own top
        // area. The same goes for everything hosted inside the detail page
        // (its tabs, e.g. the comments tab): the sheet covers the top bar
        // when expanded, so an inset would only leave a dead gap there.
        // The same goes for the pages inside a channel page: they already
        // start below the channel header, so an inset would only leave a
        // dead gap in every channel tab.
        if (fragment.getId() == R.id.fragment_player_holder
                || fragment instanceof VideoDetailFragment
                || isHostedInPlayerSheet(fragment)
                || isHostedInChannelPage(fragment)) {
            return;
        }
        if (fragment instanceof ChannelFragment) {
            // The channel page has no single scrolling view to pad: its
            // header (banner, avatar, name, subscriber count, subscribe
            // button) lives in the app bar, which starts at the very top
            // of the screen and would slide under the floating bar. Give
            // the app bar itself the top inset instead, so the whole
            // header starts fully below the bar; the tab strip and the
            // pages below follow the app bar's position.
            final View appBar = view.findViewById(R.id.app_bar_layout);
            if (appBar != null) {
                applyTopGlassInset(appBar, false);
            }
            return;
        }
        if (fragment instanceof SearchFragment) {
            // The search screen stacks two lists in one root: the results
            // list and the suggestions list shown while typing. Both start
            // at the top of the screen, so both need the inset — without
            // it, the first suggestion row slides under the floating bar
            // and its icons peek out at the bar's edges, looking like a
            // second bar stacked behind it.
            applyTopGlassInset(view.findViewById(R.id.items_list), true);
            applyTopGlassInset(view.findViewById(R.id.suggestions_list), true);
            return;
        }
        if (fragment instanceof FeedFragment) {
            // The feed screen has a second top-anchored element besides
            // its list: the refresh banner (refresh_root_view), which is
            // not a scrolling view, so the generic path below never
            // insets it — when it is visible it slides under the floating
            // bar and its refresh icon pokes out at the bar's edge.
            // Inset the banner as well as the list.
            applyTopGlassInset(view.findViewById(R.id.refresh_root_view), false);
            final View feedScrollView = findTopScrollView(view);
            if (feedScrollView != null) {
                applyTopGlassInset(feedScrollView, true);
            }
            return;
        }
        if (fragment instanceof MainFragment) {
            // The main screen's tab strip is top-anchored and not a
            // scrolling view either; with two or more configured tabs it
            // would start under the floating bar. The pages inside its
            // pager are separate fragments and get their own insets via
            // their own view-created callbacks.
            applyTopGlassInset(view.findViewById(R.id.main_tab_layout), false);
            return;
        }
        final View scrollView = findTopScrollView(view);
        if (scrollView == null) {
            return;
        }
        applyTopGlassInset(scrollView, true);
    }

    /**
     * Y-SYNC: adds the floating top bar's height as extra top padding to a
     * view, exactly once per view instance (views are guarded by
     * {@link #topGlassPaddedViews}, so recreated views get their own inset
     * and surviving ones are never padded twice).
     *
     * @param clipToPadding for scrolling views: let content scroll through
     *                      the inset area, behind the glass bar
     */
    private void applyTopGlassInset(@Nullable final View target,
                                    final boolean clipToPadding) {
        if (target == null || topGlassPaddedViews.containsKey(target)) {
            return;
        }
        target.setPadding(target.getPaddingLeft(),
                target.getPaddingTop() + topGlassInsetPx(),
                target.getPaddingRight(), target.getPaddingBottom());
        if (clipToPadding && target instanceof ViewGroup) {
            ((ViewGroup) target).setClipToPadding(false);
        }
        topGlassPaddedViews.put(target, Boolean.TRUE);
    }

    private int topGlassInsetPx() {
        final TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.actionBarSize, typedValue, true);
        return TypedValue.complexToDimensionPixelSize(typedValue.data,
                getResources().getDisplayMetrics())
                + Math.round(18 * getResources().getDisplayMetrics().density);
    }

    private boolean isHostedInPlayerSheet(final Fragment fragment) {
        Fragment parent = fragment.getParentFragment();
        while (parent != null) {
            if (parent instanceof VideoDetailFragment) {
                return true;
            }
            parent = parent.getParentFragment();
        }
        return false;
    }

    private boolean isHostedInChannelPage(final Fragment fragment) {
        Fragment parent = fragment.getParentFragment();
        while (parent != null) {
            if (parent instanceof ChannelFragment) {
                return true;
            }
            parent = parent.getParentFragment();
        }
        return false;
    }

    private View findTopScrollView(final View view) {
        if (view instanceof RecyclerView || view instanceof ScrollView
                || view instanceof NestedScrollView) {
            return view;
        }
        if (view instanceof ViewGroup) {
            if (view.getClass().getName().contains("ViewPager")) {
                return null;
            }
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View found = findTopScrollView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // Y-SYNC: whether the top bar currently shows its solid (non-glass) style.
    private boolean topBarSolid;

    /**
     * Y-SYNC: on the comment replies screen the floating glass pill looks
     * broken: its near-white tint washes out the (always light) toolbar
     * title and back arrow over the dark comment list. Give the bar a solid
     * theme background there instead — the comments list already gets a top
     * inset from applyTopGlassPadding, so the first comment stays fully
     * visible below the bar. Everywhere else the glass look is restored.
     *
     * @param force re-apply even if the style did not change (the action bar
     *              may have recreated the navigation icon in the meantime)
     */
    private void updateTopBarStyle(final boolean force) {
        if (toolbarLayoutBinding == null) {
            return;
        }
        final Fragment current = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_holder);
        final boolean solid = current instanceof CommentRepliesFragment;
        if (!force && solid == topBarSolid) {
            return;
        }
        topBarSolid = solid;
        final View toolbarContainer = (View) toolbarLayoutBinding.toolbar.getParent();
        if (solid) {
            toolbarContainer.setBackgroundColor(
                    ThemeHelper.resolveColorFromAttr(this, R.attr.windowBackground));
            final int textColor = ThemeHelper.resolveColorFromAttr(this,
                    android.R.attr.textColorPrimary);
            toolbarLayoutBinding.toolbar.setTitleTextColor(textColor);
            setToolbarNavigationIconTint(textColor);
        } else {
            toolbarContainer.setBackground(null);
            final int textColor = ThemeHelper.resolveColorFromAttr(this, R.attr.actionColor);
            toolbarLayoutBinding.toolbar.setTitleTextColor(textColor);
            setToolbarNavigationIconTint(textColor);
        }
    }

    private void setToolbarNavigationIconTint(final int color) {
        final Drawable icon = toolbarLayoutBinding.toolbar.getNavigationIcon();
        if (icon == null) {
            return;
        }
        final Drawable tinted = DrawableCompat.wrap(icon).mutate();
        DrawableCompat.setTint(tinted, color);
        toolbarLayoutBinding.toolbar.setNavigationIcon(tinted);
    }

    // Y-SYNC: guarded blur setup state (see ensureBottomBlurSetup). The
    // recorded size is the wrapper frame's size at the last setupWith.
    private boolean bottomBlurReady;
    private int bottomBlurSetupWidth;
    private int bottomBlurSetupHeight;

    /**
     * Y-SYNC: runs the bottom bar's setupWith exactly once per wrapper
     * size — the twin of {@link #ensureTopBlurSetup()}; see its javadoc
     * for why a size-0 setup leaves the bar plain until a video
     * round-trip revives it.
     */
    private void ensureBottomBlurSetup() {
        if (bottomBlurReady || mainBinding == null) {
            return;
        }
        final View frame = mainBinding.bottomBlurFrame;
        if (!frame.isAttachedToWindow() || frame.getWidth() <= 0
                || frame.getHeight() <= 0) {
            return;
        }
        setupBottomBlurEffect();
        bottomBlurReady = true;
        bottomBlurSetupWidth = frame.getWidth();
        bottomBlurSetupHeight = frame.getHeight();
    }

    private void setupBottomBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        // Y-SYNC: the pill outline/clip live on the wrapping frame (which
        // also carries the solid fallback background), not on the BlurView.
        final float radiusPx = 32 * getResources().getDisplayMetrics().density;
        mainBinding.bottomBlurFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(final View view, final Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        mainBinding.bottomBlurFrame.setClipToOutline(true);
        // Y-SYNC: guarded first-frame blur setup (see ensureTopBlurSetup):
        // try right away, again at the first drawn frame, and again
        // whenever the frame's size actually changes afterwards.
        final View bottomBarFrame = mainBinding.bottomBlurFrame;
        bottomBarFrame.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (bottomBarFrame.getViewTreeObserver().isAlive()) {
                            bottomBarFrame.getViewTreeObserver()
                                    .removeOnPreDrawListener(this);
                        }
                        ensureBottomBlurSetup();
                        return true;
                    }
                });
        bottomBarFrame.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            final int width = right - left;
            final int height = bottom - top;
            if (width > 0 && height > 0
                    && (width != bottomBlurSetupWidth
                            || height != bottomBlurSetupHeight)) {
                bottomBlurReady = false;
                ensureBottomBlurSetup();
            }
        });
        ensureBottomBlurSetup();
    }

    private void setupBottomBlurEffect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }
        mainBinding.bottomBlur.setupWith(mainBinding.getRoot(), new RenderEffectBlur())
                .setFrameClearDrawable(ContextCompat.getDrawable(this,
                        R.drawable.glass_nav_tint))
                .setBlurRadius(18f);
    }

    private void hideToolbarHamburger() {
        // Y-SYNC: the drawer is opened from the bottom bar's Menu tab instead.
        if (toggle != null) {
            toggle.setDrawerIndicatorEnabled(false);
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
        toolbarLayoutBinding.toolbar.setNavigationIcon(null);
    }

    private boolean onBottomNavItemSelected(@NonNull final MenuItem item) {
        final FragmentManager fm = getSupportFragmentManager();
        final Fragment current = fm.findFragmentById(R.id.fragment_holder);
        final int itemId = item.getItemId();
        if (itemId == R.id.nav_menu) {
            mainBinding.getRoot().openDrawer(drawerLayoutBinding.navigation);
            updateBottomNavVisibility();
            // The framework checks Menu after this returns; restore the current
            // section's checked state so Menu never stays selected.
            mainBinding.bottomNav.post(this::updateBottomNavVisibility);
            return true;
        }
        if (itemId == R.id.nav_home) {
            if (!(current instanceof MainFragment)) {
                NavigationHelper.gotoMainFragment(fm);
            }
        } else if (itemId == R.id.nav_feed) {
            if (!(current instanceof FeedFragment)) {
                fm.popBackStackImmediate(NavigationHelper.MAIN_FRAGMENT_TAG, 0);
                NavigationHelper.openFeedFragment(fm);
            }
        } else if (itemId == R.id.nav_subscriptions) {
            if (!(current instanceof SubscriptionFragment)) {
                fm.popBackStackImmediate(NavigationHelper.MAIN_FRAGMENT_TAG, 0);
                NavigationHelper.openSubscriptionFragment(fm);
            }
        } else if (itemId == R.id.nav_bookmarks) {
            if (!(current instanceof BookmarkFragment)) {
                fm.popBackStackImmediate(NavigationHelper.MAIN_FRAGMENT_TAG, 0);
                NavigationHelper.openBookmarksFragment(fm);
            }
        }
        updateBottomNavVisibility();
        return true;
    }

    // Last known player sheet offset: -1 hidden, 0 collapsed (mini player),
    // 1 expanded. Kept from the sheet callbacks; state alone is not reliable
    // because the sheet starts out COLLAPSED with peekHeight 0 (no player).
    private float playerSlideOffset = -1f;

    // Y-SYNC: true while the player sheet's peek height is temporarily set
    // to 0 because the Shorts feed is showing (see updateBottomNavVisibility).
    private boolean shortsSheetSuppressed;

    private static float sheetOffsetForState(final int state) {
        if (state == BottomSheetBehavior.STATE_EXPANDED
                || state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
            return 1f;
        } else if (state == BottomSheetBehavior.STATE_COLLAPSED) {
            return 0f;
        }
        return -1f;
    }

    private void updateBottomNavVisibility() {
        if (mainBinding == null || DeviceUtils.isTv(this)) {
            return;
        }
        final Fragment current = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_holder);
        // No fragment yet = the home screen is still loading: count it as
        // top-level so the bar is correct from the very first frame.
        final boolean topLevel = current == null || current instanceof MainFragment
                || current instanceof FeedFragment
                || current instanceof SubscriptionFragment
                || current instanceof BookmarkFragment;
        final BottomSheetBehavior<?> behavior =
                BottomSheetBehavior.from(mainBinding.fragmentPlayerHolder);
        final int sheetState = behavior.getState();
        // Y-SYNC: the sheet starts out COLLAPSED with peekHeight 0 and no player
        // fragment attached — that is NOT a playing video. A player only counts
        // when one really exists (fragment attached or a real peek height set)
        // and the sheet is not hidden. (v1.6.0 trusted the state alone, so the
        // bar stayed hidden until a video had been opened and closed once.)
        final boolean playerPresent = sheetState != BottomSheetBehavior.STATE_HIDDEN
                && (getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_player_holder) != null
                        || behavior.getPeekHeight() > 0);
        // Y-SYNC: while the Shorts feed is showing, the mini player strip
        // must not overlay its bottom. STATE_HIDDEN cannot be used for
        // this: VideoDetailFragment treats it as "close the player" and
        // stops the playback service. Collapsing with a zero peek height
        // takes the sheet fully off-screen while keeping the (paused)
        // player session alive; the normal peek height is restored as
        // soon as Shorts is left.
        if (current instanceof ShortsFragment) {
            if (playerPresent && sheetState == BottomSheetBehavior.STATE_COLLAPSED
                    && behavior.getPeekHeight() != 0) {
                behavior.setPeekHeight(0);
                shortsSheetSuppressed = true;
            }
        } else if (shortsSheetSuppressed) {
            shortsSheetSuppressed = false;
            if (playerPresent && sheetState == BottomSheetBehavior.STATE_COLLAPSED) {
                behavior.setPeekHeight(getResources()
                        .getDimensionPixelSize(R.dimen.mini_player_height));
            }
        }
        final boolean playerExpanded = playerPresent && playerSlideOffset > 0f;
        final boolean miniPlayer = playerPresent && !playerExpanded;
        // Fullscreen playback never shows the bar — checked explicitly,
        // because after a rotation the slide offset can read stale a while.
        final Fragment playerFragment = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        final boolean fullscreen = playerFragment instanceof VideoDetailFragment
                && ((VideoDetailFragment) playerFragment).isFullscreen();
        final boolean show = topLevel && !playerExpanded && !fullscreen;
        // Y-SYNC: hide with INVISIBLE, never GONE, and only write the
        // visibility when it actually changes. A GONE/VISIBLE flip changes
        // the bar's size, which reallocates the blur bitmap and forces the
        // BlurView's render node to re-record — on this GPU class that
        // window is exactly where garbage (lime) frames get composited.
        // INVISIBLE keeps the measured size (and the footprint math below)
        // perfectly stable.
        final int barVisibility = show ? View.VISIBLE : View.INVISIBLE;
        if (mainBinding.bottomBlurFrame.getVisibility() != barVisibility) {
            mainBinding.bottomBlurFrame.setVisibility(barVisibility);
        }
        // Y-SYNC: stack the mini player above the floating bar instead of
        // letting it cover the bar — lift the sheet by the bar's footprint
        // plus a visible gap, easing the lift away as the player expands.
        final float density = getResources().getDisplayMetrics().density;
        int barFootprint = Math.round(70 * density);
        if (mainBinding.bottomBlurFrame.getHeight() > 0
                && mainBinding.bottomBlurFrame.getLayoutParams()
                        instanceof ViewGroup.MarginLayoutParams) {
            barFootprint = mainBinding.bottomBlurFrame.getHeight()
                    + ((ViewGroup.MarginLayoutParams)
                            mainBinding.bottomBlurFrame.getLayoutParams()).bottomMargin;
        }
        final float lift = topLevel && playerPresent
                ? -(barFootprint + Math.round(10 * density))
                        * (1f - Math.max(playerSlideOffset, 0f)) : 0f;
        mainBinding.fragmentPlayerHolder.setTranslationY(lift);
        int bottomPadding = 0;
        if (show) {
            bottomPadding = Math.round(92 * density);
            if (miniPlayer) {
                bottomPadding += behavior.getPeekHeight();
            }
        }
        // Y-SYNC: single owner for the holder's bottom padding. While a
        // player fragment is attached, VideoDetailFragment owns this
        // padding via manageSpaceAtTheBottom(); both sides writing it
        // (as before) made the result depend on which event landed last,
        // so after rotation the states disagreed. Only write it when no
        // player fragment exists.
        if (playerFragment == null) {
            mainBinding.fragmentHolder.setPadding(0, 0, 0, bottomPadding);
        }
        if (current instanceof MainFragment) {
            mainBinding.bottomNav.getMenu().findItem(R.id.nav_home).setChecked(true);
        } else if (current instanceof FeedFragment) {
            mainBinding.bottomNav.getMenu().findItem(R.id.nav_feed).setChecked(true);
        } else if (current instanceof SubscriptionFragment) {
            mainBinding.bottomNav.getMenu().findItem(R.id.nav_subscriptions)
                    .setChecked(true);
        } else if (current instanceof BookmarkFragment) {
            mainBinding.bottomNav.getMenu().findItem(R.id.nav_bookmarks).setChecked(true);
        }
        // Y-SYNC: Shorts is full-bleed, like in the YouTube app: hide the
        // floating top bar entirely while the Shorts feed is showing (as a
        // solid black pill it just covered the video) and restore it on
        // every other screen. Back navigation keeps working via the
        // system back button/gesture. INVISIBLE (not GONE) keeps the
        // bar's size stable, so showing it again does not resize the bar
        // or disturb its blur.
        if (toolbarLayoutBinding != null) {
            final View topBar = toolbarLayoutBinding.getRoot();
            final int topBarVisibility = current instanceof ShortsFragment
                    ? View.INVISIBLE : View.VISIBLE;
            if (topBar.getVisibility() != topBarVisibility) {
                topBar.setVisibility(topBarVisibility);
            }
        }
        updateTopBarStyle(false);
    }

    /**
     * Y-SYNC: delayed re-check after onResume (see onResume). By the time
     * it runs, a recreated activity has its fragments restored: re-read
     * the player sheet's settled state (rotation could have left
     * playerSlideOffset stale — MainActivity initialised it from the XML
     * default state), make sure both bars got their guarded first-frame
     * blur setup, and re-run the visibility update, whose fullscreen rule
     * now reads the restored player fragment — so a fullscreen video
     * after rotation hides the bar again instead of leaving it drawn
     * over the video.
     */
    private void recheckBarsAfterResume() {
        if (mainBinding == null) {
            return;
        }
        ensureTopBlurSetup();
        ensureBottomBlurSetup();
        final BottomSheetBehavior<?> behavior =
                BottomSheetBehavior.from(mainBinding.fragmentPlayerHolder);
        final int sheetState = behavior.getState();
        if (sheetState != BottomSheetBehavior.STATE_DRAGGING
                && sheetState != BottomSheetBehavior.STATE_SETTLING) {
            playerSlideOffset = sheetOffsetForState(sheetState);
        }
        updateBottomNavVisibility();
    }

    private void setupDrawer() throws ExtractionException {
        addDrawerMenuForCurrentService();

        toggle = new ActionBarDrawerToggle(this, mainBinding.getRoot(),
                toolbarLayoutBinding.toolbar, R.string.drawer_open, R.string.drawer_close);
        toggle.syncState();
        mainBinding.getRoot().addDrawerListener(toggle);
        mainBinding.getRoot().addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            private int lastService;

            @Override
            public void onDrawerOpened(final View drawerView) {
                lastService = ServiceHelper.getSelectedServiceId(MainActivity.this);
            }

            @Override
            public void onDrawerClosed(final View drawerView) {
                if (servicesShown) {
                    toggleServices();
                }
                if (lastService != ServiceHelper.getSelectedServiceId(MainActivity.this)) {
                    ActivityCompat.recreate(MainActivity.this);
                }
            }
        });

        drawerLayoutBinding.navigation.setNavigationItemSelectedListener(this::drawerItemSelected);
        setupDrawerHeader();
    }

    /**
     * Builds the drawer menu for the current service.
     *
     * @throws ExtractionException if the service didn't provide available kiosks
     */
    private void addDrawerMenuForCurrentService() throws ExtractionException {
        //Tabs
        drawerLayoutBinding.navigation.getMenu()
                .add(R.id.menu_tabs_group, ITEM_ID_SUBSCRIPTIONS, ORDER,
                        R.string.tab_subscriptions)
                .setIcon(R.drawable.ic_tv);
        drawerLayoutBinding.navigation.getMenu()
                .add(R.id.menu_tabs_group, ITEM_ID_FEED, ORDER, R.string.fragment_feed_title)
                .setIcon(R.drawable.ic_subscriptions);
        drawerLayoutBinding.navigation.getMenu()
                .add(R.id.menu_tabs_group, ITEM_ID_BOOKMARKS, ORDER, R.string.tab_bookmarks)
                .setIcon(R.drawable.ic_bookmark);
        drawerLayoutBinding.navigation.getMenu()
                .add(R.id.menu_tabs_group, ITEM_ID_DOWNLOADS, ORDER, R.string.downloads)
                .setIcon(R.drawable.ic_file_download);
        drawerLayoutBinding.navigation.getMenu()
                .add(R.id.menu_tabs_group, ITEM_ID_HISTORY, ORDER, R.string.action_history)
                .setIcon(R.drawable.ic_history);
        // Y-SYNC: Shorts feed (YouTube only), in the drawer so the 5-tab
        // bottom bar stays untouched.
        if (ServiceHelper.getSelectedServiceId(this) == 0) {
            drawerLayoutBinding.navigation.getMenu()
                    .add(R.id.menu_tabs_group, ITEM_ID_SHORTS, ORDER, R.string.shorts_title)
                    .setIcon(R.drawable.ic_smart_display);
        }

        //Kiosks
        final int currentServiceId = ServiceHelper.getSelectedServiceId(this);
        final StreamingService service = NewPipe.getService(currentServiceId);

        int kioskMenuItemId = 0;

        for (final String ks : service.getKioskList().getAvailableKiosks()) {
            if (currentServiceId == 0 && !"live".equals(ks)) {
                continue;
            }
            drawerLayoutBinding.navigation.getMenu()
                    .add(R.id.menu_kiosks_group, kioskMenuItemId, 0, KioskTranslator
                            .getTranslatedKioskName(ks, this))
                    .setIcon(KioskTranslator.getKioskIcon(ks));
            kioskMenuItemId++;
        }

        //Settings and About
        drawerLayoutBinding.navigation.getMenu()
                .add(R.id.menu_options_about_group, ITEM_ID_SETTINGS, ORDER, R.string.settings)
                .setIcon(R.drawable.ic_settings);
        drawerLayoutBinding.navigation.getMenu()
                .add(R.id.menu_options_about_group, ITEM_ID_DONATION, ORDER,
                        R.string.donation_title)
                .setIcon(R.drawable.volunteer_activism_ic);
        drawerLayoutBinding.navigation.getMenu()
                .add(R.id.menu_options_about_group, ITEM_ID_ABOUT, ORDER, R.string.tab_about)
                .setIcon(R.drawable.ic_info_outline);
    }

    private boolean drawerItemSelected(final MenuItem item) {
        final int groupId = item.getGroupId();
        if (groupId == R.id.menu_services_group) {
            changeService(item);
        } else if (groupId == R.id.menu_tabs_group) {
            tabSelected(item);
        } else if (groupId == R.id.menu_kiosks_group) {
            try {
                kioskSelected(item);
            } catch (final Exception e) {
                ErrorUtil.showUiErrorSnackbar(this, "Selecting drawer kiosk", e);
            }
        } else if (groupId == R.id.menu_options_about_group) {
            optionsAboutSelected(item);
        } else {
            return false;
        }

        mainBinding.getRoot().closeDrawers();
        return true;
    }

    private void changeService(final MenuItem item) {
        drawerLayoutBinding.navigation.getMenu()
                .getItem(ServiceHelper.getSelectedServiceId(this))
                .setChecked(false);
        ServiceHelper.setSelectedServiceId(this, item.getItemId());
        drawerLayoutBinding.navigation.getMenu()
                .getItem(ServiceHelper.getSelectedServiceId(this))
                .setChecked(true);
    }

    private void tabSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case ITEM_ID_SUBSCRIPTIONS:
                NavigationHelper.openSubscriptionFragment(getSupportFragmentManager());
                break;
            case ITEM_ID_FEED:
                NavigationHelper.openFeedFragment(getSupportFragmentManager());
                break;
            case ITEM_ID_BOOKMARKS:
                NavigationHelper.openBookmarksFragment(getSupportFragmentManager());
                break;
            case ITEM_ID_DOWNLOADS:
                NavigationHelper.openDownloads(this);
                break;
            case ITEM_ID_HISTORY:
                NavigationHelper.openStatisticFragment(getSupportFragmentManager());
                break;
            case ITEM_ID_SHORTS:
                NavigationHelper.openShortsFragment(getSupportFragmentManager());
                break;
        }
    }

    private void kioskSelected(final MenuItem item) throws ExtractionException {
        final StreamingService currentService = ServiceHelper.getSelectedService(this);
        int kioskMenuItemId = 0;
        for (final String kioskId : currentService.getKioskList().getAvailableKiosks()) {
            if (currentService.getServiceId() == 0 && !"live".equals(kioskId)) {
                continue;
            }
            if (kioskMenuItemId == item.getItemId()) {
                NavigationHelper.openKioskFragment(getSupportFragmentManager(),
                        currentService.getServiceId(), kioskId);
                break;
            }
            kioskMenuItemId++;
        }
    }

    private void optionsAboutSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case ITEM_ID_SETTINGS:
                NavigationHelper.openSettings(this);
                break;
            case ITEM_ID_DONATION:
                ShareUtils.openUrlInBrowser(this, getString(R.string.donation_url));
                break;
            case ITEM_ID_ABOUT:
                NavigationHelper.openAbout(this);
                break;
        }
    }

    private void setupDrawerHeader() {
        drawerHeaderBinding.drawerHeaderActionButton.setOnClickListener(view -> toggleServices());

        // If the current app name is bigger than the default "NewPipe" (7 chars),
        // let the text view grow a little more as well.
        if (getString(R.string.app_name).length() > "NewPipe".length()) {
            final ViewGroup.LayoutParams layoutParams =
                    drawerHeaderBinding.drawerHeaderNewpipeTitle.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            drawerHeaderBinding.drawerHeaderNewpipeTitle.setLayoutParams(layoutParams);
            drawerHeaderBinding.drawerHeaderNewpipeTitle.setMaxLines(2);
            drawerHeaderBinding.drawerHeaderNewpipeTitle.setMinWidth(getResources()
                    .getDimensionPixelSize(R.dimen.drawer_header_newpipe_title_default_width));
            drawerHeaderBinding.drawerHeaderNewpipeTitle.setMaxWidth(getResources()
                    .getDimensionPixelSize(R.dimen.drawer_header_newpipe_title_max_width));
        }
    }

    private void toggleServices() {
        servicesShown = !servicesShown;

        drawerLayoutBinding.navigation.getMenu().removeGroup(R.id.menu_services_group);
        drawerLayoutBinding.navigation.getMenu().removeGroup(R.id.menu_tabs_group);
        drawerLayoutBinding.navigation.getMenu().removeGroup(R.id.menu_kiosks_group);
        drawerLayoutBinding.navigation.getMenu().removeGroup(R.id.menu_options_about_group);

        // Show up or down arrow
        drawerHeaderBinding.drawerArrow.setImageResource(
                servicesShown ? R.drawable.ic_arrow_drop_up : R.drawable.ic_arrow_drop_down);

        if (servicesShown) {
            showServices();
        } else {
            try {
                addDrawerMenuForCurrentService();
            } catch (final Exception e) {
                ErrorUtil.showUiErrorSnackbar(this, "Showing main page tabs", e);
            }
        }
    }

    private void showServices() {
        for (final StreamingService s : NewPipe.getServices()) {
            final String title = s.getServiceInfo().getName();

            final MenuItem menuItem = drawerLayoutBinding.navigation.getMenu()
                    .add(R.id.menu_services_group, s.getServiceId(), ORDER, title)
                    .setIcon(ServiceHelper.getIcon(s.getServiceId()));

            // peertube specifics
            if (s.getServiceId() == 3) {
                enhancePeertubeMenu(menuItem);
            }
        }
        drawerLayoutBinding.navigation.getMenu()
                .getItem(ServiceHelper.getSelectedServiceId(this))
                .setChecked(true);
    }

    private void enhancePeertubeMenu(final MenuItem menuItem) {
        final PeertubeInstance currentInstance = PeertubeHelper.getCurrentInstance();
        menuItem.setTitle(currentInstance.getName());
        final Spinner spinner = InstanceSpinnerLayoutBinding.inflate(LayoutInflater.from(this))
                .getRoot();
        final List<PeertubeInstance> instances = PeertubeHelper.getInstanceList(this);
        final List<String> items = new ArrayList<>();
        int defaultSelect = 0;
        for (final PeertubeInstance instance : instances) {
            items.add(instance.getName());
            if (instance.getUrl().equals(currentInstance.getUrl())) {
                defaultSelect = items.size() - 1;
            }
        }
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                R.layout.instance_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(defaultSelect, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(final AdapterView<?> parent, final View view,
                                       final int position, final long id) {
                final PeertubeInstance newInstance = instances.get(position);
                if (newInstance.getUrl().equals(PeertubeHelper.getCurrentInstance().getUrl())) {
                    return;
                }
                PeertubeHelper.selectInstance(newInstance, getApplicationContext());
                changeService(menuItem);
                mainBinding.getRoot().closeDrawers();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    getSupportFragmentManager().popBackStack(null,
                            FragmentManager.POP_BACK_STACK_INCLUSIVE);
                    ActivityCompat.recreate(MainActivity.this);
                }, 300);
            }

            @Override
            public void onNothingSelected(final AdapterView<?> parent) {

            }
        });
        menuItem.setActionView(spinner);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isChangingConfigurations()) {
            StateSaver.clearStateFiles();
        }
        if (broadcastReceiver != null) {
            unregisterReceiver(broadcastReceiver);
        }
    }

    @Override
    protected void onResume() {
        // Change the date format to match the selected language on resume
        Localization.initPrettyTime(Localization.resolvePrettyTime());
        super.onResume();
        updateBottomNavVisibility();
        // Rotation recreates the activity and the sheet state can settle
        // after onResume; re-check once everything has landed.
        mainBinding.bottomNav.postDelayed(this::recheckBarsAfterResume, 400);

        // Close drawer on return, and don't show animation,
        // so it looks like the drawer isn't open when the user returns to MainActivity
        mainBinding.getRoot().closeDrawer(GravityCompat.START, false);
        try {
            final int selectedServiceId = ServiceHelper.getSelectedServiceId(this);
            final String selectedServiceName = NewPipe.getService(selectedServiceId)
                    .getServiceInfo().getName();
            drawerHeaderBinding.drawerHeaderServiceView.setText(selectedServiceName);
            drawerHeaderBinding.drawerHeaderServiceIcon.setImageResource(ServiceHelper
                    .getIcon(selectedServiceId));

            drawerHeaderBinding.drawerHeaderServiceView.post(() -> drawerHeaderBinding
                    .drawerHeaderServiceView.setSelected(true));
            drawerHeaderBinding.drawerHeaderActionButton.setContentDescription(
                    getString(R.string.drawer_header_description) + selectedServiceName);
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Setting up service toggle", e);
        }

        if (sharedPreferences.getBoolean(Constants.KEY_THEME_CHANGE, false)) {
            if (DEBUG) {
                Log.d(TAG, "Theme has changed, recreating activity...");
            }
            sharedPrefEditor.putBoolean(Constants.KEY_THEME_CHANGE, false).apply();
            ActivityCompat.recreate(this);
        }

        if (sharedPreferences.getBoolean(Constants.KEY_MAIN_PAGE_CHANGE, false)) {
            if (DEBUG) {
                Log.d(TAG, "main page has changed, recreating main fragment...");
            }
            sharedPrefEditor.putBoolean(Constants.KEY_MAIN_PAGE_CHANGE, false).apply();
            NavigationHelper.openMainActivity(this);
        }

        final boolean isHistoryEnabled = sharedPreferences.getBoolean(
                getString(R.string.enable_watch_history_key), true);
        drawerLayoutBinding.navigation.getMenu().findItem(ITEM_ID_HISTORY)
                .setVisible(isHistoryEnabled);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "onNewIntent() called with: intent = [" + intent + "]");
        }
        if (intent != null) {
            // Return if launched from a launcher (e.g. Nova Launcher, Pixel Launcher ...)
            // to not destroy the already created backstack
            final String action = intent.getAction();
            if ((action != null && action.equals(Intent.ACTION_MAIN))
                    && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                return;
            }
        }

        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        final Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (fragment instanceof OnKeyDownListener
                && !bottomSheetHiddenOrCollapsed()) {
            // Provide keyDown event to fragment which then sends this event
            // to the main player service
            return ((OnKeyDownListener) fragment).onKeyDown(keyCode)
                    || super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (DEBUG) {
            Log.d(TAG, "onBackPressed() called");
        }

        if (DeviceUtils.isTv(this)) {
            if (mainBinding.getRoot().isDrawerOpen(drawerLayoutBinding.navigation)) {
                mainBinding.getRoot().closeDrawers();
                return;
            }
        }

        // In case bottomSheet is not visible on the screen or collapsed we can assume that the user
        // interacts with a fragment inside fragment_holder so all back presses should be
        // handled by it
        if (bottomSheetHiddenOrCollapsed()) {
            final FragmentManager fm = getSupportFragmentManager();
            final Fragment fragment = fm.findFragmentById(R.id.fragment_holder);
            // If current fragment implements BackPressable (i.e. can/wanna handle back press)
            // delegate the back press to it
            if (fragment instanceof BackPressable) {
                if (((BackPressable) fragment).onBackPressed()) {
                    return;
                }
            } else if (fragment instanceof CommentRepliesFragment) {
                // expand DetailsFragment if CommentRepliesFragment was opened
                // to show the top level comments again
                // Expand DetailsFragment if CommentRepliesFragment was opened
                // and no other CommentRepliesFragments are on top of the back stack
                // to show the top level comments again.
                openDetailFragmentFromCommentReplies(fm, false);
            }

        } else {
            final Fragment fragmentPlayer = getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_player_holder);
            // If current fragment implements BackPressable (i.e. can/wanna handle back press)
            // delegate the back press to it
            if (fragmentPlayer instanceof BackPressable) {
                if (!((BackPressable) fragmentPlayer).onBackPressed()) {
                    BottomSheetBehavior.from(mainBinding.fragmentPlayerHolder)
                            .setState(BottomSheetBehavior.STATE_COLLAPSED);
                }
                return;
            }
        }

        if (getSupportFragmentManager().getBackStackEntryCount() == 1) {
            finish();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
                                           @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (final int i : grantResults) {
            if (i == PackageManager.PERMISSION_DENIED) {
                return;
            }
        }
        switch (requestCode) {
            case PermissionHelper.DOWNLOADS_REQUEST_CODE:
                NavigationHelper.openDownloads(this);
                break;
            case PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE:
                final Fragment fragment = getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_player_holder);
                if (fragment instanceof VideoDetailFragment) {
                    ((VideoDetailFragment) fragment).openDownloadDialog();
                }
                break;
            case PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE:
                NotificationWorker.initialize(this);
                break;
        }
    }

    /**
     * Implement the following diagram behavior for the up button:
     * <pre>
     *              +---------------+
     *              |  Main Screen  +----+
     *              +-------+-------+    |
     *                      |            |
     *                      ▲ Up         | Search Button
     *                      |            |
     *                 +----+-----+      |
     *    +------------+  Search  |◄-----+
     *    |            +----+-----+
     *    |   Open          |
     *    |  something      ▲ Up
     *    |                 |
     *    |    +------------+-------------+
     *    |    |                          |
     *    |    |  Video    <->  Channel   |
     *    +---►|  Channel  <->  Playlist  |
     *         |  Video    <->  ....      |
     *         |                          |
     *         +--------------------------+
     * </pre>
     */
    private void onHomeButtonPressed() {
        final FragmentManager fm = getSupportFragmentManager();
        final Fragment fragment = fm.findFragmentById(R.id.fragment_holder);

        if (fragment instanceof CommentRepliesFragment) {
            // Expand DetailsFragment if CommentRepliesFragment was opened
            // and no other CommentRepliesFragments are on top of the back stack
            // to show the top level comments again.
            openDetailFragmentFromCommentReplies(fm, true);
        } else if (!NavigationHelper.tryGotoSearchFragment(fm)) {
            // If search fragment wasn't found in the backstack go to the main fragment
            NavigationHelper.gotoMainFragment(fm);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: menu = [" + menu + "]");
        }
        super.onCreateOptionsMenu(menu);

        final Fragment fragment =
                getSupportFragmentManager().findFragmentById(R.id.fragment_holder);
        if (!(fragment instanceof SearchFragment)) {
            toolbarLayoutBinding.toolbarSearchContainer.getRoot().setVisibility(View.GONE);
        }

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
        }

        updateDrawerNavigation();
        // The action bar may have recreated the navigation icon above:
        // re-apply the top bar style (e.g. the solid comments bar).
        updateTopBarStyle(true);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (DEBUG) {
            Log.d(TAG, "onOptionsItemSelected() called with: item = [" + item + "]");
        }

        if (item.getItemId() == android.R.id.home) {
            onHomeButtonPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    private void initFragments() {
        if (DEBUG) {
            Log.d(TAG, "initFragments() called");
        }
        StateSaver.clearStateFiles();
        if (getIntent() != null && getIntent().hasExtra(Constants.KEY_LINK_TYPE)) {
            // When user watch a video inside popup and then tries to open the video in main player
            // while the app is closed he will see a blank fragment on place of kiosk.
            // Let's open it first
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                NavigationHelper.openMainFragment(getSupportFragmentManager());
            }

            handleIntent(getIntent());
        } else {
            NavigationHelper.gotoMainFragment(getSupportFragmentManager());
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private void updateDrawerNavigation() {
        if (getSupportActionBar() == null) {
            return;
        }

        final Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_holder);
        if (fragment instanceof MainFragment) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            if (toggle != null) {
                toggle.syncState();
                toolbarLayoutBinding.toolbar.setNavigationIcon(null);
                toolbarLayoutBinding.toolbar.setNavigationOnClickListener(v -> mainBinding.getRoot()
                        .open());
                mainBinding.getRoot().setDrawerLockMode(DrawerLayout.LOCK_MODE_UNDEFINED);
            }
        } else {
            mainBinding.getRoot().setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbarLayoutBinding.toolbar.setNavigationOnClickListener(v -> onHomeButtonPressed());
        }
    }

    private void handleIntent(final Intent intent) {
        try {
            if (DEBUG) {
                Log.d(TAG, "handleIntent() called with: intent = [" + intent + "]");
            }

            if (intent.hasExtra(Constants.KEY_LINK_TYPE)) {
                final String url = intent.getStringExtra(Constants.KEY_URL);
                final int serviceId = intent.getIntExtra(Constants.KEY_SERVICE_ID, 0);
                String title = intent.getStringExtra(Constants.KEY_TITLE);
                if (title == null) {
                    title = "";
                }

                final StreamingService.LinkType linkType = ((StreamingService.LinkType) intent
                        .getSerializableExtra(Constants.KEY_LINK_TYPE));
                assert linkType != null;
                switch (linkType) {
                    case STREAM:
                        final String intentCacheKey = intent.getStringExtra(
                                Player.PLAY_QUEUE_KEY);
                        final PlayQueue playQueue = intentCacheKey != null
                                ? SerializedCache.getInstance()
                                .take(intentCacheKey, PlayQueue.class)
                                : null;

                        final boolean switchingPlayers = intent.getBooleanExtra(
                                VideoDetailFragment.KEY_SWITCHING_PLAYERS, false);
                        NavigationHelper.openVideoDetailFragment(
                                getApplicationContext(), getSupportFragmentManager(),
                                serviceId, url, title, playQueue, switchingPlayers);
                        break;
                    case CHANNEL:
                        NavigationHelper.openChannelFragment(getSupportFragmentManager(),
                                serviceId, url, title);
                        break;
                    case PLAYLIST:
                        NavigationHelper.openPlaylistFragment(getSupportFragmentManager(),
                                serviceId, url, title);
                        break;
                }
            } else if (intent.hasExtra(Constants.KEY_OPEN_SEARCH)) {
                String searchString = intent.getStringExtra(Constants.KEY_SEARCH_STRING);
                if (searchString == null) {
                    searchString = "";
                }
                final int serviceId = intent.getIntExtra(Constants.KEY_SERVICE_ID, 0);
                NavigationHelper.openSearchFragment(
                        getSupportFragmentManager(),
                        serviceId,
                        searchString);

            } else {
                NavigationHelper.gotoMainFragment(getSupportFragmentManager());
            }
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Handling intent", e);
        }
    }

    private void openMiniPlayerIfMissing() {
        final Fragment fragmentPlayer = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (fragmentPlayer == null) {
            // We still don't have a fragment attached to the activity. It can happen when a user
            // started popup or background players without opening a stream inside the fragment.
            // Adding it in a collapsed state (only mini player will be visible).
            NavigationHelper.showMiniPlayer(getSupportFragmentManager());
        }
    }

    private void openMiniPlayerUponPlayerStarted() {
        if (getIntent().getSerializableExtra(Constants.KEY_LINK_TYPE)
                == StreamingService.LinkType.STREAM) {
            // handleIntent() already takes care of opening video detail fragment
            // due to an intent containing a STREAM link
            return;
        }

        if (PlayerHolder.getInstance().isPlayerOpen()) {
            // if the player is already open, no need for a broadcast receiver
            openMiniPlayerIfMissing();
        } else {
            // listen for player start intent being sent around
            broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    if (Objects.equals(intent.getAction(),
                            VideoDetailFragment.ACTION_PLAYER_STARTED)
                            && PlayerHolder.getInstance().isPlayerOpen()) {
                        openMiniPlayerIfMissing();
                        // At this point the player is added 100%, we can unregister. Other actions
                        // are useless since the fragment will not be removed after that.
                        unregisterReceiver(broadcastReceiver);
                        broadcastReceiver = null;
                    }
                }
            };
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(VideoDetailFragment.ACTION_PLAYER_STARTED);
            ContextCompat.registerReceiver(this, broadcastReceiver, intentFilter,
                    ContextCompat.RECEIVER_EXPORTED);

            // If the PlayerHolder is not bound yet, but the service is running, try to bind to it.
            // Once the connection is established, the ACTION_PLAYER_STARTED will be sent.
            PlayerHolder.getInstance().tryBindIfNeeded(this);
        }
    }

    private void openDetailFragmentFromCommentReplies(
            @NonNull final FragmentManager fm,
            final boolean popBackStack
    ) {
        // obtain the name of the fragment under the replies fragment that's going to be popped
        @Nullable final String fragmentUnderEntryName;
        if (fm.getBackStackEntryCount() < 2) {
            fragmentUnderEntryName = null;
        } else {
            fragmentUnderEntryName = fm.getBackStackEntryAt(fm.getBackStackEntryCount() - 2)
                    .getName();
        }

        // the root comment is the comment for which the user opened the replies page
        @Nullable final CommentRepliesFragment repliesFragment =
                (CommentRepliesFragment) fm.findFragmentByTag(CommentRepliesFragment.TAG);
        @Nullable final CommentsInfoItem rootComment =
                repliesFragment == null ? null : repliesFragment.getCommentsInfoItem();

        // sometimes this function pops the backstack, other times it's handled by the system
        if (popBackStack) {
            fm.popBackStackImmediate();
        }

        // only expand the bottom sheet back if there are no more nested comment replies fragments
        // stacked under the one that is currently being popped
        if (CommentRepliesFragment.TAG.equals(fragmentUnderEntryName)) {
            return;
        }

        final BottomSheetBehavior<FragmentContainerView> behavior = BottomSheetBehavior
                .from(mainBinding.fragmentPlayerHolder);
        // do not return to the comment if the details fragment was closed
        if (behavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
            return;
        }

        // scroll to the root comment once the bottom sheet expansion animation is finished
        behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull final View bottomSheet,
                                       final int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    final Fragment detailFragment = fm.findFragmentById(
                            R.id.fragment_player_holder);
                    if (detailFragment instanceof VideoDetailFragment && rootComment != null) {
                        // should always be the case
                        ((VideoDetailFragment) detailFragment).scrollToComment(rootComment);
                    }
                    behavior.removeBottomSheetCallback(this);
                }
            }

            @Override
            public void onSlide(@NonNull final View bottomSheet, final float slideOffset) {
                // not needed, listener is removed once the sheet is expanded
            }
        });

        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private boolean bottomSheetHiddenOrCollapsed() {
        final BottomSheetBehavior<FrameLayout> bottomSheetBehavior =
                BottomSheetBehavior.from(mainBinding.fragmentPlayerHolder);

        final int sheetState = bottomSheetBehavior.getState();
        return sheetState == BottomSheetBehavior.STATE_HIDDEN
                || sheetState == BottomSheetBehavior.STATE_COLLAPSED;
    }

    private void showKeepAndroidDialog() {
        final var prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final var lastCheckKey = getString(R.string.kao_last_checked_key);
        final var lastCheck = Instant.ofEpochMilli(prefs.getLong(lastCheckKey, 0));
        final var now = Instant.now();

        if (lastCheck.plus(30, ChronoUnit.DAYS).isBefore(now)) {
            final String detailsUrl = getKeepAndroidOpenDetailsUrl();

            final var dialog = new AlertDialog.Builder(this)
                    .setTitle("Keep Android Open")
                    .setCancelable(false)
                    .setMessage(R.string.kao_dialog_warning)
                    .setPositiveButton(android.R.string.ok, (d, w) -> prefs.edit()
                            .putLong(lastCheckKey, now.toEpochMilli())
                            .apply())
                    .setNeutralButton(R.string.kao_dialog_more_info, null)
                    .show();

            // If we use setNeutralButton/setNegativeButton, dialog will close after pressing the
            // buttons, but we want it to close only when positive button is pressed
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> ShareUtils.openUrlInBrowser(this, detailsUrl));
        }
    }

    @NonNull
    private static String getKeepAndroidOpenDetailsUrl() {
        final var supportedLanguages = List.of("fr", "de", "ca", "es", "id", "it", "pl",
                "pt", "cs", "sk", "fa", "ar", "tr", "el", "th", "ru", "uk", "ko", "zh", "ja");
        final String kaoBaseUrl = "https://keepandroidopen.org/";
        final var locale = Localization.getAppLocale();
        if (supportedLanguages.contains(locale.getLanguage())) {
            if ("zh".equals(locale.getLanguage())) {
                return kaoBaseUrl + ("TW".equals(locale.getCountry()) ? "zh-TW" : "zh-CN");
            } else {
                return kaoBaseUrl + locale.getLanguage();
            }
        } else {
            return kaoBaseUrl;
        }
    }

    private void showApi23RequirementDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return; // only show dialog on the devices that will stop being supported
        }

        final var prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final var shownKey = getString(R.string.api23_requirement_dialog_shown_key);
        if (prefs.getBoolean(shownKey, false)) {
            return; // dialog was already shown in the past, no need to show it again
        }

        final var dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.api23_requirement_dialog_title)
                .setCancelable(false)
                .setMessage(R.string.api23_requirement_dialog_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> prefs.edit()
                        .putBoolean(shownKey, true)
                        .apply())
                .setNegativeButton(R.string.api23_requirement_dialog_blogpost, null)
                .show();

        // If we use setNegativeButton, dialog will close after pressing the button,
        // but we want it to close only when positive button is pressed
        final var blogpostUrl = "https://newpipe.net/blog/pinned/announcement/drop-android-5/";
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> ShareUtils.openUrlInBrowser(this, blogpostUrl));
    }
}
