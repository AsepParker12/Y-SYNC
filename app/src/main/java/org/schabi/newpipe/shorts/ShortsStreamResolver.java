package org.schabi.newpipe.shorts;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves Shorts to playable streams through the extractor, with a small
 * cache and a bounded prefetch queue.
 *
 * <p>Extraction is the slow part of the feed (seconds per short), so callers
 * prefetch the upcoming shorts; at most {@value #MAX_CONCURRENT_EXTRACTIONS}
 * extractions run at the same time and queued requests are started
 * nearest-first. Results are always delivered on the main thread.</p>
 */
public final class ShortsStreamResolver {
    private static final boolean DEBUG = MainActivity.DEBUG;
    private static final String TAG = ShortsStreamResolver.class.getSimpleName();

    private static final int MAX_CONCURRENT_EXTRACTIONS = 3;
    private static final int MAX_CACHED_RESULTS = 60;
    private static final int MAX_SHORT_SIDE_PX = 720;
    private static final int YOUTUBE_SERVICE_ID = 0;

    public interface Callback {
        void onResolved(@NonNull String videoId, @NonNull ResolvedShort resolved);

        void onError(@NonNull String videoId, @NonNull Throwable error);
    }

    /**
     * The streams chosen for one short: a fixed-quality video-only stream plus
     * the best audio stream (the two are merged at playback time), or, as a
     * fallback, a muxed progressive stream.
     */
    public static final class ResolvedShort {
        private final StreamInfo info;
        @Nullable
        private final VideoStream videoStream;
        @Nullable
        private final AudioStream audioStream;
        @Nullable
        private final VideoStream progressiveStream;

        ResolvedShort(@NonNull final StreamInfo info,
                      @Nullable final VideoStream videoStream,
                      @Nullable final AudioStream audioStream,
                      @Nullable final VideoStream progressiveStream) {
            this.info = info;
            this.videoStream = videoStream;
            this.audioStream = audioStream;
            this.progressiveStream = progressiveStream;
        }

        @NonNull
        public StreamInfo getInfo() {
            return info;
        }

        @Nullable
        public VideoStream getVideoStream() {
            return videoStream;
        }

        @Nullable
        public AudioStream getAudioStream() {
            return audioStream;
        }

        @Nullable
        public VideoStream getProgressiveStream() {
            return progressiveStream;
        }
    }

    private static final class Task {
        private final String videoId;
        private boolean force;

        Task(@NonNull final String videoId, final boolean force) {
            this.videoId = videoId;
            this.force = force;
        }
    }

    private final ExecutorService executor =
            Executors.newFixedThreadPool(MAX_CONCURRENT_EXTRACTIONS);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Bounded on purpose: a full StreamInfo is heavy, and an endless
    // scrolling session must not accumulate results without limit.
    // Access-ordered, so the shorts around the user's position (the ones
    // read through getCached on every pool sync) stay in the cache.
    private final Map<String, ResolvedShort> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, ResolvedShort>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<String, ResolvedShort> eldest) {
                    return size() > MAX_CACHED_RESULTS;
                }
            });
    // The queue, the pending callbacks and the running counter are all guarded
    // by synchronizing on the queue.
    private final Deque<Task> queue = new ArrayDeque<>();
    private final Map<String, List<Callback>> pendingCallbacks = new HashMap<>();
    private final Map<String, Task> scheduledTasks = new HashMap<>();
    private int running;
    private volatile boolean shutdown;

    /**
     * Resolves a short, delivering a cached result when available. Forced
     * requests (e.g. after a 403) jump the queue and bypass the cache.
     */
    public void resolve(@NonNull final String videoId, final boolean force,
                        @NonNull final Callback callback) {
        if (!force) {
            final ResolvedShort cached = cache.get(videoId);
            if (cached != null) {
                mainHandler.post(() -> {
                    if (!shutdown) {
                        callback.onResolved(videoId, cached);
                    }
                });
                return;
            }
        }
        enqueue(videoId, force, callback);
    }

    /**
     * Starts resolving a short in the background without reporting back, so
     * that it is cached by the time the user swipes to it.
     */
    public void prefetch(@NonNull final String videoId) {
        if (!cache.containsKey(videoId)) {
            enqueue(videoId, false, null);
        }
    }

    public void invalidate(@NonNull final String videoId) {
        cache.remove(videoId);
    }

    @Nullable
    public ResolvedShort getCached(@NonNull final String videoId) {
        return cache.get(videoId);
    }

    public void shutdown() {
        // The flag and the executor shutdown happen under the queue lock,
        // the same lock pump() submits tasks under: a task can therefore
        // never be submitted to an already-terminated executor.
        synchronized (queue) {
            shutdown = true;
            executor.shutdownNow();
            queue.clear();
            pendingCallbacks.clear();
            scheduledTasks.clear();
        }
    }

    private void enqueue(@NonNull final String videoId, final boolean force,
                         @Nullable final Callback callback) {
        synchronized (queue) {
            if (callback != null) {
                List<Callback> callbacks = pendingCallbacks.get(videoId);
                if (callbacks == null) {
                    callbacks = new ArrayList<>();
                    pendingCallbacks.put(videoId, callbacks);
                }
                callbacks.add(callback);
            }
            Task task = scheduledTasks.get(videoId);
            if (task == null) {
                task = new Task(videoId, force);
                scheduledTasks.put(videoId, task);
                if (force) {
                    queue.addFirst(task);
                } else {
                    queue.addLast(task);
                }
            } else if (force) {
                task.force = true;
                queue.remove(task);
                queue.addFirst(task);
            }
        }
        pump();
    }

    private void pump() {
        // execute() stays inside the lock, together with the shutdown flag
        // check: shutdown() flips the flag and terminates the executor under
        // the same lock, so submitting to a terminated executor (an
        // uncaught RejectedExecutionException) cannot interleave here.
        // execute() on this pool never blocks, so the lock is not held
        // across blocking work.
        synchronized (queue) {
            while (!shutdown && running < MAX_CONCURRENT_EXTRACTIONS
                    && !queue.isEmpty()) {
                final Task task = queue.pollFirst();
                running++;
                executor.execute(() -> runTask(task));
            }
        }
    }

    private void runTask(@NonNull final Task task) {
        ResolvedShort resolved = null;
        Throwable error = null;
        try {
            final String url = "https://www.youtube.com/watch?v=" + task.videoId;
            final StreamInfo info = ExtractorHelper
                    .getStreamInfo(YOUTUBE_SERVICE_ID, url, task.force)
                    .blockingGet();
            resolved = selectStreams(info);
            cache.put(task.videoId, resolved);
        } catch (final Throwable t) {
            error = t;
            if (DEBUG) {
                Log.d(TAG, "Could not resolve short " + task.videoId, t);
            }
        }
        final List<Callback> callbacks;
        synchronized (queue) {
            running--;
            scheduledTasks.remove(task.videoId);
            callbacks = pendingCallbacks.remove(task.videoId);
        }
        final ResolvedShort result = resolved;
        final Throwable failure = error;
        if (callbacks != null && !callbacks.isEmpty()) {
            mainHandler.post(() -> {
                if (shutdown) {
                    return;
                }
                for (final Callback callback : callbacks) {
                    if (failure != null) {
                        callback.onError(task.videoId, failure);
                    } else if (result != null) {
                        callback.onResolved(task.videoId, result);
                    }
                }
            });
        }
        pump();
    }

    /**
     * Picks the playback streams of a short: the largest AVC video-only stream
     * whose short side is at most {@value #MAX_SHORT_SIDE_PX} px (fixed quality,
     * no adaptive ladder), the best M4A audio stream, and the best muxed
     * progressive stream as a fallback.
     */
    @NonNull
    static ResolvedShort selectStreams(@NonNull final StreamInfo info) {
        VideoStream bestVideo = null;
        for (final VideoStream stream : info.getVideoOnlyStreams()) {
            if (stream.getWidth() <= 0 || stream.getHeight() <= 0) {
                continue;
            }
            if (bestVideo == null || isBetterVideo(stream, bestVideo)) {
                bestVideo = stream;
            }
        }
        AudioStream bestAudio = null;
        for (final AudioStream stream : info.getAudioStreams()) {
            if (bestAudio == null || isBetterAudio(stream, bestAudio)) {
                bestAudio = stream;
            }
        }
        VideoStream bestProgressive = null;
        for (final VideoStream stream : info.getVideoStreams()) {
            if (stream.isVideoOnly() || stream.getWidth() <= 0 || stream.getHeight() <= 0) {
                continue;
            }
            if (bestProgressive == null || isBetterVideo(stream, bestProgressive)) {
                bestProgressive = stream;
            }
        }
        return new ResolvedShort(info, bestVideo, bestAudio, bestProgressive);
    }

    private static boolean isBetterVideo(@NonNull final VideoStream candidate,
                                         @NonNull final VideoStream current) {
        final int candidateShortSide = Math.min(candidate.getWidth(), candidate.getHeight());
        final int currentShortSide = Math.min(current.getWidth(), current.getHeight());
        final boolean candidateInCap = candidateShortSide <= MAX_SHORT_SIDE_PX;
        final boolean currentInCap = currentShortSide <= MAX_SHORT_SIDE_PX;
        if (candidateInCap != currentInCap) {
            return candidateInCap;
        }
        final boolean candidateAvc = isAvc(candidate);
        final boolean currentAvc = isAvc(current);
        if (candidateAvc != currentAvc) {
            return candidateAvc;
        }
        if (candidateShortSide != currentShortSide) {
            // Within the cap, take the largest; above it, the smallest.
            return candidateInCap
                    ? candidateShortSide > currentShortSide
                    : candidateShortSide < currentShortSide;
        }
        return candidate.getBitrate() > current.getBitrate();
    }

    private static boolean isAvc(@NonNull final VideoStream stream) {
        final String codec = stream.getCodec();
        return codec != null && codec.startsWith("avc");
    }

    private static boolean isBetterAudio(@NonNull final AudioStream candidate,
                                         @NonNull final AudioStream current) {
        final boolean candidateM4a = candidate.getFormat() == MediaFormat.M4A;
        final boolean currentM4a = current.getFormat() == MediaFormat.M4A;
        if (candidateM4a != currentM4a) {
            return candidateM4a;
        }
        return candidate.getAverageBitrate() > current.getAverageBitrate();
    }
}
