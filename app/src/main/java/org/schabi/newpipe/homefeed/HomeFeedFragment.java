package org.schabi.newpipe.homefeed;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.databinding.FragmentHomeFeedBinding;
import org.schabi.newpipe.databinding.ListStreamCardItemBinding;
import org.schabi.newpipe.util.DependentPreferenceHelper;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.image.CoilHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The home screen (Beranda): YouTube's generic home feed — regular videos of
 * all kinds, not just livestreams.
 *
 * <p>The extractor no longer offers a generic home kiosk, so the items come
 * from {@link HomeFeedClient} (a raw, anonymous InnerTube browse session) and
 * are shown with the same big-card look as the other lists. The feed scrolls
 * endlessly through continuation tokens, can be pulled to refresh, and
 * silently reloads when it is shown again after going stale. Tapping a card
 * opens the video in the regular player, exactly like the other lists.</p>
 */
public final class HomeFeedFragment extends BaseFragment {
    private static final int LOAD_MORE_THRESHOLD = 4;
    private static final int MAX_KEPT_ITEMS = 300;
    private static final int TRIM_SAFE_FIRST_VISIBLE = 100;
    private static final int YOUTUBE_SERVICE_ID = 0;
    private static final long STALE_AFTER_MS = 15 * 60 * 1000L;
    private static final long LOAD_TIMEOUT_SECONDS = 30;

    private final List<HomeFeedItem> items = new ArrayList<>();
    private final Set<String> seenVideoIds = new HashSet<>();
    // Watched positions by videoId: {progressSeconds, durationSeconds}.
    // Filled from the local playback-state database, like the other lists.
    private final Map<String, long[]> watchedStates = new HashMap<>();
    private final CompositeDisposable disposables = new CompositeDisposable();

    private FragmentHomeFeedBinding binding;
    private HomeFeedAdapter adapter;
    private HomeFeedClient feedClient;
    private LinearLayoutManager layoutManager;

    @Nullable
    private String continuationToken;
    private boolean loadingFeed;
    private long lastLoadTimeMs;

