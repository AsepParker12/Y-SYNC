package org.schabi.newpipe.livechat;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.FragmentLiveChatBinding;
import org.schabi.newpipe.livechat.LiveChatClient.HttpStatusException;
import org.schabi.newpipe.livechat.LiveChatClient.LiveChatPage;
import org.schabi.newpipe.livechat.LiveChatClient.LiveChatSession;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Read-only live chat for a livestream, shown as a tab on the watch page.
 * Owns the poll loop around {@link LiveChatClient}: bootstrap once, then
 * poll, wait the server-chosen {@code timeoutMs}, poll again.
 *
 * <p>The loop runs only while this tab is the selected page and the
 * fragment is resumed — the pager uses
 * {@code BEHAVIOR_SET_USER_VISIBLE_HINT}, so {@link #getUserVisibleHint()}
 * is the selection signal. All list mutations happen on the main thread;
 * network and parsing happen on a single background executor.</p>
 */
public final class LiveChatFragment extends BaseFragment {
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private static final long ERROR_BACKOFF_BASE_MS = 2000L;
    private static final long ERROR_BACKOFF_MAX_MS = 30000L;

    private static final String ARG_SERVICE_ID = "live_chat_service_id";
    private static final String ARG_URL = "live_chat_url";
    private static final String ARG_VIDEO_ID = "live_chat_video_id";

    private int serviceId;
    private String url;
    private String videoId;

    private FragmentLiveChatBinding binding;
    // The adapter is a fragment field, not a view field: the pager destroys
    // far-away tabs' views, and the message history must survive that.
    private final LiveChatAdapter adapter = new LiveChatAdapter();
    private LinearLayoutManager layoutManager;

    private final LiveChatClient client = new LiveChatClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private ScheduledExecutorService executor;
    @Nullable
    private LiveChatSession session;

    private boolean loopRunning;
    private int loopGeneration;
    private boolean chatEnded;
    private boolean rebootstrapped;
    private int consecutiveErrors;

    // Auto-scroll state: a fresh chat view counts as at the bottom, so
    // sticking starts engaged; it disengages only when the user actually
    // scrolls away from the bottom, and re-engages when they scroll back
    // (or tap the jump-to-latest button). While engaged, every poll
    // batch moves the list to the newest message.
    private boolean stickingToBottom = true;

    // Whether the player sheet hosting this tab is expanded. Polling only
    // makes sense while the watch page is actually on screen: collapsed
    // to the mini player, the chat is invisible and polling just burns
    // data and battery. The host reports the state through
    // setSheetExpanded; expanded is the default until it says otherwise.
    private boolean sheetExpanded = true;

    public static LiveChatFragment getInstance(final int serviceId, final String url,
                                               final String videoId) {
        final LiveChatFragment instance = new LiveChatFragment();
        // The values also go into the arguments: plain fields do not
        // survive recreation (rotation restores a fresh instance whose
        // fields are null, and the pager never asks for a new fragment),
        // which left the restored tab on "not available" forever.
        final Bundle args = new Bundle();
        args.putInt(ARG_SERVICE_ID, serviceId);
        args.putString(ARG_URL, url);
        args.putString(ARG_VIDEO_ID, videoId);
        instance.setArguments(args);
        instance.setInitialData(serviceId, url, videoId);
        return instance;
    }

    private void setInitialData(final int serviceIdValue, final String urlValue,
                                final String videoIdValue) {
        this.serviceId = serviceIdValue;
        this.url = urlValue;
        this.videoId = videoIdValue;
    }

    public int getServiceId() {
        return serviceId;
    }

    @Nullable
    public String getVideoId() {
        return videoId;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The arguments are the copy of the initial data that survives
        // recreation; the fields stay the working copies.
        final Bundle args = getArguments();
        if (args != null) {
            setInitialData(args.getInt(ARG_SERVICE_ID), args.getString(ARG_URL),
                    args.getString(ARG_VIDEO_ID));
        }
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        binding = FragmentLiveChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        layoutManager = new LinearLayoutManager(requireContext());
        binding.liveChatList.setLayoutManager(layoutManager);
        binding.liveChatList.setAdapter(adapter);
        binding.liveChatList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull final RecyclerView recyclerView,
                                   final int dx, final int dy) {
                // Sticking follows where the user actually is: seeing
                // the last item (re-)engages it, anything above disengages
                // it. Programmatic scrolls to the bottom land on the last
                // item, so they keep it engaged.
                stickingToBottom = isAtBottom();
                if (stickingToBottom) {
                    binding.liveChatJumpLatest.setVisibility(View.GONE);
                }
            }
        });
        binding.liveChatRetry.setOnClickListener(view -> onRetryClicked());
        binding.liveChatJumpLatest.setOnClickListener(view -> {
            stickingToBottom = true;
            scrollToBottom();
            binding.liveChatJumpLatest.setVisibility(View.GONE);
        });
        if (adapter.getItemCount() > 0 && stickingToBottom) {
            // The view was recreated (the pager destroys far-away tabs)
            // with the history intact: a fresh layout manager starts at
            // the top, so jump back to the newest message after layout.
            binding.liveChatList.post(this::scrollToBottom);
        }

        if (videoId == null) {
            // Fragment recreated without its data (e.g. process death):
            // there is nothing to bootstrap, degrade to the empty state.
            showState(R.string.live_chat_not_available, false);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        maybeStartLoop();
    }

    @Override
    public void onPause() {
        stopLoop();
        super.onPause();
    }

    @Override
    public void setUserVisibleHint(final boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isVisibleToUser) {
            maybeStartLoop();
        } else {
            stopLoop();
        }
    }

    @Override
    public void onDestroyView() {
        stopLoop();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        binding = null;
        super.onDestroyView();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Poll loop (background thread)
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Called by the hosting watch page when its player sheet expands or
     * collapses to the mini player: the poll loop runs only while the
     * sheet is expanded and the chat could actually be seen.
     */
    public void setSheetExpanded(final boolean expanded) {
        if (sheetExpanded == expanded) {
            return;
        }
        sheetExpanded = expanded;
        if (expanded) {
            maybeStartLoop();
        } else {
            stopLoop();
        }
    }

    private void maybeStartLoop() {
        if (binding == null || videoId == null || loopRunning || chatEnded
                || !sheetExpanded || !isResumed() || !getUserVisibleHint()) {
            return;
        }
        loopRunning = true;
        final int generation = ++loopGeneration;
        ensureExecutor().execute(() -> pollStep(generation));
    }

    private void stopLoop() {
        loopRunning = false;
        loopGeneration++;
    }

    @NonNull
    private ScheduledExecutorService ensureExecutor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor();
        }
        return executor;
    }

    private void scheduleNext(final int generation, final long delayMs) {
        try {
            ensureExecutor().schedule(() -> pollStep(generation),
                    Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
        } catch (final RuntimeException e) {
            // Executor was shut down while scheduling (view destroyed):
            // the loop is dead anyway, nothing to recover.
            if (DEBUG) {
                Log.d(TAG, "Could not schedule next live chat poll", e);
            }
        }
    }

    private void pollStep(final int generation) {
        if (generation != loopGeneration) {
            return;
        }
        try {
            if (session == null) {
                final Optional<LiveChatSession> bootstrapped = client.bootstrap(videoId);
                if (generation != loopGeneration) {
                    return;
                }
                if (!bootstrapped.isPresent()) {
                    mainHandler.post(() -> showUnavailable(generation, false));
                    return;
                }
                session = bootstrapped.get();
                if (session.isReplay()) {
                    // Replay chat needs player-position sync; not supported in v1.
                    mainHandler.post(() -> showUnavailable(generation, true));
                    return;
                }
            }
            final LiveChatPage page = client.poll(session);
            consecutiveErrors = 0;
            mainHandler.post(() -> applyPage(generation, page));
            if (!page.isEnded()) {
                scheduleNext(generation, page.getTimeoutMs());
            }
        } catch (final HttpStatusException e) {
            if (e.getStatusCode() == 400 && session != null && !rebootstrapped) {
                // Stale continuation: re-bootstrap once and carry on.
                rebootstrapped = true;
                session = null;
                scheduleNext(generation, 0L);
            } else {
                handlePollError(generation, e);
            }
        } catch (final IOException | RuntimeException e) {
            handlePollError(generation, e);
        }
    }

    private void handlePollError(final int generation, @NonNull final Exception error) {
        if (DEBUG) {
            Log.d(TAG, "Live chat poll failed", error);
        }
        consecutiveErrors++;
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            mainHandler.post(() -> showError(generation));
        } else {
            final long backoff = Math.min(
                    ERROR_BACKOFF_BASE_MS * consecutiveErrors, ERROR_BACKOFF_MAX_MS);
            scheduleNext(generation, backoff);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // UI updates (main thread)
    //////////////////////////////////////////////////////////////////////////*/

    private void applyPage(final int generation, @NonNull final LiveChatPage page) {
        if (binding == null || generation != loopGeneration) {
            return;
        }
        binding.liveChatLoading.setVisibility(View.GONE);

        final boolean wasEmpty = adapter.getItemCount() == 0;
        final int added = adapter.addItems(page.getItems());
        for (final Map.Entry<String, String> deleted : page.getDeletedMessages().entrySet()) {
            final String text = deleted.getValue() != null
                    ? deleted.getValue() : getString(R.string.live_chat_message_deleted);
            adapter.markDeleted(deleted.getKey(), text);
        }
        if (added > 0) {
            if (wasEmpty) {
                // First batch: open on the newest message, like YouTube.
                // Scroll once now and once more after the pending layout
                // pass — the first layout of a fresh list can otherwise
                // leave the pending scroll short of the end.
                scrollToBottom();
                binding.liveChatList.post(this::scrollToBottom);
            } else if (stickingToBottom) {
                scrollToBottom();
            } else {
                binding.liveChatJumpLatest.setVisibility(View.VISIBLE);
            }
        }
        if (page.isEnded()) {
            onChatEnded();
        }
    }

    private void onChatEnded() {
        chatEnded = true;
        stopLoop();
        if (binding == null) {
            return;
        }
        binding.liveChatLoading.setVisibility(View.GONE);
        if (adapter.getItemCount() == 0) {
            showState(R.string.live_chat_ended, false);
        } else {
            binding.liveChatEndedBanner.setVisibility(View.VISIBLE);
        }
    }

    private void showUnavailable(final int generation, final boolean replay) {
        if (binding == null || generation != loopGeneration) {
            return;
        }
        stopLoop();
        showState(replay ? R.string.live_chat_replay_not_supported
                : R.string.live_chat_not_available, false);
    }

    private void showError(final int generation) {
        if (binding == null || generation != loopGeneration) {
            return;
        }
        stopLoop();
        showState(R.string.live_chat_load_error, true);
    }

    private void showState(final int textRes, final boolean showRetry) {
        binding.liveChatLoading.setVisibility(View.GONE);
        binding.liveChatState.setVisibility(View.VISIBLE);
        binding.liveChatStateText.setText(textRes);
        binding.liveChatRetry.setVisibility(showRetry ? View.VISIBLE : View.GONE);
    }

    private void onRetryClicked() {
        if (binding == null) {
            return;
        }
        binding.liveChatState.setVisibility(View.GONE);
        binding.liveChatLoading.setVisibility(View.VISIBLE);
        session = null;
        consecutiveErrors = 0;
        rebootstrapped = false;
        chatEnded = false;
        loopRunning = true;
        final int generation = ++loopGeneration;
        ensureExecutor().execute(() -> pollStep(generation));
    }

    private boolean isAtBottom() {
        if (layoutManager == null || adapter.getItemCount() == 0) {
            return true;
        }
        return layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 1;
    }

    private void scrollToBottom() {
        if (binding != null && adapter.getItemCount() > 0) {
            binding.liveChatList.scrollToPosition(adapter.getItemCount() - 1);
        }
    }
}
