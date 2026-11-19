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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * so the home feed is driven directly through YouTube's web endpoints. The
 * home feed is a recommendations surface: unlike search or the Shorts
 * sequence, it only comes back populated for a request that carries a real
 * visitor identity, so this client first establishes an anonymous session
 * the way YouTube.js does — one browser-like fetch of the homepage whose
 * server-issued cookies are kept and replayed, and whose page config
 * provides the session's client version and visitor data. The homepage's
 * embedded {@code ytInitialData} doubles as a candidate first page; when it
 * yields nothing, the first page is a {@code browse} request for
 * {@code FEwhat_to_watch} inside that session, and every following page is
 * a {@code browse} request holding only the continuation token of the
 * previous one. Visitor data of later responses is captured and sent
 * along, so YouTube keeps the feed session consistent.</p>
 *
 * <p>Videos are collected by walking the whole response tree: the classic
 * {@code videoRenderer} family (also {@code gridVideoRenderer} and
 * {@code compactVideoRenderer}, bare or wrapped in a
 * {@code richItemRenderer}) and the newer {@code lockupViewModel} items.
 * Shelves of other kinds (Shorts/reel shelves, ads, sections) are skipped.
 * Results are deduplicated by video id.</p>
 *
 * <p>All methods perform blocking network I/O and must be called off the
 * main thread. Instances are stateful (they hold the session) and are not
 * thread-safe; use one instance per feed.</p>
 */
public final class HomeFeedClient {
    private static final String TAG = HomeFeedClient.class.getSimpleName();

    // Public API key of the YouTube web client, used by every InnerTube caller.
    private static final String API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    // Used only until the homepage bootstrap scrapes the live client version.
    private static final String FALLBACK_CLIENT_VERSION = "2.20251001.01.00";
    private static final String INNERTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1/";
    private static final String HOME_PAGE_URL = "https://www.youtube.com/";
    private static final String HOME_BROWSE_ID = "FEwhat_to_watch";
    private static final String LOCKUP_VIDEO_CONTENT_TYPE = "LOCKUP_CONTENT_TYPE_VIDEO";

    private final Map<String, String> sessionCookies = new LinkedHashMap<>();
    private String clientVersion = FALLBACK_CLIENT_VERSION;

    @Nullable
    private String visitorData;
    private boolean sessionStarted;
    @Nullable
    private FeedPage bootstrappedPage;
    @Nullable
    private String bootstrapDetail;
    private int lastHttpCode = -1;
    @Nullable
    private String lastFailureDetail;

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
     * A short, human-readable account of the most recent first-page fetch
     * (which routes ran, their HTTP codes and how many videos each yielded),
     * for display on the feed's error screen; {@code null} before the first
     * fetch.
     */
    @Nullable
    public String getLastFailureDetail() {
        return lastFailureDetail;
    }

    /**
     * Fetches the first page of the feed. The anonymous session is
     * bootstrapped first (once per instance); the homepage's own
     * {@code ytInitialData} is used when it already carries videos,
     * otherwise the page comes from a session-backed {@code browse}
     * request.
     */
    @NonNull
    public FeedPage fetchFirstPage() throws IOException {
        ensureSession();
        final FeedPage candidate = bootstrappedPage;
        bootstrappedPage = null;
        if (candidate != null && !candidate.getItems().isEmpty()) {
            lastFailureDetail = bootstrapDetail;
            return candidate;
        }
        lastHttpCode = -1;
        try {
            final FeedPage page = parsePage(postJson("browse", firstPageBody()));
            lastFailureDetail = bootstrapDetail + "; browse: HTTP " + lastHttpCode
                    + ", items=" + page.getItems().size()
                    + ", visitor=" + (visitorData != null ? "yes" : "no");
            if (page.getItems().isEmpty()) {
                Log.w(TAG, "Home feed first page is empty: " + lastFailureDetail);
            }
            return page;
        } catch (final IOException e) {
            lastFailureDetail = bootstrapDetail + "; browse: error"
                    + ", visitor=" + (visitorData != null ? "yes" : "no");
            Log.w(TAG, "Home feed browse failed: " + lastFailureDetail, e);
            throw e;
        }
    }

    /**
     * Fetches the next page of the feed, using a continuation token from a
     * previous page and the session established by the bootstrap.
     */
    @NonNull
    public FeedPage fetchNextPage(@NonNull final String continuationToken) throws IOException {
        ensureSession();
        final String body = "{" + contextJson()
                + ",\"continuation\":\"" + escapeJson(continuationToken) + "\"}";
        return parsePage(postJson("browse", body));
    }

