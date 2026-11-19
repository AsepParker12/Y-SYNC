package org.schabi.newpipe.shorts;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentShortsBinding;
import org.schabi.newpipe.databinding.ItemShortBinding;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.image.CoilHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The Shorts screen: an endless, full-screen vertical feed.
 *
 * <p>Feed items come from {@link ShortsFeedClient}; each short is resolved to
 * a fixed-quality stream pair by {@link ShortsStreamResolver} and played by a
 * small pool of {@link ExoPlayer}s sliding over the pager window (previous,
 * current, next and next+1), so swiping feels instant while only a handful of
 * players and a few seconds of buffered data per waiting player are kept.
 * Entering the screen pauses the main player; leaving it resumes the main
 * player if it was playing before.</p>
 */
public final class ShortsFragment extends BaseFragment {
    private static final int PLAYER_POOL_SIZE = 4;
    private static final int PREFETCH_AHEAD = 5;
    private static final int LOAD_MORE_THRESHOLD = 4;
    private static final int YOUTUBE_SERVICE_ID = 0;

    private final List<ShortsItem> items = new ArrayList<>();
    private final Set<String> seenVideoIds = new HashSet<>();
    private final Set<String> renderedVideoIds = new HashSet<>();
    private final Set<String> retriedAfter403 = new HashSet<>();
    private final CompositeDisposable disposables = new CompositeDisposable();

    private final ExoPlayer[] playerPool = new ExoPlayer[PLAYER_POOL_SIZE];
    private final int[] playerPositions = new int[PLAYER_POOL_SIZE];
    private final String[] playerVideoIds = new String[PLAYER_POOL_SIZE];

    private FragmentShortsBinding binding;
    private ShortsPagerAdapter adapter;
    private ShortsFeedClient feedClient;
    private ShortsStreamResolver resolver;
    private DataSource.Factory httpDataSourceFactory;

    @Nullable
    private String continuationToken;
    private boolean loadingFeed;
    private int currentPosition = -1;
    private boolean userPaused;
    private boolean fragmentResumed;
    private boolean wasMainPlayerPlaying;
    private boolean mainPlayerPausedByUs;
    private boolean openedInFullPlayer;

    public static ShortsFragment newInstance() {
        return new ShortsFragment();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentShortsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setTitle(getString(R.string.shorts_title));
        for (int i = 0; i < PLAYER_POOL_SIZE; i++) {
            playerPositions[i] = -1;
        }
        feedClient = new ShortsFeedClient();
        resolver = new ShortsStreamResolver();
        httpDataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(DownloaderImpl.USER_AGENT)
                .setAllowCrossProtocolRedirects(true);

        adapter = new ShortsPagerAdapter();
        binding.shortsPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        binding.shortsPager.setOffscreenPageLimit(2);
        binding.shortsPager.setAdapter(adapter);
        binding.shortsPager.registerOnPageChangeCallback(
                new ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(final int position) {
                        onShortSelected(position);
                    }
                });
        binding.shortsRetry.setOnClickListener(v -> loadFirstPage());

