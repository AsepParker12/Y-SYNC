package org.schabi.newpipe.shorts;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.MainActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Hand-rolled, anonymous InnerTube client for the Shorts feed.
 *
 * <p>The extractor does not expose YouTube's algorithmic Shorts feed, so, like
 * other open-source clients, the feed is driven directly through the
 * {@code reel/reel_watch_sequence} endpoint: a seed short is picked at random
 * from an anonymous {@code "shorts"} search, wrapped in a hand-encoded protobuf
 * "sequence params" token, and every response yields the next batch of shorts
 * plus a continuation token for the following batch. When a token goes stale
 * (empty batch or HTTP error), the caller drops it and chains a fresh sequence
 * from a new seed, which makes the feed effectively endless.</p>
 *
 * <p>All methods perform blocking network I/O and must be called off the main
 * thread.</p>
 */
public final class ShortsFeedClient {
    private static final boolean DEBUG = MainActivity.DEBUG;
    private static final String TAG = ShortsFeedClient.class.getSimpleName();

    // Public API key of the YouTube web client, used by every InnerTube caller.
    private static final String API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String CLIENT_VERSION = "2.20251001.01.00";
    private static final String INNERTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1/";

    private final Random random = new Random();

    /**
     * One page of the reel sequence: the shorts it contained, in feed order,
     * and the token to request the next page with (as the next call's
     * {@code sequenceParams}). A {@code null} continuation means the sequence
     * is exhausted and a fresh one has to be chained.
     */
    public static final class FeedPage {
        private final List<ShortsItem> items;
        @Nullable
        private final String continuationToken;

        FeedPage(@NonNull final List<ShortsItem> items,
                 @Nullable final String continuationToken) {
            this.items = items;
            this.continuationToken = continuationToken;
        }

        @NonNull
        public List<ShortsItem> getItems() {
            return items;
        }

        @Nullable
        public String getContinuationToken() {
            return continuationToken;
        }
    }

    /**
     * Fetches the first page of a fresh sequence: searches anonymously for
     * {@code "shorts"}, picks one of the first seed candidates at random and
     * starts a reel sequence from it.
     */
    @NonNull
    public FeedPage fetchFirstPage() throws IOException {
        final List<String> seeds = fetchSeedVideoIds();
        if (seeds.isEmpty()) {
            throw new IOException("No Shorts seed found in search response");
        }
        final int seedCount = Math.min(seeds.size(), 10);
        final String seedId = seeds.get(random.nextInt(seedCount));
        if (DEBUG) {
            Log.d(TAG, "Starting reel sequence from seed " + seedId);
        }
        return fetchSequence(buildSequenceParams(seedId));
    }

