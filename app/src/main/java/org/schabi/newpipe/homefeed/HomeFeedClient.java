package org.schabi.newpipe.homefeed;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.DownloaderImpl;

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
 * Hand-rolled, anonymous InnerTube client for the generic home feed.
 *
 * <p>The extractor's kiosks no longer include a generic YouTube home page
 * (Trending was removed in July 2025, leaving the Live kiosk as the default),
 * so, like other open-source clients, the home feed is driven directly
 * through YouTube's web endpoints. The first page is bootstrapped the way
 * the proven logged-out scrapers do it: the homepage HTML is fetched like a
 * browser would (desktop user agent, a {@code CONSENT} cookie against the
 * consent interstitial) and its embedded {@code ytInitialData} is parsed —
 * a bare {@code browse} request for {@code FEwhat_to_watch} without any
 * session data comes back unusable on some networks. Only when the HTML
 * route fails or yields nothing does the client fall back to that bare
 * {@code browse} request. Every following page is a {@code browse} request
 * holding the continuation token of the previous one. The
 * {@code visitorData} of the first response is captured and sent along with
 * the continuations, so YouTube keeps the feed session consistent.</p>
 *
 * <p>Only plain videos are collected: shelves that are not
 * {@code richItemRenderer}s wrapping a {@code videoRenderer} (Shorts/reel
 * shelves, ads, sections) are skipped. Results are deduplicated by video id.
 * </p>
 *
 * <p>All methods perform blocking network I/O and must be called off the main
 * thread. Instances are stateful (they hold the session's visitor data) and
 * are not thread-safe; use one instance per feed.</p>
 */
public final class HomeFeedClient {
    private static final String TAG = HomeFeedClient.class.getSimpleName();

    // Public API key of the YouTube web client, used by every InnerTube caller.
    private static final String API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    private static final String CLIENT_VERSION = "2.20251001.01.00";
    private static final String INNERTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1/";
    private static final String HOME_PAGE_URL = "https://www.youtube.com/";
    private static final String HOME_BROWSE_ID = "FEwhat_to_watch";

    @Nullable
    private String visitorData;

    /**
     * One page of the home feed: the videos it contained, in feed order, and
     * the token to request the next page with. A {@code null} continuation
     * means the feed is exhausted.
     */
    public static final class FeedPage {
        private final List<HomeFeedItem> items;
        @Nullable
        private final String continuationToken;

        FeedPage(@NonNull final List<HomeFeedItem> items,
                 @Nullable final String continuationToken) {
            this.items = items;
            this.continuationToken = continuationToken;
        }

        @NonNull
        public List<HomeFeedItem> getItems() {
            return items;
        }

        @Nullable
        public String getContinuationToken() {
            return continuationToken;
        }
    }

    /**
     * Fetches the first page of a fresh home feed session, dropping any
     * visitor data captured by earlier calls.
     *
     * <p>The primary route scrapes the homepage's {@code ytInitialData}
     * (see {@link #fetchFirstPageFromHtml()}); the bare {@code browse}
     * request is only the fallback for when that route throws or yields
     * no videos. Visitor data captured by the HTML route is kept for the
     * fallback, so the API request still runs inside the scraped session.
     * </p>
     */
    @NonNull
    public FeedPage fetchFirstPage() throws IOException {
        visitorData = null;
        try {
            final FeedPage page = fetchFirstPageFromHtml();
            if (!page.getItems().isEmpty()) {
                return page;
            }
        } catch (final IOException e) {
            // Fall through to the bare API request below.
            Log.w(TAG, "Homepage scrape failed, using the browse API instead", e);
        }
        final String body = "{" + contextJson()
                + ",\"browseId\":\"" + HOME_BROWSE_ID + "\"}";
        return parsePage(postJson("browse", body));
    }

    /**
     * Fetches the homepage HTML like a desktop browser and parses the
     * {@code ytInitialData} embedded in it as the first feed page. The
     * {@code CONSENT} cookie (in the format youtube-dl sets) keeps the
     * request from being redirected to the consent interstitial, whose
     * page carries no feed data.
     */
    @NonNull
    private FeedPage fetchFirstPageFromHtml() throws IOException {
        final OkHttpClient client = DownloaderImpl.getInstance().getClient();
        final Request request = new Request.Builder()
                .url(HOME_PAGE_URL)
                .get()
                .addHeader("User-Agent", DownloaderImpl.USER_AGENT)
                .addHeader("Accept-Language", "id")
                .addHeader("Cookie", buildConsentCookie())
                .build();
        try (Response response = client.newCall(request).execute()) {
            final ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                throw new IOException("Homepage request failed with HTTP "
                        + response.code());
            }
            final String html = responseBody.string();
            final FeedPage page = parsePage(extractInitialData(html));
            if (visitorData == null) {
                // ytInitialData normally carries the visitor data in its
                // response context (parsePage captured it); the ytcfg blob
                // of the page is the backup source.
                visitorData = extractVisitorData(html);
            }
            return page;
        }
    }

    /**
     * Builds the {@code CONSENT} cookie that gets past YouTube's consent
     * interstitial: the value youtube-dl sets, a "YES" callback stamp with
     * a random consent id.
     */
    @NonNull
    private static String buildConsentCookie() {
        final int consentId = 100 + new Random().nextInt(900);
        return "CONSENT=YES+cb.20210328-17-p0.id+FX+" + consentId;
    }

    /**
     * Extracts and parses the {@code ytInitialData} JSON object embedded in
     * a YouTube page. The object follows {@code var ytInitialData = } in a
     * script tag; its end is found by scanning for the balancing closing
     * brace (string contents cannot unbalance the scan), which stays
     * correct no matter what the page around it looks like.
     */
    @NonNull
    private static JsonObject extractInitialData(@NonNull final String html)
            throws IOException {
        final int marker = html.indexOf("ytInitialData");
        final int start = marker < 0 ? -1 : html.indexOf('{', marker);
        final int end = start < 0 ? -1 : findJsonObjectEnd(html, start);
        if (end < 0) {
            throw new IOException("No ytInitialData found in the homepage response");
        }
        try {
            return JsonParser.object().from(html.substring(start, end + 1));
        } catch (final JsonParserException e) {
            throw new IOException("Could not parse the homepage's ytInitialData", e);
        }
    }

    /**
     * Returns the index of the closing brace that balances the opening
     * brace at {@code start}, or {@code -1} when the object never closes.
     * Braces inside string literals do not count.
     */
    private static int findJsonObjectEnd(@NonNull final String text, final int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Reads the visitor data out of the page's ytcfg blob
     * ({@code "VISITOR_DATA":"..."}), the backup for when
     * {@code ytInitialData} itself carries none.
     */
    @Nullable
    private static String extractVisitorData(@NonNull final String html) {
        final String marker = "\"VISITOR_DATA\":\"";
        final int markerIndex = html.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        final int valueStart = markerIndex + marker.length();
        final int valueEnd = html.indexOf('"', valueStart);
        if (valueEnd <= valueStart) {
            return null;
        }
        return html.substring(valueStart, valueEnd);
    }

    /**
     * Fetches the next page of the feed, using a continuation token from a
     * previous page and the visitor data captured so far.
     */
    @NonNull
    public FeedPage fetchNextPage(@NonNull final String continuationToken) throws IOException {
        final String body = "{" + contextJson()
                + ",\"browseId\":\"" + HOME_BROWSE_ID + "\""
                + ",\"continuation\":\"" + escapeJson(continuationToken) + "\"}";
        return parsePage(postJson("browse", body));
    }

    @NonNull
    private FeedPage parsePage(@NonNull final JsonObject root) {
        final JsonObject responseContext = root.getObject("responseContext");
        if (responseContext != null) {
            final String newVisitorData = responseContext.getString("visitorData");
            if (newVisitorData != null && !newVisitorData.isEmpty()) {
                visitorData = newVisitorData;
            }
        }

        final List<HomeFeedItem> items = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        final String[] continuation = new String[1];

        final JsonArray gridContents = findGridContents(root);
        if (gridContents != null) {
            collectItems(gridContents, items, seen, continuation);
        }
        final JsonArray continuationItems = findContinuationItems(root);
        if (continuationItems != null) {
            collectItems(continuationItems, items, seen, continuation);
        }
        if (continuation[0] == null) {
            continuation[0] = findLastContinuationItemToken(root);
        }
        return new FeedPage(items, continuation[0]);
    }

    /**
     * Collects the videos of one rich-grid contents (or continuation items)
     * array and remembers the token of its continuation item, if any.
     */
    private static void collectItems(@NonNull final JsonArray contents,
                                     @NonNull final List<HomeFeedItem> out,
                                     @NonNull final Set<String> seen,
                                     @NonNull final String[] continuation) {
        for (int i = 0; i < contents.size(); i++) {
            final Object entry = contents.get(i);
            if (!(entry instanceof JsonObject)) {
                continue;
            }
            final JsonObject item = (JsonObject) entry;
            final JsonObject richItem = item.getObject("richItemRenderer");
            if (richItem != null) {
                final JsonObject content = richItem.getObject("content");
                final JsonObject videoRenderer = content == null
                        ? null : content.getObject("videoRenderer");
                if (videoRenderer != null) {
                    addVideo(videoRenderer, out, seen);
                }
                continue;
            }
            final JsonObject continuationItem = item.getObject("continuationItemRenderer");
            if (continuationItem != null) {
                final JsonObject endpoint = continuationItem.getObject("continuationEndpoint");
                final JsonObject command = endpoint == null
                        ? null : endpoint.getObject("continuationCommand");
                final String token = command == null ? null : command.getString("token");
                if (token != null && !token.isEmpty()) {
                    continuation[0] = token;
                }
            }
        }
    }

    private static void addVideo(@NonNull final JsonObject videoRenderer,
                                 @NonNull final List<HomeFeedItem> out,
                                 @NonNull final Set<String> seen) {
        final String videoId = videoRenderer.getString("videoId");
        if (videoId == null || videoId.isEmpty() || !seen.add(videoId)) {
            return;
        }
        final String durationText = extractText(videoRenderer.getObject("lengthText"));
        out.add(new HomeFeedItem(
                videoId,
                extractText(videoRenderer.getObject("title")),
                uploaderName(videoRenderer),
                bestThumbnailUrl(videoRenderer.getObject("thumbnail")),
                durationText,
                extractText(videoRenderer.getObject("viewCountText")),
                extractText(videoRenderer.getObject("publishedTimeText")),
                isLive(videoRenderer, durationText)));
    }

    @Nullable
    private static String uploaderName(@NonNull final JsonObject videoRenderer) {
        final String fromByline = extractText(videoRenderer.getObject("longBylineText"));
        return fromByline != null
                ? fromByline : extractText(videoRenderer.getObject("ownerText"));
    }

    /**
     * A video is live when one of its badges is the LIVE badge, or when it
     * has no duration text at all (live and upcoming videos carry none).
     */
    private static boolean isLive(@NonNull final JsonObject videoRenderer,
                                  @Nullable final String durationText) {
        final JsonArray badges = videoRenderer.getArray("badges");
        if (badges != null) {
            for (int i = 0; i < badges.size(); i++) {
                final Object entry = badges.get(i);
                if (!(entry instanceof JsonObject)) {
                    continue;
                }
                final JsonObject badge =
                        ((JsonObject) entry).getObject("metadataBadgeRenderer");
                if (badge == null) {
                    continue;
                }
                if ("BADGE_STYLE_TYPE_LIVE_NOW".equals(badge.getString("style"))) {
                    return true;
                }
                final JsonObject icon = badge.getObject("icon");
                if (icon != null && "LIVE".equals(icon.getString("iconType"))) {
                    return true;
                }
                if ("LIVE".equals(badge.getString("label"))) {
                    return true;
                }
            }
        }
        return durationText == null;
    }

    /**
     * Extracts the display text of an InnerTube text object, which is either
     * a {@code simpleText} or a list of {@code runs}.
     */
    @Nullable
    private static String extractText(@Nullable final JsonObject textObject) {
        if (textObject == null) {
            return null;
        }
        final String simpleText = textObject.getString("simpleText");
        if (simpleText != null) {
            return simpleText;
        }
        final JsonArray runs = textObject.getArray("runs");
        if (runs == null) {
            return null;
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            final Object run = runs.get(i);
            if (run instanceof JsonObject) {
                final String text = ((JsonObject) run).getString("text");
                if (text != null) {
                    builder.append(text);
                }
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    @Nullable
    private static String bestThumbnailUrl(@Nullable final JsonObject thumbnail) {
        if (thumbnail == null) {
            return null;
        }
        final JsonArray thumbnails = thumbnail.getArray("thumbnails");
        if (thumbnails == null || thumbnails.isEmpty()) {
            return null;
        }
        // Thumbnails are sorted by size ascending; the last one is the best.
        final Object last = thumbnails.get(thumbnails.size() - 1);
        return last instanceof JsonObject ? ((JsonObject) last).getString("url") : null;
    }

    /**
     * Finds the contents array of the first rich grid in the response (the
     * first page wraps it in the browse results; some responses repeat the
     * whole contents tree for continuation requests).
     */
    @Nullable
    private static JsonArray findGridContents(@NonNull final JsonObject root) {
        final JsonObject richGrid = findObject(root, "richGridRenderer");
        return richGrid == null ? null : richGrid.getArray("contents");
    }

    /**
     * Finds the items array appended by a continuation response
     * ({@code appendContinuationItemsAction}).
     */
    @Nullable
    private static JsonArray findContinuationItems(@NonNull final JsonObject root) {
        final JsonObject action = findObject(root, "appendContinuationItemsAction");
        return action == null ? null : action.getArray("continuationItems");
    }

    /**
     * Fallback continuation search: the token of the last
     * {@code continuationItemRenderer} anywhere in the tree (grid
     * continuations are emitted after the items they continue).
     */
    @Nullable
    private static String findLastContinuationItemToken(@NonNull final JsonObject root) {
        final String[] result = new String[1];
        findLastContinuationItemToken(root, result);
        return result[0];
    }

    private static void findLastContinuationItemToken(@NonNull final Object node,
                                                      @NonNull final String[] result) {
        if (node instanceof JsonObject) {
            final JsonObject object = (JsonObject) node;
            final JsonObject continuationItem = object.getObject("continuationItemRenderer");
            if (continuationItem != null) {
                final JsonObject endpoint = continuationItem.getObject("continuationEndpoint");
                final JsonObject command = endpoint == null
                        ? null : endpoint.getObject("continuationCommand");
                final String token = command == null ? null : command.getString("token");
                if (token != null && !token.isEmpty()) {
                    result[0] = token;
                }
            }
            for (final Object value : object.values()) {
                findLastContinuationItemToken(value, result);
            }
        } else if (node instanceof JsonArray) {
            final JsonArray array = (JsonArray) node;
            for (int i = 0; i < array.size(); i++) {
                findLastContinuationItemToken(array.get(i), result);
            }
        }
    }

    @Nullable
    private static JsonObject findObject(@NonNull final Object node,
                                         @NonNull final String key) {
        if (node instanceof JsonObject) {
            final JsonObject object = (JsonObject) node;
            final JsonObject direct = object.getObject(key);
            if (direct != null) {
                return direct;
            }
            for (final Object value : object.values()) {
                final JsonObject found = findObject(value, key);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof JsonArray) {
            final JsonArray array = (JsonArray) node;
            for (int i = 0; i < array.size(); i++) {
                final JsonObject found = findObject(array.get(i), key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @NonNull
    private String contextJson() {
        final StringBuilder builder = new StringBuilder();
        builder.append("\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"")
                .append(CLIENT_VERSION)
                .append("\",\"hl\":\"id\",\"gl\":\"ID\"");
        if (visitorData != null) {
            builder.append(",\"visitorData\":\"")
                    .append(escapeJson(visitorData))
                    .append("\"");
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
}