        // The main player and the Shorts players must never play at the same
        // time: pause the main player for the duration of this screen.
        wasMainPlayerPlaying = PlayerHolder.getInstance().isPlaying();
        if (wasMainPlayerPlaying) {
            PlayerHolder.getInstance().pausePlayer();
            mainPlayerPausedByUs = true;
        }
        if (items.isEmpty()) {
            loadFirstPage();
        } else {
            binding.shortsLoading.setVisibility(View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        fragmentResumed = true;
        syncPlayerPool();
    }

    @Override
    public void onPause() {
        fragmentResumed = false;
        for (final ExoPlayer player : playerPool) {
            if (player != null) {
                player.setPlayWhenReady(false);
            }
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        for (int i = 0; i < PLAYER_POOL_SIZE; i++) {
            if (playerPool[i] != null) {
                playerPool[i].release();
                playerPool[i] = null;
            }
            playerPositions[i] = -1;
            playerVideoIds[i] = null;
        }
        if (resolver != null) {
            resolver.shutdown();
        }
        // clear(), not dispose(): the view can be recreated while this
        // fragment instance stays on the back stack.
        disposables.clear();
        if (mainPlayerPausedByUs && !openedInFullPlayer && wasMainPlayerPlaying) {
            PlayerHolder.getInstance().playPlayer();
        }
        binding = null;
        super.onDestroyView();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Feed loading
    //////////////////////////////////////////////////////////////////////////*/

    private void loadFirstPage() {
        if (loadingFeed) {
            return;
        }
        loadingFeed = true;
        binding.shortsLoading.setVisibility(View.VISIBLE);
        binding.shortsError.setVisibility(View.GONE);
        disposables.add(Single.fromCallable(() -> feedClient.fetchFirstPage())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    loadingFeed = false;
                    if (binding == null) {
                        return;
                    }
                    binding.shortsLoading.setVisibility(View.GONE);
                    continuationToken = page.getContinuationToken();
                    appendItems(page.getItems());
                    if (items.isEmpty()) {
                        binding.shortsError.setVisibility(View.VISIBLE);
                    } else if (currentPosition < 0) {
                        currentPosition = 0;
                        syncPlayerPool();
                        prefetchAhead();
                    }
                }, throwable -> {
                    loadingFeed = false;
                    if (binding == null) {
                        return;
                    }
                    Log.e(TAG, "Could not load the Shorts feed", throwable);
                    binding.shortsLoading.setVisibility(View.GONE);
                    binding.shortsError.setVisibility(View.VISIBLE);
                }));
    }

    private void loadMore() {
        if (loadingFeed || items.isEmpty()) {
            return;
        }
        loadingFeed = true;
        final String token = continuationToken;
        final Single<ShortsFeedClient.FeedPage> single = token != null
                ? Single.fromCallable(() -> feedClient.fetchSequence(token))
                : Single.fromCallable(() -> feedClient.fetchFirstPage());
        disposables.add(single
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    loadingFeed = false;
                    if (binding == null) {
                        return;
                    }
                    final int added = appendItems(page.getItems());
                    continuationToken = page.getContinuationToken();
                    if (token != null && (added == 0 || continuationToken == null)) {
                        // The token went stale: drop it and chain a fresh
                        // sequence so the feed keeps going.
                        continuationToken = null;
                        loadMore();
                    }
                }, throwable -> {
                    loadingFeed = false;
                    Log.e(TAG, "Could not load more Shorts", throwable);
                    if (token != null && binding != null) {
                        // Same stale-token recovery as above.
                        continuationToken = null;
                        loadMore();
                    }
                }));
    }

    private int appendItems(@NonNull final List<ShortsItem> newItems) {
        final int start = items.size();
        int added = 0;
        for (final ShortsItem item : newItems) {
            if (seenVideoIds.add(item.getVideoId())) {
                items.add(item);
                added++;
            }
        }
        if (added > 0) {
            adapter.notifyItemRangeInserted(start, added);
        }
        return added;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Player pool
    //////////////////////////////////////////////////////////////////////////*/

    private void onShortSelected(final int position) {
        currentPosition = position;
        userPaused = false;
        syncPlayerPool();
        prefetchAhead();
        if (position >= items.size() - LOAD_MORE_THRESHOLD) {
            loadMore();
        }
    }

    private void prefetchAhead() {
        if (currentPosition < 0) {
            return;
        }
        final int last = Math.min(items.size() - 1, currentPosition + PREFETCH_AHEAD);
        for (int i = currentPosition + 1; i <= last; i++) {
            resolver.prefetch(items.get(i).getVideoId());
        }
        if (currentPosition > 0) {
            resolver.prefetch(items.get(currentPosition - 1).getVideoId());
        }
    }

    /**
     * Keeps the player pool aligned with the pager window around the current
     * position (previous, current, next, next+1): assigns players to window
     * positions, loads resolved media into them and starts the current one.
     */
    private void syncPlayerPool() {
        if (binding == null || resolver == null || items.isEmpty() || currentPosition < 0) {
            return;
        }
        final int from = Math.max(0, currentPosition - 1);
        final int to = Math.min(items.size() - 1, currentPosition + 2);

        for (int slot = 0; slot < PLAYER_POOL_SIZE; slot++) {
            final int position = playerPositions[slot];
            if (position != -1 && (position < from || position > to)) {
                if (playerPool[slot] != null) {
                    playerPool[slot].setPlayWhenReady(false);
                }
                playerPositions[slot] = -1;
                playerVideoIds[slot] = null;
            }
        }
        for (int position = from; position <= to; position++) {
            if (slotForPosition(position) != -1) {
                continue;
            }
            final int slot = freeSlot();
            if (slot == -1) {
                break;
            }
            playerPositions[slot] = position;
        }
        for (int slot = 0; slot < PLAYER_POOL_SIZE; slot++) {
            final int position = playerPositions[slot];
            if (position == -1) {
                continue;
            }
            final ShortsItem item = items.get(position);
            final ShortsStreamResolver.ResolvedShort resolved =
                    resolver.getCached(item.getVideoId());
            if (resolved == null) {
                resolver.resolve(item.getVideoId(), false, resolveCallback);
                continue;
            }
            if (!item.getVideoId().equals(playerVideoIds[slot])) {
                final MediaSource mediaSource = buildMediaSource(resolved);
                if (mediaSource != null) {
                    final ExoPlayer player = getOrCreatePlayer(slot);
                    player.setMediaSource(mediaSource);
                    player.prepare();
                    playerVideoIds[slot] = item.getVideoId();
                }
            }
            if (playerPool[slot] != null) {
                playerPool[slot].setPlayWhenReady(position == currentPosition
                        && fragmentResumed && !userPaused);
            }
        }
        refreshVisibleHolders();
    }

    private int slotForPosition(final int position) {
        for (int slot = 0; slot < PLAYER_POOL_SIZE; slot++) {
            if (playerPositions[slot] == position) {
                return slot;
            }
        }
        return -1;
    }

    private int freeSlot() {
        for (int slot = 0; slot < PLAYER_POOL_SIZE; slot++) {
            if (playerPositions[slot] == -1) {
                return slot;
            }
        }
        return -1;
    }

    @Nullable
    private ExoPlayer playerForPosition(final int position) {
        final int slot = slotForPosition(position);
        return slot == -1 ? null : playerPool[slot];
    }

    @NonNull
    private ExoPlayer getOrCreatePlayer(final int slot) {
        if (playerPool[slot] == null) {
            final LoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_000, 15_000, 500, 1_000)
                    .build();
            final ExoPlayer player = new ExoPlayer.Builder(requireContext())
                    .setLoadControl(loadControl)
                    .build();
            player.setRepeatMode(Player.REPEAT_MODE_ONE);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(@NonNull final PlaybackException error) {
                    handlePlayerError(slot, error);
                }

                @Override
                public void onRenderedFirstFrame() {
                    final int position = playerPositions[slot];
                    if (position >= 0 && position < items.size()) {
                        renderedVideoIds.add(items.get(position).getVideoId());
                        refreshVisibleHolders();
                    }
                }
            });
            playerPool[slot] = player;
        }
        return playerPool[slot];
    }

    private void handlePlayerError(final int slot, @NonNull final PlaybackException error) {
        final String videoId = playerVideoIds[slot];
        Log.e(TAG, "Short playback failed for " + videoId, error);
        if (videoId == null || !isHttp403(error) || !retriedAfter403.add(videoId)) {
            return;
        }
        // Stream URLs expire: re-extract this short once and reload its player.
        playerVideoIds[slot] = null;
        resolver.invalidate(videoId);
        resolver.resolve(videoId, true, resolveCallback);
    }

    private static boolean isHttp403(@NonNull final Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException
                    && ((HttpDataSource.InvalidResponseCodeException) cause).responseCode
                    == 403) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private final ShortsStreamResolver.Callback resolveCallback =
            new ShortsStreamResolver.Callback() {
                @Override
                public void onResolved(@NonNull final String videoId,
                                       @NonNull final ShortsStreamResolver.ResolvedShort
                                               resolved) {
                    final StreamInfo info = resolved.getInfo();
                    for (final ShortsItem item : items) {
                        if (item.getVideoId().equals(videoId)) {
                            item.setTitle(info.getName());
                            item.setUploaderName(info.getUploaderName());
                            break;
                        }
                    }
                    syncPlayerPool();
                }

                @Override
                public void onError(@NonNull final String videoId,
                                    @NonNull final Throwable error) {
                    if (DEBUG) {
                        Log.d(TAG, "Could not resolve short " + videoId, error);
                    }
                    refreshVisibleHolders();
                }
            };

    /*//////////////////////////////////////////////////////////////////////////
    // Media sources
    //////////////////////////////////////////////////////////////////////////*/

    @Nullable
    private MediaSource buildMediaSource(
            @NonNull final ShortsStreamResolver.ResolvedShort resolved) {
        final VideoStream videoStream = resolved.getVideoStream();
        final Stream audioStream = resolved.getAudioStream();
        final long duration = resolved.getInfo().getDuration();
        if (videoStream != null && audioStream != null) {
            try {
                final MediaSource videoSource = buildDashMediaSource(videoStream, duration);
                final MediaSource audioSource = buildDashMediaSource(audioStream, duration);
                if (videoSource != null && audioSource != null) {
                    return new MergingMediaSource(true, videoSource, audioSource);
                }
            } catch (final Exception e) {
                Log.w(TAG, "Could not build merged DASH source, falling back", e);
            }
        }
        final VideoStream progressiveStream = resolved.getProgressiveStream();
        if (progressiveStream != null) {
            return new ProgressiveMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(progressiveStream.getContent()));
        }
        if (videoStream != null) {
            try {
                return buildDashMediaSource(videoStream, duration);
            } catch (final Exception e) {
                Log.w(TAG, "Could not build video-only DASH source", e);
            }
        }
        return null;
    }

    @Nullable
    private MediaSource buildDashMediaSource(@NonNull final Stream stream,
                                             final long durationSeconds)
            throws IOException, CreationException {
        final String manifestString = YoutubeProgressiveDashManifestCreator
                .fromProgressiveStreamingUrl(stream.getContent(),
                        Objects.requireNonNull(stream.getItagItem()), durationSeconds);
        final DashManifest manifest = new DashManifestParser().parse(
                Uri.parse(stream.getManifestUrl() == null ? "" : stream.getManifestUrl()),
                new ByteArrayInputStream(manifestString.getBytes(StandardCharsets.UTF_8)));
        return new DashMediaSource.Factory(httpDataSourceFactory)
                .createMediaSource(manifest, MediaItem.fromUri(stream.getContent()));
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Page interactions
    //////////////////////////////////////////////////////////////////////////*/

    private void onPageClicked(final int position) {
        if (position != currentPosition) {
            return;
        }
        userPaused = !userPaused;
        final ExoPlayer player = playerForPosition(position);
        if (player != null) {
            player.setPlayWhenReady(!userPaused && fragmentResumed);
        }
        refreshVisibleHolders();
    }

    private void openInFullPlayer(@NonNull final ShortsItem item) {
        // The watch page takes over playback from here; do not resume the old
        // main player state when this screen is closed afterwards.
        openedInFullPlayer = true;
        userPaused = true;
        final ExoPlayer player = playerForPosition(currentPosition);
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        NavigationHelper.openVideoDetailFragment(requireContext(),
                getParentFragmentManager(), YOUTUBE_SERVICE_ID, item.getUrl(),
                item.getTitle() == null ? "" : item.getTitle(), null, false);
    }

    private void refreshVisibleHolders() {
        if (binding == null) {
            return;
        }
        if (binding.shortsPager.getChildAt(0) == null) {
            return;
        }
        final RecyclerView recyclerView =
                (RecyclerView) binding.shortsPager.getChildAt(0);
        // First pass: detach players from holders they no longer belong to, so
        // a player is never bound to two surfaces at once.
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            final RecyclerView.ViewHolder viewHolder =
                    recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
            if (viewHolder instanceof ShortsPagerAdapter.ShortViewHolder) {
                final ShortsPagerAdapter.ShortViewHolder holder =
                        (ShortsPagerAdapter.ShortViewHolder) viewHolder;
                final int position = holder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION
                        && holder.binding.shortsPlayerView.getPlayer()
                        != playerForPosition(position)) {
                    holder.binding.shortsPlayerView.setPlayer(null);
                }
            }
        }
        // Second pass: attach the right players and refresh the overlays.
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            final RecyclerView.ViewHolder viewHolder =
                    recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
            if (viewHolder instanceof ShortsPagerAdapter.ShortViewHolder) {
                final ShortsPagerAdapter.ShortViewHolder holder =
                        (ShortsPagerAdapter.ShortViewHolder) viewHolder;
                final int position = holder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION || position >= items.size()) {
                    continue;
                }
                final ExoPlayer player = playerForPosition(position);
                if (holder.binding.shortsPlayerView.getPlayer() != player) {
                    holder.binding.shortsPlayerView.setPlayer(player);
                }
                final ShortsItem item = items.get(position);
                holder.binding.shortsTitle.setText(item.getTitle());
                holder.binding.shortsChannel.setText(item.getUploaderName());
                holder.binding.shortsThumbnail.setVisibility(
                        renderedVideoIds.contains(item.getVideoId())
                                ? View.GONE : View.VISIBLE);
                holder.binding.shortsProgress.setVisibility(
                        resolver != null && resolver.getCached(item.getVideoId()) == null
                                ? View.VISIBLE : View.GONE);
                holder.binding.shortsPauseIcon.setVisibility(
                        position == currentPosition && userPaused
                                ? View.VISIBLE : View.GONE);
            }
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Pager adapter
    //////////////////////////////////////////////////////////////////////////*/

    private final class ShortsPagerAdapter
            extends RecyclerView.Adapter<ShortsPagerAdapter.ShortViewHolder> {

        @NonNull
        @Override
        public ShortViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                  final int viewType) {
            final ItemShortBinding itemBinding = ItemShortBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new ShortViewHolder(itemBinding);
        }

        @Override
        public void onBindViewHolder(@NonNull final ShortViewHolder holder,
                                     final int position) {
            final ShortsItem item = items.get(position);
            holder.binding.shortsTitle.setText(item.getTitle());
            holder.binding.shortsChannel.setText(item.getUploaderName());
            CoilHelper.INSTANCE.loadThumbnail(holder.binding.shortsThumbnail,
                    item.getThumbnailUrl());
            holder.binding.shortsThumbnail.setVisibility(
                    renderedVideoIds.contains(item.getVideoId())
                            ? View.GONE : View.VISIBLE);
            holder.binding.shortsProgress.setVisibility(
                    resolver != null && resolver.getCached(item.getVideoId()) == null
                            ? View.VISIBLE : View.GONE);
            holder.binding.shortsPauseIcon.setVisibility(
                    position == currentPosition && userPaused
                            ? View.VISIBLE : View.GONE);
            holder.binding.shortsPlayerView.setPlayer(playerForPosition(position));
            holder.binding.getRoot().setOnClickListener(
                    v -> onPageClicked(holder.getBindingAdapterPosition()));
            holder.binding.shortsOpenButton.setOnClickListener(
                    v -> openInFullPlayer(item));
        }

        @Override
        public void onViewRecycled(@NonNull final ShortViewHolder holder) {
            holder.binding.shortsPlayerView.setPlayer(null);
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class ShortViewHolder extends RecyclerView.ViewHolder {
            private final ItemShortBinding binding;

            ShortViewHolder(@NonNull final ItemShortBinding itemBinding) {
                super(itemBinding.getRoot());
                binding = itemBinding;
            }
        }
    }
}