    /**
     * Fetches the next page of a sequence, using a continuation token (or an
     * initial sequence-params token) from a previous page.
     */
    @NonNull
    public FeedPage fetchSequence(@NonNull final String sequenceParams) throws IOException {
        final String body = "{" + contextJson()
                + ",\"sequenceParams\":\"" + escapeJson(sequenceParams) + "\"}";
        final JsonObject root = postJson("reel/reel_watch_sequence", body);
        final List<ShortsItem> items = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        final JsonArray entries = root.getArray("entries");
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                final Object entry = entries.get(i);
                if (entry instanceof JsonObject) {
                    addReelItems((JsonObject) entry, items, seen);
                }
            }
        }
        if (items.isEmpty()) {
            // Fallback: the response shape may have changed; walk it whole.
            addReelItems(root, items, seen);
        }
        return new FeedPage(items, findContinuationToken(root));
    }

    @NonNull
    private List<String> fetchSeedVideoIds() throws IOException {
        final String body = "{" + contextJson() + ",\"query\":\"shorts\"}";
        final JsonObject root = postJson("search", body);
        final List<ShortsItem> items = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        addReelItems(root, items, seen);
        final List<String> ids = new ArrayList<>(items.size());
        for (final ShortsItem item : items) {
            ids.add(item.getVideoId());
        }
        return ids;
    }

    /**
     * Builds the initial {@code sequenceParams} token for a seed video: a
     * hand-encoded protobuf message (field 1 = video id, field 5 = a submessage
     * holding field 3 = 5, field 10 = 25), Base64 URL-safe encoded.
     */
    @NonNull
    static String buildSequenceParams(@NonNull final String videoId) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTag(out, 1, 2);
        writeBytes(out, videoId.getBytes(StandardCharsets.UTF_8));

        final ByteArrayOutputStream subMessage = new ByteArrayOutputStream();
        writeTag(subMessage, 3, 0);
        writeVarint(subMessage, 5);
        writeTag(out, 5, 2);
        writeBytes(out, subMessage.toByteArray());

        writeTag(out, 10, 0);
        writeVarint(out, 25);
        return Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private static void writeTag(final ByteArrayOutputStream out, final int fieldNumber,
                                 final int wireType) {
        writeVarint(out, (fieldNumber << 3) | wireType);
    }

    private static void writeBytes(final ByteArrayOutputStream out, final byte[] bytes) {
        writeVarint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeVarint(final ByteArrayOutputStream out, final long value) {
        long remaining = value;
        while (true) {
            if ((remaining & ~0x7FL) == 0) {
                out.write((int) remaining);
                return;
            }
            out.write(((int) remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
    }

    @NonNull
    private static String contextJson() {
        return "\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\""
                + CLIENT_VERSION + "\",\"hl\":\"en\",\"gl\":\"US\"}}";
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
                throw new IOException("InnerTube request to " + path
                        + " failed with HTTP " + response.code());
            }
            try {
                return JsonParser.object().from(responseBody.string());
            } catch (final JsonParserException e) {
                throw new IOException("Could not parse InnerTube response of " + path, e);
            }
        }
    }

    /**
     * Recursively collects every reel item below the given node, in document
     * order, deduplicated by video id. Reel items appear either as a
     * {@code reelWatchEndpoint} object (sequence responses) or as a
     * {@code reelItemRenderer} with a direct {@code videoId} (search shelves).
     */
    private static void addReelItems(@NonNull final JsonObject node,
                                     @NonNull final List<ShortsItem> out,
                                     @NonNull final Set<String> seen) {
        final JsonObject endpoint = node.getObject("reelWatchEndpoint");
        if (endpoint != null) {
            addItem(endpoint.getString("videoId"), endpoint.getObject("thumbnail"), out, seen);
        }
        if (node.has("videoId") && node.has("headline")) {
            addItem(node.getString("videoId"), node.getObject("thumbnail"), out, seen);
        }
        for (final Object value : node.values()) {
            collectFrom(value, out, seen);
        }
    }

    private static void collectFrom(@Nullable final Object value,
                                    @NonNull final List<ShortsItem> out,
                                    @NonNull final Set<String> seen) {
        if (value instanceof JsonObject) {
            addReelItems((JsonObject) value, out, seen);
        } else if (value instanceof JsonArray) {
            final JsonArray array = (JsonArray) value;
            for (int i = 0; i < array.size(); i++) {
                collectFrom(array.get(i), out, seen);
            }
        }
    }

    private static void addItem(@Nullable final String videoId,
                                @Nullable final JsonObject thumbnail,
                                @NonNull final List<ShortsItem> out,
                                @NonNull final Set<String> seen) {
        if (videoId == null || videoId.isEmpty() || !seen.add(videoId)) {
            return;
        }
        String thumbnailUrl = null;
        if (thumbnail != null) {
            final JsonArray thumbnails = thumbnail.getArray("thumbnails");
            if (thumbnails != null && !thumbnails.isEmpty()) {
                final Object last = thumbnails.get(thumbnails.size() - 1);
                if (last instanceof JsonObject) {
                    thumbnailUrl = ((JsonObject) last).getString("url");
                }
            }
        }
        if (thumbnailUrl == null) {
            thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        }
        out.add(new ShortsItem(videoId, thumbnailUrl));
    }

    /**
     * Finds the continuation token of a sequence response: the last
     * {@code continuationCommand} token anywhere in the tree (paging commands
     * are emitted after the entries they continue).
     */
    @Nullable
    private static String findContinuationToken(@NonNull final JsonObject root) {
        final String[] result = new String[1];
        findContinuationToken(root, result);
        return result[0];
    }

    private static void findContinuationToken(@NonNull final Object node,
                                              @NonNull final String[] result) {
        if (node instanceof JsonObject) {
            final JsonObject object = (JsonObject) node;
            final JsonObject command = object.getObject("continuationCommand");
            if (command != null) {
                final String token = command.getString("token");
                if (token != null && !token.isEmpty()) {
                    result[0] = token;
                }
            }
            for (final Object value : object.values()) {
                findContinuationToken(value, result);
            }
        } else if (node instanceof JsonArray) {
            final JsonArray array = (JsonArray) node;
            for (int i = 0; i < array.size(); i++) {
                findContinuationToken(array.get(i), result);
            }
        }
    }
}