    @NonNull
    private String firstPageBody() {
        return "{" + contextJson()
                + ",\"browseId\":\"" + HOME_BROWSE_ID + "\"}";
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Session bootstrap
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Establishes the anonymous session, once per instance: fetches the
     * homepage like a desktop browser, keeps every cookie YouTube sets,
     * adopts the client version and visitor data of the page's config, and
     * parses the embedded {@code ytInitialData} as a candidate first page.
     * A failed bootstrap is not fatal — the browse request still runs,
     * just without a session — but its outcome is remembered in
     * {@link #bootstrapDetail} for the failure diagnostics.
     */
    private void ensureSession() {
        if (sessionStarted) {
            return;
        }
        sessionStarted = true;
        try {
            final OkHttpClient client = DownloaderImpl.getInstance().getClient();
            final Request request = new Request.Builder()
                    .url(HOME_PAGE_URL)
                    .get()
                    .addHeader("User-Agent", DownloaderImpl.USER_AGENT)
                    .addHeader("Accept-Language", "id")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                absorbCookies(response);
                final StringBuilder detail = new StringBuilder("html: HTTP ")
                        .append(response.code());
                final ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    bootstrapDetail = detail.append(", ytInitialData=no, items=0").toString();
                    return;
                }
                final String html = responseBody.string();
                final String scrapedVersion =
                        extractConfigValue(html, "INNERTUBE_CLIENT_VERSION");
                if (scrapedVersion != null) {
                    clientVersion = scrapedVersion;
                }
                if (visitorData == null) {
                    visitorData = extractConfigValue(html, "VISITOR_DATA");
                }
                try {
                    bootstrappedPage = parsePage(extractInitialData(html));
                    detail.append(", ytInitialData=yes, items=")
                            .append(bootstrappedPage.getItems().size());
                } catch (final IOException e) {
                    detail.append(", ytInitialData=no, items=0");
                }
                bootstrapDetail = detail.toString();
            }
        } catch (final IOException e) {
            bootstrapDetail = "html: error";
            Log.w(TAG, "Home session bootstrap failed", e);
        }
    }

    /**
     * Stores the cookies of a response in the session store, replacing
     * same-named ones, so they can be replayed on later requests.
     */
    private void absorbCookies(@NonNull final Response response) {
        for (final String header : response.headers("Set-Cookie")) {
            final int semicolon = header.indexOf(';');
            final String pair = (semicolon < 0 ? header : header.substring(0, semicolon))
                    .trim();
            final int equals = pair.indexOf('=');
            if (equals > 0) {
                sessionCookies.put(pair.substring(0, equals).trim(),
                        pair.substring(equals + 1).trim());
            }
        }
    }

