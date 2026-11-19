package org.schabi.newpipe.homefeed;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentHomeFeedBinding;
import org.schabi.newpipe.databinding.ListStreamCardItemBinding;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.image.CoilHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final int YOUTUBE_SERVICE_ID = 0;
    private static final long STALE_AFTER_MS = 15 * 60 * 1000L;

    private final List<HomeFeedItem> items = new ArrayList<>();
    private final Set<String> seenVideoIds = new HashSet<>();
    private final CompositeDisposable disposables = new CompositeDisposable();

    private FragmentHomeFeedBinding binding;
    private HomeFeedAdapter adapter;
    private HomeFeedClient feedClient;

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
        feedClient = new HomeFeedClient();

        adapter = new HomeFeedAdapter();
        final LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        binding.homeFeedList.setLayoutManager(layoutManager);
        binding.homeFeedList.setAdapter(adapter);
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
        // Coming back to a home feed that has gone stale: fetch a fresh
        // first page instead of showing the old one forever.
        if (!items.isEmpty() && !loadingFeed
                && System.currentTimeMillis() - lastLoadTimeMs > STALE_AFTER_MS) {
            refreshFeed();
        }
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
        loadFirstPage(true);
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
                    continuationToken = page.getContinuationToken();
                    appendItems(page.getItems());
                    adapter.notifyDataSetChanged();
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
                    final int start = items.size();
                    final int added = appendItems(page.getItems());
                    continuationToken = page.getContinuationToken();
                    if (added > 0) {
                        adapter.notifyItemRangeInserted(start, added);
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
            card.itemProgressView.setVisibility(View.GONE);
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