    public static HomeFeedFragment newInstance() {
        return new HomeFeedFragment();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentHomeFeedBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(getString(R.string.bottom_nav_home));
        feedClient = new HomeFeedClient(requireContext());

        adapter = new HomeFeedAdapter();
        layoutManager = new LinearLayoutManager(requireContext());
        binding.homeFeedList.setLayoutManager(layoutManager);
        binding.homeFeedList.setAdapter(adapter);
        setupCardSpacing();
        setupRefreshLayout();
        binding.homeFeedList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull final RecyclerView recyclerView,
                                   final int dx, final int dy) {
                if (dy > 0 && layoutManager.findLastVisibleItemPosition()
                        >= items.size() - LOAD_MORE_THRESHOLD) {
                    loadMore();
                }
            }
        });
        binding.homeFeedRefresh.setOnRefreshListener(this::refreshFeed);
        binding.homeFeedRetry.setOnClickListener(v -> loadFirstPage(false));

        if (items.isEmpty()) {
            loadFirstPage(false);
        } else {
            binding.homeFeedLoading.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from watching a video: refresh the watched-progress
        // bars so the video just watched shows how far it got.
        if (!items.isEmpty()) {
            loadWatchedStates();
        }
        // Coming back to a home feed that has gone stale: fetch a fresh
        // first page instead of showing the old one forever.
        if (!items.isEmpty() && !loadingFeed
                && System.currentTimeMillis() - lastLoadTimeMs > STALE_AFTER_MS) {
            refreshFeed();
        }
    }

    /**
     * Loads the saved playback position of every feed item from the local
     * database (the same {@code stream_state} table the other lists read),
     * in the background, then rebinds the cards so watched videos get the
     * red progress bar under their thumbnail, YouTube-style. Videos that
     * were never watched (or whose history is off) simply get no bar.
     */
    private void loadWatchedStates() {
        if (binding == null
                || !DependentPreferenceHelper.getPositionsInListsEnabled(requireContext())) {
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        final List<HomeFeedItem> snapshot = new ArrayList<>(items);
        disposables.add(Single.fromCallable(() -> {
                    final AppDatabase database =
                            NewPipeDatabase.getInstance(appContext);
                    final Map<String, long[]> states = new HashMap<>();
                    for (final HomeFeedItem item : snapshot) {
                        if (item.isLive()) {
                            continue;
                        }
                        final List<StreamEntity> streams = database.streamDAO()
                                .getStream(YOUTUBE_SERVICE_ID, item.getUrl())
                                .blockingFirst();
                        if (streams.isEmpty() || streams.get(0).getDuration() <= 0) {
                            continue;
                        }
                        final StreamEntity stream = streams.get(0);
                        final List<StreamStateEntity> saved = database.streamStateDAO()
                                .getState(stream.getUid())
                                .blockingFirst();
                        if (!saved.isEmpty()) {
                            states.put(item.getVideoId(), new long[]{
                                    TimeUnit.MILLISECONDS.toSeconds(
                                            saved.get(0).getProgressMillis()),
                                    stream.getDuration()});
                        }
                    }
                    return states;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(states -> {
                    if (binding == null || adapter == null) {
                        return;
                    }
                    watchedStates.clear();
                    watchedStates.putAll(states);
                    adapter.notifyDataSetChanged();
                }, throwable -> Log.e(TAG, "Could not load watched states", throwable)));
    }

    @Override
    public void onDestroyView() {
        // clear(), not dispose(): the view can be recreated while this
        // fragment instance stays alive in the home pager.
        disposables.clear();
        binding = null;
        super.onDestroyView();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Feed loading
    //////////////////////////////////////////////////////////////////////////*/

    private void refreshFeed() {
        // A refresh always wins over whatever was in flight: without this a
        // stalled request kept loadingFeed true forever and every later
        // pull-to-refresh was silently ignored.
        disposables.clear();
        loadingFeed = false;
        loadFirstPage(true);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Look & feel (liquid glass)
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Floating glass cards: a little air at the sides and between cards.
     */
    private void setupCardSpacing() {
        final float density = getResources().getDisplayMetrics().density;
        final int side = Math.round(12 * density);
        final int gap = Math.round(8 * density);
        binding.homeFeedList.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull final Rect outRect, @NonNull final View view,
                                       @NonNull final RecyclerView parent,
                                       @NonNull final RecyclerView.State state) {
                outRect.left = side;
                outRect.right = side;
                outRect.bottom = gap;
            }
        });
    }

    /**
     * The floating glass top bar covers the first
     * {@code actionBarSize + 18dp} of the screen (MainActivity pads the list
     * by exactly that much). The pull-to-refresh spinner used to start at the
     * very top of the layout, i.e. hidden behind the bar, so a pull looked
     * like it did nothing. Start and rest it below the bar instead.
     */
    private void setupRefreshLayout() {
        final float density = getResources().getDisplayMetrics().density;
        final TypedValue actionBar = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.actionBarSize, actionBar, true);
        final int barInset = TypedValue.complexToDimensionPixelSize(actionBar.data,
                getResources().getDisplayMetrics()) + Math.round(18 * density);

        binding.homeFeedRefresh.setProgressViewOffset(true,
                barInset - Math.round(8 * density), barInset + Math.round(16 * density));
        binding.homeFeedRefresh.setDistanceToTriggerSync(Math.round(96 * density));
        binding.homeFeedRefresh.setProgressBackgroundColorSchemeColor(
                ThemeHelper.resolveColorFromAttr(requireContext(),
                        R.attr.card_item_background_color));
        binding.homeFeedRefresh.setColorSchemeColors(
                ThemeHelper.resolveColorFromAttr(requireContext(),
                        android.R.attr.colorAccent));
    }

    private void loadFirstPage(final boolean isRefresh) {
        if (loadingFeed) {
            if (binding != null) {
                binding.homeFeedRefresh.setRefreshing(false);
            }
            return;
        }
        loadingFeed = true;
        if (binding != null) {
            if (!isRefresh) {
                binding.homeFeedLoading.setVisibility(View.VISIBLE);
            }
            binding.homeFeedError.setVisibility(View.GONE);
        }
        disposables.add(Single.fromCallable(() -> feedClient.fetchFirstPage())
                .subscribeOn(Schedulers.io())
                .timeout(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    loadingFeed = false;
                    lastLoadTimeMs = System.currentTimeMillis();
                    if (binding == null) {
                        return;
                    }
                    binding.homeFeedLoading.setVisibility(View.GONE);
                    binding.homeFeedRefresh.setRefreshing(false);
                    items.clear();
                    seenVideoIds.clear();
                    watchedStates.clear();
                    continuationToken = page.getContinuationToken();
                    appendItems(page.getItems());
                    adapter.notifyDataSetChanged();
                    loadWatchedStates();
                    binding.homeFeedList.scrollToPosition(0);
                    binding.homeFeedErrorDetail.setText(
                            feedClient.getLastFailureDetail());
                    binding.homeFeedError.setVisibility(
                            items.isEmpty() ? View.VISIBLE : View.GONE);
                }, throwable -> {
                    loadingFeed = false;
                    if (binding == null) {
                        return;
                    }
                    Log.e(TAG, "Could not load the home feed", throwable);
                    binding.homeFeedLoading.setVisibility(View.GONE);
                    binding.homeFeedRefresh.setRefreshing(false);
                    if (items.isEmpty()) {
                        binding.homeFeedErrorDetail.setText(
                                feedClient.getLastFailureDetail());
                        binding.homeFeedError.setVisibility(View.VISIBLE);
                    }
                }));
    }

    private void loadMore() {
        if (loadingFeed || items.isEmpty() || continuationToken == null) {
            return;
        }
        loadingFeed = true;
        final String token = continuationToken;
        disposables.add(Single.fromCallable(() -> feedClient.fetchNextPage(token))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    loadingFeed = false;
                    lastLoadTimeMs = System.currentTimeMillis();
                    if (binding == null) {
                        return;
                    }
                    final int added = appendItems(page.getItems());
                    continuationToken = page.getContinuationToken();
                    loadWatchedStates();
                    if (added > 0) {
                        final int trimmed = trimOldItems();
                        if (trimmed > 0) {
                            adapter.notifyItemRangeRemoved(0, trimmed);
                        }
                        adapter.notifyItemRangeInserted(items.size() - added, added);
                    }
                }, throwable -> {
                    loadingFeed = false;
                    // Keep the token: the next scroll near the end retries.
                    Log.e(TAG, "Could not load more of the home feed", throwable);
                }));
    }

    private int appendItems(@NonNull final List<HomeFeedItem> newItems) {
        int added = 0;
        for (final HomeFeedItem item : newItems) {
            if (seenVideoIds.add(item.getVideoId())) {
                items.add(item);
                added++;
            }
        }
        return added;
    }

    /**
     * Bounds the endless feed's memory: once more than
     * {@value #MAX_KEPT_ITEMS} items are loaded, the oldest ones are
     * dropped from the front — but only while the user is far from the
     * top, and never including the first visible item itself, so the
     * current viewport is never disturbed.
     *
     * @return how many items were removed from the front of the list
     */
    private int trimOldItems() {
        if (items.size() <= MAX_KEPT_ITEMS || layoutManager == null) {
            return 0;
        }
        final int firstVisible = layoutManager.findFirstVisibleItemPosition();
        if (firstVisible <= TRIM_SAFE_FIRST_VISIBLE) {
            return 0;
        }
        final int trimCount = Math.min(items.size() - MAX_KEPT_ITEMS, firstVisible);
        for (int i = 0; i < trimCount; i++) {
            seenVideoIds.remove(items.get(i).getVideoId());
        }
        items.subList(0, trimCount).clear();
        return trimCount;
    }

    private void openInPlayer(@NonNull final HomeFeedItem item) {
        NavigationHelper.openVideoDetailFragment(requireContext(), getFM(),
                YOUTUBE_SERVICE_ID, item.getUrl(),
                item.getTitle() == null ? "" : item.getTitle(), null, false);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Card adapter (same big-card layout as the other lists)
    //////////////////////////////////////////////////////////////////////////*/

    private final class HomeFeedAdapter
            extends RecyclerView.Adapter<HomeFeedAdapter.HomeCardViewHolder> {

        @NonNull
        @Override
        public HomeCardViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                     final int viewType) {
            final ListStreamCardItemBinding itemBinding = ListStreamCardItemBinding
                    .inflate(LayoutInflater.from(parent.getContext()), parent, false);
            return new HomeCardViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull final HomeCardViewHolder holder,
                                     final int position) {
            final HomeFeedItem item = items.get(position);
            final ListStreamCardItemBinding card = holder.binding;
            card.itemVideoTitleView.setText(item.getTitle());
            card.itemUploaderView.setText(item.getUploaderName());
            card.itemAdditionalDetails.setText(buildDetailsLine(item));
            CoilHelper.INSTANCE.loadThumbnail(card.itemThumbnailView,
                    item.getThumbnailUrl());
            final long[] watched = watchedStates.get(item.getVideoId());
            if (watched != null && !item.isLive() && watched[1] > 0) {
                card.itemProgressView.setMax((int) watched[1]);
                card.itemProgressView.setProgress((int) watched[0]);
                card.itemProgressView.setVisibility(View.VISIBLE);
            } else {
                card.itemProgressView.setVisibility(View.GONE);
            }
            if (item.isLive()) {
                card.itemDurationView.setText(R.string.duration_live);
                card.itemDurationView.setBackgroundColor(ContextCompat.getColor(
                        requireContext(), R.color.live_duration_background_color));
                card.itemDurationView.setVisibility(View.VISIBLE);
            } else if (item.getDurationText() != null) {
                card.itemDurationView.setText(item.getDurationText());
                card.itemDurationView.setBackgroundColor(ContextCompat.getColor(
                        requireContext(), R.color.duration_background_color));
                card.itemDurationView.setVisibility(View.VISIBLE);
            } else {
                card.itemDurationView.setVisibility(View.GONE);
            }
            card.getRoot().setOnClickListener(v -> openInPlayer(item));
        }

        @Nullable
        private String buildDetailsLine(@NonNull final HomeFeedItem item) {
            final String views = item.getViewCountText();
            final String published = item.getPublishedTimeText();
            if (views != null && published != null) {
                return views + " • " + published;
            }
            return views != null ? views : published;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class HomeCardViewHolder extends RecyclerView.ViewHolder {
            private final ListStreamCardItemBinding binding;

            HomeCardViewHolder(@NonNull final ListStreamCardItemBinding itemBinding) {
                super(itemBinding.getRoot());
                binding = itemBinding;
            }
        }
    }
}