    @Nullable
    private String cookieHeader() {
        if (sessionCookies.isEmpty()) {
            return null;
        }
        final StringBuilder builder = new StringBuilder();
        for (final Map.Entry<String, String> entry : sessionCookies.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
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
     * Reads a string value out of the page's ytcfg blob
     * ({@code "KEY":"..."}), or {@code null} when the key is absent.
     */
    @Nullable
    private static String extractConfigValue(@NonNull final String html,
                                             @NonNull final String key) {
        final String marker = "\"" + key + "\":\"";
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

    /*//////////////////////////////////////////////////////////////////////////
    // Response parsing
    //////////////////////////////////////////////////////////////////////////*/

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
        collectFromTree(root, items, seen, continuation);
        return new FeedPage(items, continuation[0]);
    }

    /**
     * Walks the whole response tree collecting video items and the feed's
     * continuation token. Item order follows the arrays of the response
     * (grids, shelves and continuation items all carry their entries in
     * arrays); the token of the last continuation item found wins, as grid
     * continuations are emitted after the items they continue.
     */
    private static void collectFromTree(@NonNull final Object node,
                                        @NonNull final List<HomeFeedItem> out,
                                        @NonNull final Set<String> seen,
                                        @NonNull final String[] continuation) {
        if (node instanceof JsonObject) {
            final JsonObject object = (JsonObject) node;
            JsonObject renderer = object.getObject("videoRenderer");
            if (renderer == null) {
                renderer = object.getObject("gridVideoRenderer");
            }
            if (renderer == null) {
                renderer = object.getObject("compactVideoRenderer");
            }
            if (renderer != null) {
                addVideo(renderer, out, seen);
            }
            final JsonObject lockup = object.getObject("lockupViewModel");
            if (lockup != null) {
                addLockup(lockup, out, seen);
            }
            final String token = continuationTokenOf(object);
            if (token != null) {
                continuation[0] = token;
            }
            for (final Object value : object.values()) {
                collectFromTree(value, out, seen, continuation);
            }
        } else if (node instanceof JsonArray) {
            final JsonArray array = (JsonArray) node;
            for (int i = 0; i < array.size(); i++) {
                collectFromTree(array.get(i), out, seen, continuation);
            }
        }
    }

    /**
     * The continuation token carried directly by one object, if it is a
     * continuation item (classic renderer or newer view model shape).
     */
    @Nullable
    private static String continuationTokenOf(@NonNull final JsonObject object) {
        final JsonObject continuationItem = object.getObject("continuationItemRenderer");
        if (continuationItem != null) {
            final JsonObject endpoint = continuationItem.getObject("continuationEndpoint");
            final JsonObject command = endpoint == null
                    ? null : endpoint.getObject("continuationCommand");
            final String token = command == null ? null : command.getString("token");
            if (token != null && !token.isEmpty()) {
                return token;
            }
        }
        final JsonObject viewModel = object.getObject("continuationItemViewModel");
        if (viewModel != null) {
            final JsonObject command = viewModel.getObject("continuationCommand");
            String token = command == null ? null : command.getString("token");
            if (token == null) {
                token = viewModel.getString("token");
            }
            if (token != null && !token.isEmpty()) {
                return token;
            }
        }
        return null;
    }

    /**
     * Adds one video of the classic renderer family ({@code videoRenderer}
     * and its grid/compact siblings, whose fields differ slightly — every
     * lookup falls back across the names the variants use).
     */
    private static void addVideo(@NonNull final JsonObject renderer,
                                 @NonNull final List<HomeFeedItem> out,
                                 @NonNull final Set<String> seen) {
        final String videoId = renderer.getString("videoId");
        if (videoId == null || videoId.isEmpty() || !seen.add(videoId)) {
            return;
        }
        String durationText = extractText(renderer.getObject("lengthText"));
        boolean live = hasLiveBadge(renderer);
        if (durationText == null) {
            // Grid renderers may carry the duration only in a thumbnail
            // overlay; a LIVE overlay text marks a running stream instead.
            final String overlayText = overlayDurationText(renderer);
            if ("LIVE".equals(overlayText)) {
                live = true;
            } else if (overlayText != null) {
                durationText = overlayText;
            }
        }
        if (durationText == null) {
            // No duration at all: a running or upcoming stream.
            live = true;
        }
        String viewCountText = extractText(renderer.getObject("viewCountText"));
        if (viewCountText == null) {
            viewCountText = extractText(renderer.getObject("shortViewCountText"));
        }
        out.add(new HomeFeedItem(
                videoId,
                extractText(renderer.getObject("title")),
                uploaderName(renderer),
                bestThumbnailUrl(renderer.getObject("thumbnail")),
                durationText,
                viewCountText,
                extractText(renderer.getObject("publishedTimeText")),
                live));
    }

    /**
     * Adds one video of the newer {@code lockupViewModel} family. Its
     * fields live in dedicated view models instead of text objects, so
     * every extraction is defensive: only the video id is mandatory, the
     * rest degrades to {@code null}.
     */
    private static void addLockup(@NonNull final JsonObject lockup,
                                  @NonNull final List<HomeFeedItem> out,
                                  @NonNull final Set<String> seen) {
        if (!LOCKUP_VIDEO_CONTENT_TYPE.equals(lockup.getString("contentType"))) {
            return;
        }
        final String videoId = lockup.getString("contentId");
        if (videoId == null || videoId.isEmpty() || !seen.add(videoId)) {
            return;
        }
        final JsonObject metadata = lockup.getObject("metadata");
        final JsonObject metadataViewModel = metadata == null
                ? null : metadata.getObject("lockupMetadataViewModel");
        String title = null;
        String uploaderName = null;
        String viewCountText = null;
        String publishedTimeText = null;
        if (metadataViewModel != null) {
            final JsonObject titleObject = metadataViewModel.getObject("title");
            if (titleObject != null) {
                title = titleObject.getString("content");
                if (title == null) {
                    title = extractText(titleObject);
                }
            }
            // Metadata rows: the first row names the uploader, the second
            // row's parts are the view count and the publish time.
            final JsonObject innerMetadata = metadataViewModel.getObject("metadata");
            final JsonObject contentMetadata = innerMetadata == null
                    ? null : innerMetadata.getObject("contentMetadataViewModel");
            final JsonArray rows = contentMetadata == null
                    ? null : contentMetadata.getArray("metadataRows");
            if (rows != null && !rows.isEmpty()) {
                uploaderName = metadataRowText(rows.get(0));
                if (rows.size() > 1 && rows.get(1) instanceof JsonObject) {
                    final JsonArray parts = ((JsonObject) rows.get(1)).getArray("parts");
                    if (parts != null && !parts.isEmpty()) {
                        viewCountText = partText(parts.get(0));
                        if (parts.size() > 1) {
                            publishedTimeText = partText(parts.get(1));
                        }
                    }
                }
            }
        }
        final JsonObject contentImage = lockup.getObject("contentImage");
        final JsonObject thumbnailViewModel = contentImage == null
                ? null : contentImage.getObject("thumbnailViewModel");
        String durationText = null;
        boolean live = false;
        if (thumbnailViewModel != null) {
            final JsonArray overlays = thumbnailViewModel.getArray("overlays");
            if (overlays != null) {
                for (int i = 0; i < overlays.size(); i++) {
                    final Object entry = overlays.get(i);
                    if (!(entry instanceof JsonObject)) {
                        continue;
                    }
                    final JsonObject bottomOverlay = ((JsonObject) entry)
                            .getObject("thumbnailBottomOverlayViewModel");
                    final JsonArray badges = bottomOverlay == null
                            ? null : bottomOverlay.getArray("badges");
                    if (badges == null || badges.isEmpty()
                            || !(badges.get(0) instanceof JsonObject)) {
                        continue;
                    }
                    final JsonObject badge = ((JsonObject) badges.get(0))
                            .getObject("thumbnailBadgeViewModel");
                    final String badgeText = badge == null ? null : badge.getString("text");
                    if ("LIVE".equals(badgeText)) {
                        live = true;
                    } else if (badgeText != null) {
                        durationText = badgeText;
                    }
                }
            }
        }
        out.add(new HomeFeedItem(
                videoId,
                title,
                uploaderName,
                lockupThumbnailUrl(thumbnailViewModel),
                durationText,
                viewCountText,
                publishedTimeText,
                live));
    }

    @Nullable
    private static String metadataRowText(@Nullable final Object row) {
        if (!(row instanceof JsonObject)) {
            return null;
        }
        final JsonArray parts = ((JsonObject) row).getArray("parts");
        if (parts == null) {
            return null;
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            final String text = partText(parts.get(i));
            if (text != null) {
                builder.append(text);
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    @Nullable
    private static String partText(@Nullable final Object part) {
        if (!(part instanceof JsonObject)) {
            return null;
        }
        final JsonObject text = ((JsonObject) part).getObject("text");
        if (text == null) {
            return null;
        }
        final String content = text.getString("content");
        return content != null ? content : extractText(text);
    }

    @Nullable
    private static String lockupThumbnailUrl(
            @Nullable final JsonObject thumbnailViewModel) {
        if (thumbnailViewModel == null) {
            return null;
        }
        final JsonObject image = thumbnailViewModel.getObject("image");
        final JsonArray sources = image == null ? null : image.getArray("sources");
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        // Sources are sorted by size ascending; the last one is the best.
        final Object last = sources.get(sources.size() - 1);
        return last instanceof JsonObject ? ((JsonObject) last).getString("url") : null;
    }

    @Nullable
    private static String uploaderName(@NonNull final JsonObject renderer) {
        String name = extractText(renderer.getObject("longBylineText"));
        if (name == null) {
            name = extractText(renderer.getObject("ownerText"));
        }
        if (name == null) {
            name = extractText(renderer.getObject("shortBylineText"));
        }
        return name;
    }

    private static boolean hasLiveBadge(@NonNull final JsonObject renderer) {
        final JsonArray badges = renderer.getArray("badges");
        if (badges == null) {
            return false;
        }
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
        return false;
    }

    @Nullable
    private static String overlayDurationText(@NonNull final JsonObject renderer) {
        final JsonArray overlays = renderer.getArray("thumbnailOverlays");
        if (overlays == null) {
            return null;
        }
        for (int i = 0; i < overlays.size(); i++) {
            final Object entry = overlays.get(i);
            if (entry instanceof JsonObject) {
                final JsonObject status = ((JsonObject) entry)
                        .getObject("thumbnailOverlayTimeStatusRenderer");
                if (status != null) {
                    return extractText(status.getObject("text"));
                }
            }
        }
        return null;
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

    /*//////////////////////////////////////////////////////////////////////////
    // Requests
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull
    private String contextJson() {
        final StringBuilder builder = new StringBuilder();
        builder.append("\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"")
                .append(clientVersion)
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
        final Request.Builder builder = new Request.Builder()
                .url(INNERTUBE_BASE_URL + path + "?key=" + API_KEY)
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8)))
                .addHeader("Content-Type", "application/json")
                .addHeader("X-YouTube-Client-Name", "1")
                .addHeader("X-YouTube-Client-Version", clientVersion)
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/");
        if (visitorData != null) {
            builder.addHeader("X-Goog-Visitor-Id", visitorData);
        }
        final String cookies = cookieHeader();
        if (cookies != null) {
            builder.addHeader("Cookie", cookies);
        }
        try (Response response = client.newCall(builder.build()).execute()) {
            lastHttpCode = response.code();
            absorbCookies(response);
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
