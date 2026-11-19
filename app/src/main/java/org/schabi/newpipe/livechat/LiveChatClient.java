package org.schabi.newpipe.livechat;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.MainActivity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Hand-rolled, anonymous InnerTube client for YouTube live chat.
 *
 * <p>The extractor has no live chat support, so — exactly like the Shorts
 * feed — the chat is driven directly through YouTube's internal web API:
 * a {@code next} request bootstraps the chat session out of the watch
 * page's {@code liveChatRenderer}, and {@code live_chat/get_live_chat}
 * is then polled with the continuation token each response hands back.
 * Reading public live chat needs no login, cookies or auth headers.</p>
 *
 * <p>All methods perform blocking network I/O and must be called off the
 * main thread. Parsing is defensive throughout: unknown actions and item
 * renderers are skipped, never thrown on, so an API change degrades to
 * "chat unavailable" instead of a crash.</p>
 */
public final class LiveChatClient {
    private static final boolean DEBUG = MainActivity.DEBUG;
    private static final String TAG = LiveChatClient.class.getSimpleName();

    // Public API key of the YouTube web client, used by every InnerTube caller.
    private static final String API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String CLIENT_VERSION = "2.20251001.01.00";
    private static final String INNERTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1/";

    private static final long DEFAULT_TIMEOUT_MS = 5000L;
    private static final long MIN_TIMEOUT_MS = 1000L;
    private static final long MAX_TIMEOUT_MS = 30000L;

    /**
     * An {@link IOException} that also carries the HTTP status code, so the
     * caller can recognise a stale continuation (HTTP 400) and re-bootstrap.
     */
    public static final class HttpStatusException extends IOException {
        private final int statusCode;

        HttpStatusException(final int statusCode, @NonNull final String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    /**
     * A bootstrapped chat session: the continuation to poll with (updated
     * in place by every {@link #poll(LiveChatSession)} call), the tokens of
     * the two chat filters, and the visitor data echoed back to YouTube.
     * Not thread-safe; confine it to one polling thread.
     */
    public static final class LiveChatSession {
        private String continuation;
        @Nullable
        private final String topChatContinuation;
        @Nullable
        private final String liveChatContinuation;
        private final boolean replay;
        @Nullable
        private String visitorData;

        LiveChatSession(@NonNull final String continuation,
                        @Nullable final String topChatContinuation,
                        @Nullable final String liveChatContinuation,
                        final boolean replay,
                        @Nullable final String visitorData) {
            this.continuation = continuation;
            this.topChatContinuation = topChatContinuation;
            this.liveChatContinuation = liveChatContinuation;
            this.replay = replay;
            this.visitorData = visitorData;
        }

        @NonNull
        public String getContinuation() {
            return continuation;
        }

        /** The server-filtered "Top chat" token, if the filter menu had one. */
        @Nullable
        public String getTopChatContinuation() {
            return topChatContinuation;
        }

        /** The unfiltered "Live chat" token, if the filter menu had one. */
        @Nullable
        public String getLiveChatContinuation() {
            return liveChatContinuation;
        }

        /** Whether this session belongs to a finished stream's chat replay. */
        public boolean isReplay() {
            return replay;
        }
    }

    /**
     * One poll result: the new chat items it carried, the messages a
     * moderator deleted in the meantime (item id → replacement text, or a
     * {@code null} value when YouTube sent none), how long to wait before
     * polling again, and whether the chat has ended.
     */
    public static final class LiveChatPage {
        private final List<LiveChatItem> items;
        private final Map<String, String> deletedMessages;
        private final long timeoutMs;
        private final boolean ended;

        LiveChatPage(@NonNull final List<LiveChatItem> items,
                     @NonNull final Map<String, String> deletedMessages,
                     final long timeoutMs,
                     final boolean ended) {
            this.items = items;
            this.deletedMessages = deletedMessages;
            this.timeoutMs = timeoutMs;
            this.ended = ended;
        }

        @NonNull
        public List<LiveChatItem> getItems() {
            return items;
        }

        @NonNull
        public Map<String, String> getDeletedMessages() {
            return deletedMessages;
        }

        /** Server-chosen delay before the next poll, clamped to 1–30 s. */
        public long getTimeoutMs() {
            return timeoutMs;
        }

        public boolean isEnded() {
            return ended;
        }
    }

    /**
     * Bootstraps a chat session for the given video: POSTs {@code next} and
     * extracts the {@code liveChatRenderer}. The session starts on the
     * renderer's own continuation, which is the filter YouTube pre-selects
     * (Top chat on large streams).
     *
     * @return the session, or empty when the video has no live chat at all
     *         (chat disabled, "made for kids", ordinary VOD, …)
     */
    @NonNull
    public Optional<LiveChatSession> bootstrap(@NonNull final String videoId)
            throws IOException {
        final String body = "{" + contextJson(null)
                + ",\"videoId\":\"" + escapeJson(videoId) + "\"}";
        final JsonObject root = postJson("next", body);
        final String visitorData = getVisitorData(root);

        final JsonObject contents = root.getObject("contents");
        final JsonObject watchNext = contents == null
                ? null : contents.getObject("twoColumnWatchNextResults");
        final JsonObject conversationBar = watchNext == null
                ? null : watchNext.getObject("conversationBar");
        final JsonObject renderer = conversationBar == null
                ? null : conversationBar.getObject("liveChatRenderer");
        if (renderer == null) {
            if (DEBUG) {
                Log.d(TAG, "No liveChatRenderer for video " + videoId);
            }
            return Optional.empty();
        }

        final String initialContinuation = getReloadContinuation(renderer);
        if (initialContinuation == null || initialContinuation.isEmpty()) {
            return Optional.empty();
        }
        final boolean replay = Boolean.TRUE.equals(renderer.get("isReplay"));

        // The filter menu: index 0 = Top chat, index 1 = Live chat.
        String topChatContinuation = null;
        String liveChatContinuation = null;
        final JsonArray filterItems = getFilterItems(renderer);
        if (filterItems != null) {
            if (filterItems.size() > 0) {
                topChatContinuation = getReloadContinuation(filterItems.getObject(0));
            }
            if (filterItems.size() > 1) {
                liveChatContinuation = getReloadContinuation(filterItems.getObject(1));
            }
        }

        return Optional.of(new LiveChatSession(initialContinuation, topChatContinuation,
                liveChatContinuation, replay, visitorData));
    }

    /**
     * Polls the chat once with the session's current continuation and moves
     * the session on to the continuation the response hands back. A
     * response without a usable next continuation means the chat has ended
     * (stream over or chat closed); its items are still delivered.
     */
    @NonNull
    public LiveChatPage poll(@NonNull final LiveChatSession session) throws IOException {
        final String body = "{" + contextJson(session.visitorData)
                + ",\"continuation\":\"" + escapeJson(session.continuation) + "\"}";
        final JsonObject root = postJson("live_chat/get_live_chat", body);
        final String visitorData = getVisitorData(root);
        if (visitorData != null) {
            session.visitorData = visitorData;
        }

        final JsonObject contents = root.getObject("continuationContents");
        final JsonObject chatContinuation = contents == null
                ? null : contents.getObject("liveChatContinuation");
        if (chatContinuation == null) {
            return new LiveChatPage(new ArrayList<>(), new LinkedHashMap<>(),
                    DEFAULT_TIMEOUT_MS, true);
        }

        final List<LiveChatItem> items = new ArrayList<>();
        final Map<String, String> deletedMessages = new LinkedHashMap<>();
        final JsonArray actions = chatContinuation.getArray("actions");
        if (actions != null) {
            for (int i = 0; i < actions.size(); i++) {
                final JsonObject action = actions.getObject(i);
                if (action != null) {
                    parseAction(action, items, deletedMessages);
                }
            }
        }

        final NextContinuation next = findNextContinuation(chatContinuation);
        if (next == null) {
            return new LiveChatPage(items, deletedMessages, DEFAULT_TIMEOUT_MS, true);
        }
        session.continuation = next.token;
        return new LiveChatPage(items, deletedMessages,
                clampTimeoutMs(next.timeoutMs), false);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Parsing
    //////////////////////////////////////////////////////////////////////////*/

    private static void parseAction(@NonNull final JsonObject action,
                                    @NonNull final List<LiveChatItem> items,
                                    @NonNull final Map<String, String> deletedMessages) {
        final JsonObject addItem = action.getObject("addChatItemAction");
        if (addItem != null) {
            final JsonObject itemWrapper = addItem.getObject("item");
            final LiveChatItem item = itemWrapper == null ? null : parseChatItem(itemWrapper);
            if (item != null) {
                items.add(item);
            }
            return;
        }
        final JsonObject deleted = action.getObject("markChatItemAsDeletedAction");
        if (deleted != null) {
            final String targetId = deleted.getString("targetItemId");
            if (targetId != null && !targetId.isEmpty()) {
                final JsonObject stateMessage = deleted.getObject("deletedStateMessage");
                deletedMessages.put(targetId,
                        stateMessage == null ? null : flattenRuns(stateMessage));
            }
        }
        // Every other action type (tickers, banners, polls, removals, …)
        // is intentionally ignored in v1.
    }

    @Nullable
    private static LiveChatItem parseChatItem(@NonNull final JsonObject itemWrapper) {
        JsonObject renderer = itemWrapper.getObject("liveChatTextMessageRenderer");
        if (renderer != null) {
            return buildItem(renderer, LiveChatItem.Type.TEXT, null, 0, false,
                    flattenRuns(renderer.getObject("message")));
        }
        renderer = itemWrapper.getObject("liveChatPaidMessageRenderer");
        if (renderer != null) {
            return buildItem(renderer, LiveChatItem.Type.PAID,
                    getSimpleText(renderer.getObject("purchaseAmountText")),
                    getPaidColor(renderer), false,
                    flattenRuns(renderer.getObject("message")));
        }
        renderer = itemWrapper.getObject("liveChatPaidStickerRenderer");
        if (renderer != null) {
            return buildItem(renderer, LiveChatItem.Type.PAID,
                    getSimpleText(renderer.getObject("purchaseAmountText")),
                    getPaidColor(renderer), true, "");
        }
        renderer = itemWrapper.getObject("liveChatMembershipItemRenderer");
        if (renderer != null) {
            String text = flattenRuns(renderer.getObject("headerSubtext"));
            if (text.isEmpty()) {
                text = flattenRuns(renderer.getObject("headerPrimaryText"));
            }
            return buildItem(renderer, LiveChatItem.Type.MEMBERSHIP, null, 0, false, text);
        }
        renderer = itemWrapper.getObject("liveChatViewerEngagementMessageRenderer");
        if (renderer != null) {
            return buildItem(renderer, LiveChatItem.Type.SYSTEM, null, 0, false,
                    flattenRuns(renderer.getObject("message")));
        }
        // Unknown renderer (automod, mode change, placeholder, poll, …): skip.
        return null;
    }

    @Nullable
    private static LiveChatItem buildItem(@NonNull final JsonObject renderer,
                                          @NonNull final LiveChatItem.Type type,
                                          @Nullable final String paidAmountText,
                                          final int paidBackgroundColor,
                                          final boolean superSticker,
                                          @NonNull final String messageText) {
        final String id = renderer.getString("id");
        if (id == null || id.isEmpty()) {
            return null;
        }
        String authorName = getSimpleText(renderer.getObject("authorName"));
        if (type == LiveChatItem.Type.SYSTEM) {
            // YouTube's own engagement messages have no author; label the source.
            authorName = "YouTube";
        } else if (authorName == null) {
            authorName = "";
        }
        final String channelId = renderer.getString("authorExternalChannelId");

        boolean owner = false;
        boolean moderator = false;
        boolean verified = false;
        boolean member = false;
        final JsonArray badges = renderer.getArray("authorBadges");
        if (badges != null) {
            for (int i = 0; i < badges.size(); i++) {
                final JsonObject badgeWrapper = badges.getObject(i);
                final JsonObject badge = badgeWrapper == null
                        ? null : badgeWrapper.getObject("liveChatAuthorBadgeRenderer");
                if (badge == null) {
                    continue;
                }
                if (badge.getObject("customThumbnail") != null) {
                    member = true;
                }
                final JsonObject icon = badge.getObject("icon");
                final String iconType = icon == null ? null : icon.getString("iconType");
                if ("OWNER".equals(iconType)) {
                    owner = true;
                } else if ("MODERATOR".equals(iconType)) {
                    moderator = true;
                } else if ("VERIFIED".equals(iconType)) {
                    verified = true;
                }
            }
        }

        return new LiveChatItem(id, authorName, channelId == null ? "" : channelId,
                messageText, getAuthorPhotoUrl(renderer), getTimestampUsec(renderer),
                type, paidAmountText, paidBackgroundColor, superSticker,
                owner, moderator, verified, member);
    }

    /**
     * Flattens a {@code {runs: [...]}} message into plain text. Text runs are
     * appended as-is; standard emoji runs contribute their unicode character;
     * channel custom emoji (which only exist as images) contribute their
     * {@code :shortcut:} text so the run is never silently dropped.
     */
    @NonNull
    private static String flattenRuns(@Nullable final JsonObject message) {
        if (message == null) {
            return "";
        }
        final JsonArray runs = message.getArray("runs");
        if (runs == null) {
            final String simpleText = message.getString("simpleText");
            return simpleText == null ? "" : simpleText;
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            final JsonObject run = runs.getObject(i);
            if (run == null) {
                continue;
            }
            final String text = run.getString("text");
            if (text != null) {
                builder.append(text);
                continue;
            }
            final JsonObject emoji = run.getObject("emoji");
            if (emoji != null) {
                builder.append(flattenEmoji(emoji));
            }
        }
        return builder.toString();
    }

    @NonNull
    private static String flattenEmoji(@NonNull final JsonObject emoji) {
        if (!Boolean.TRUE.equals(emoji.get("isCustomEmoji"))) {
            final String emojiId = emoji.getString("emojiId");
            if (emojiId != null && !emojiId.isEmpty()) {
                return emojiId;
            }
        }
        final JsonArray shortcuts = emoji.getArray("shortcuts");
        if (shortcuts != null && !shortcuts.isEmpty()) {
            final String shortcut = shortcuts.getString(0);
            if (shortcut != null) {
                return shortcut;
            }
        }
        return "";
    }

    @Nullable
    private static String getSimpleText(@Nullable final JsonObject holder) {
        return holder == null ? null : holder.getString("simpleText");
    }

    @Nullable
    private static String getAuthorPhotoUrl(@NonNull final JsonObject renderer) {
        final JsonObject photo = renderer.getObject("authorPhoto");
        final JsonArray thumbnails = photo == null ? null : photo.getArray("thumbnails");
        if (thumbnails == null || thumbnails.isEmpty()) {
            return null;
        }
        // The array is ascending by size; the smallest (32 px) is enough for a row.
        final JsonObject smallest = thumbnails.getObject(0);
        return smallest == null ? null : smallest.getString("url");
    }

    private static long getTimestampUsec(@NonNull final JsonObject renderer) {
        final Object raw = renderer.get("timestampUsec");
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw instanceof String) {
            try {
                return Long.parseLong((String) raw);
            } catch (final NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static int getPaidColor(@NonNull final JsonObject renderer) {
        final int bodyColor = getColorValue(renderer.get("bodyBackgroundColor"));
        if (bodyColor != 0) {
            return bodyColor;
        }
        return getColorValue(renderer.get("headerBackgroundColor"));
    }

    private static int getColorValue(@Nullable final Object raw) {
        return raw instanceof Number ? ((Number) raw).intValue() : 0;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Continuations
    //////////////////////////////////////////////////////////////////////////*/

    private static final class NextContinuation {
        private final String token;
        private final long timeoutMs;

        NextContinuation(@NonNull final String token, final long timeoutMs) {
            this.token = token;
            this.timeoutMs = timeoutMs;
        }
    }

    /**
     * Reads {@code continuations[0]} of a renderer or continuation object:
     * its {@code reloadContinuationData.continuation} token.
     */
    @Nullable
    private static String getReloadContinuation(@Nullable final JsonObject holder) {
        if (holder == null) {
            return null;
        }
        final JsonArray continuations = holder.getArray("continuations");
        if (continuations == null || continuations.isEmpty()) {
            return null;
        }
        final JsonObject first = continuations.getObject(0);
        final JsonObject reload = first == null
                ? null : first.getObject("reloadContinuationData");
        return reload == null ? null : reload.getString("continuation");
    }

    @Nullable
    private static JsonArray getFilterItems(@NonNull final JsonObject renderer) {
        final JsonObject header = renderer.getObject("header");
        final JsonObject headerRenderer = header == null
                ? null : header.getObject("liveChatHeaderRenderer");
        final JsonObject viewSelector = headerRenderer == null
                ? null : headerRenderer.getObject("viewSelector");
        final JsonObject subMenu = viewSelector == null
                ? null : viewSelector.getObject("sortFilterSubMenuRenderer");
        return subMenu == null ? null : subMenu.getArray("subMenuItems");
    }

    /**
     * Finds the next continuation of a poll response. Priority order:
     * {@code invalidationContinuationData}, then
     * {@code timedContinuationData}, then {@code reloadContinuationData} —
     * the first one present wins.
     */
    @Nullable
    private static NextContinuation findNextContinuation(
            @NonNull final JsonObject chatContinuation) {
        final JsonArray continuations = chatContinuation.getArray("continuations");
        if (continuations == null || continuations.isEmpty()) {
            return null;
        }
        final JsonObject first = continuations.getObject(0);
        if (first == null) {
            return null;
        }
        NextContinuation next = readContinuation(first, "invalidationContinuationData");
        if (next == null) {
            next = readContinuation(first, "timedContinuationData");
        }
        if (next == null) {
            next = readContinuation(first, "reloadContinuationData");
        }
        return next;
    }

    @Nullable
    private static NextContinuation readContinuation(@NonNull final JsonObject holder,
                                                     @NonNull final String key) {
        final JsonObject data = holder.getObject(key);
        if (data == null) {
            return null;
        }
        final String token = data.getString("continuation");
        if (token == null || token.isEmpty()) {
            return null;
        }
        long timeoutMs = DEFAULT_TIMEOUT_MS;
        final Object rawTimeout = data.get("timeoutMs");
        if (rawTimeout instanceof Number) {
            timeoutMs = ((Number) rawTimeout).longValue();
        }
        return new NextContinuation(token, timeoutMs);
    }

    private static long clampTimeoutMs(final long timeoutMs) {
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs));
    }

    @Nullable
    private static String getVisitorData(@NonNull final JsonObject root) {
        final JsonObject responseContext = root.getObject("responseContext");
        return responseContext == null ? null : responseContext.getString("visitorData");
    }

    /*//////////////////////////////////////////////////////////////////////////
    // HTTP plumbing (mirrors ShortsFeedClient)
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull
    private static String contextJson(@Nullable final String visitorData) {
        final StringBuilder builder = new StringBuilder();
        builder.append("\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"")
                .append(CLIENT_VERSION)
                .append("\",\"hl\":\"en\",\"gl\":\"US\"");
        if (visitorData != null && !visitorData.isEmpty()) {
            builder.append(",\"visitorData\":\"").append(escapeJson(visitorData)).append('"');
        }
        builder.append("}}");
        return builder.toString();
    }

    @NonNull
    private static String escapeJson(@NonNull final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @NonNull
    private JsonObject postJson(@NonNull final String path, @NonNull final String body)
            throws IOException {
        final OkHttpClient client = DownloaderImpl.getInstance().getClient();
        final Request request = new Request.Builder()
                .url(INNERTUBE_BASE_URL + path + "?key=" + API_KEY)
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8)))
                .addHeader("Content-Type", "application/json")
                .addHeader("X-YouTube-Client-Name", "1")
                .addHeader("X-YouTube-Client-Version", CLIENT_VERSION)
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/")
                .build();
        try (Response response = client.newCall(request).execute()) {
            final ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                throw new HttpStatusException(response.code(),
                        "InnerTube request to " + path
                                + " failed with HTTP " + response.code());
            }
            try {
                return JsonParser.object().from(responseBody.string());
            } catch (final JsonParserException e) {
                throw new IOException("Could not parse InnerTube response of " + path, e);
            }
        }
    }
}
