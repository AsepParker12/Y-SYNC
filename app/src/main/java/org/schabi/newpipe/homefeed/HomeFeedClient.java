package org.schabi.newpipe.homefeed;

import android.content.Context;
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
 * provides the session's client version and visitor data. The session is
 * then warmed up with one anonymous search, the way a real visitor has
 * activity before recommendations attach. The homepage's embedded
 * {@code ytInitialData} doubles as a candidate first page; when it yields
 * nothing, the first page is a {@code browse} request for
 * {@code FEwhat_to_watch} inside that session — and when even that comes
 * back empty, one more {@code browse} as the Android client, whose home
 * recommendations are attached more liberally. Every following page is a
 * {@code browse} request holding only the continuation token of the
 * previous one, sent with the client that produced the first page.
 * Visitor data of later responses is captured and sent along, so YouTube
 * keeps the feed session consistent.</p>
 *
 * <p>The session is persisted across app starts ({@link HomeSession}):
 * a fresh identity is served YouTube's empty "get started" home (a feed
 * nudge, zero videos) until it has accumulated watch history — history
 * the app reports for real watches via {@link HomeWatchReporter} — so
 * the identity has to survive restarts for real recommendations to ever
 * attach. Until they do, a first page that comes back empty on every
 * real route is assembled from anonymous searches instead (the
 * discovery fallback below), which YouTube answers for any identity;
 * the real routes are still tried first on every cold start, and take
 * over again the moment they serve videos.</p>
 *
 * <p>Videos are collected by walking the whole response tree: the classic
 * {@code videoRenderer} family (also {@code gridVideoRenderer} and
 * {@code compactVideoRenderer}, bare or wrapped in a
 * {@code richItemRenderer}) and the newer {@code lockupViewModel} items.
 * Shelves of other kinds (Shorts/reel shelves, ads, sections) are skipped.
 * Results are deduplicated by video id. The same walk counts the renderer
 * families it passes, so the failure diagnostics can show what a
 * video-less response actually contained.</p>
 *
 * <p>All methods perform blocking network I/O and must be called off the
 * main thread. Instances are stateful (they hold the session) and are not
 * thread-safe; use one instance per feed.</p>
 */
public final class HomeFeedClient {
    private static final String TAG = HomeFeedClient.class.getSimpleName();

    // Public API key of the YouTube web client, used by every InnerTube caller.
    static final String API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";
    // Used only until the homepage bootstrap scrapes the live client version.
    static final String FALLBACK_CLIENT_VERSION = "2.20251001.01.00";
    static final String INNERTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1/";
    private static final String HOME_PAGE_URL = "https://www.youtube.com/";
    private static final String HOME_BROWSE_ID = "FEwhat_to_watch";
    private static final String LOCKUP_VIDEO_CONTENT_TYPE = "LOCKUP_CONTENT_TYPE_VIDEO";

    // Public API key, client version and SDK level of the YouTube Android
    // app, for the fallback browse route (the values open-source clients
    // use for the Android InnerTube client).
    private static final String ANDROID_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";
    private static final String ANDROID_CLIENT_VERSION = "20.10.38";
    private static final int ANDROID_SDK_VERSION = 30;

    // Renderer families counted in every parsed response, for the failure
    // diagnostics: when a page yields no videos, the census shows what
    // YouTube actually served instead. The labels are the short forms
    // used in the diagnostic line.
    private static final String[] CENSUS_KEYS = {
        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer",
        "lockupViewModel", "richItemRenderer", "richSectionRenderer",
        "richShelfRenderer", "reelItemRenderer", "shortsLockupViewModel",
        "videoListItemViewModel"
    };
    private static final String[] CENSUS_LABELS = {
        "video", "gridVideo", "compactVideo", "lockup", "richItem",
        "richSection", "richShelf", "reel", "shortsLockup", "listItem"
    };
    private static final int CENSUS_VIDEO_INDEX = 0;
    private static final int CENSUS_LOCKUP_INDEX = 3;

    // Discovery fallback: when YouTube serves a fresh visitor identity
    // its empty "get started" home (a feed nudge instead of videos), the
    // feed is assembled from anonymous searches on broad topics instead,
    // which YouTube answers for any identity. The topics rotate a few at
    // a time so the feed keeps mixing subjects as it pages.
    private static final String[] DISCOVERY_QUERIES = {
        "musik", "game", "berita", "sepak bola", "komedi", "film",
        "teknologi", "kuliner"
    };
    private static final int DISCOVERY_TOPICS_PER_PAGE = 3;
    private static final int DISCOVERY_MAX_FIRST_PAGE_ROUNDS = 3;
    private static final String DISCOVERY_TOKEN = "ysync-discovery-feed";

    private final Context appContext;
    private final Map<String, String> sessionCookies = new LinkedHashMap<>();
    private final List<DiscoveryTopic> discoveryTopics = new ArrayList<>();
    private final Set<String> discoverySeenIds = new LinkedHashSet<>();
    private String clientVersion = FALLBACK_CLIENT_VERSION;

    @Nullable
    private String visitorData;
    private boolean sessionStarted;
    private boolean sessionWarmedUp;
    private boolean feedFromAndroid;
    private boolean feedFromDiscovery;
    private int discoveryCursor;
    @NonNull
    private String lastCensus = "";
    @Nullable
    private FeedPage bootstrappedPage;
    @Nullable
    private String bootstrapDetail;
    private int lastHttpCode = -1;
    @Nullable
    private String lastFailureDetail;

    /**
     * Creates a client whose anonymous session is persisted in the
     * given context's default shared preferences (see
     * {@link HomeSession}); only the application context is kept.
     */
    public HomeFeedClient(@NonNull final Context context) {
        appContext = context.getApplicationContext();
    }

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
     * bootstrapped (or restored from the store) and warmed up first
     * (once per instance); the homepage's own {@code ytInitialData} is
     * used when it already carries videos, otherwise the page comes
     * from a session-backed {@code browse} request, with an
     * Android-client {@code browse} as the next resort when the web
     * session is served an empty home — and when every real route
     * serves the empty "get started" home, the page is assembled from
     * anonymous searches (the discovery fallback) instead.
     */
    @NonNull
    public FeedPage fetchFirstPage() throws IOException {
        // Every first page starts over: the real routes are tried first
        // (an aged session may receive real recommendations by now), and
        // the discovery fallback state is rebuilt from scratch.
        feedFromDiscovery = false;
        discoveryTopics.clear();
        discoverySeenIds.clear();
        discoveryCursor = 0;
        ensureSession();
        warmUpSession();
        final FeedPage candidate = bootstrappedPage;
        bootstrappedPage = null;
        if (candidate != null && !candidate.getItems().isEmpty()) {
            feedFromAndroid = false;
            lastFailureDetail = bootstrapDetail;
            return candidate;
        }
        lastHttpCode = -1;
        final FeedPage page;
        try {
            page = parsePage(postJson("browse", firstPageBody()));
        } catch (final IOException e) {
            lastFailureDetail = bootstrapDetail + "; browse: error"
                    + ", visitor=" + (visitorData != null ? "yes" : "no");
            Log.w(TAG, "Home feed browse failed: " + lastFailureDetail, e);
            throw e;
        }
        final String webDetail = bootstrapDetail + "; browse: HTTP " + lastHttpCode
                + ", items=" + page.getItems().size()
                + ", visitor=" + (visitorData != null ? "yes" : "no");
        if (!page.getItems().isEmpty()) {
            feedFromAndroid = false;
            lastFailureDetail = webDetail;
            return page;
        }
        final FeedPage androidPage = fetchFirstPageFromAndroid(page, webDetail);
        if (!androidPage.getItems().isEmpty()) {
            return androidPage;
        }
        return fetchDiscoveryFirstPage(androidPage);
    }

    /**
     * Last first-page route: a {@code browse} for the home feed as the
     * Android client, whose recommendations are attached more liberally
     * than the web client's. When it yields videos, the whole feed
     * session switches to that client (its continuations only make sense
     * there); when it yields nothing either, its page is returned so the
     * error screen can show the census of what was actually served; when
     * it fails outright, the web page (equally empty) is returned
     * instead, with the web response's census.
     */
    @NonNull
    private FeedPage fetchFirstPageFromAndroid(@NonNull final FeedPage webPage,
                                               @NonNull final String webDetail) {
        final String webCensus = lastCensus;
        lastHttpCode = -1;
        try {
            final FeedPage page = parsePage(
                    postJsonAndroid("browse", androidFirstPageBody()));
            feedFromAndroid = true;
            lastFailureDetail = webDetail + "; android: HTTP " + lastHttpCode
                    + ", items=" + page.getItems().size();
            if (page.getItems().isEmpty()) {
                lastFailureDetail += "; census: " + lastCensus;
                Log.w(TAG, "Home feed first page is empty: " + lastFailureDetail);
            }
            return page;
        } catch (final IOException e) {
            Log.w(TAG, "Home feed Android browse failed", e);
            feedFromAndroid = false;
            lastFailureDetail = webDetail + "; android: error; census: " + webCensus;
            Log.w(TAG, "Home feed first page is empty: " + lastFailureDetail);
            return webPage;
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Discovery fallback (search-assembled feed)
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * One discovery topic: a search query whose pages are pulled into
     * the feed in rotation. A topic is exhausted once YouTube stops
     * handing out continuations for it (or its requests start failing).
     */
    private static final class DiscoveryTopic {
        private final String query;
        @Nullable
        private String continuation;
        private boolean started;
        private boolean exhausted;

        DiscoveryTopic(@NonNull final String query) {
            this.query = query;
        }
    }

    /**
     * Last resort when every real route served the empty "get started"
     * home: assemble the first page from anonymous searches instead.
     * When even the searches yield nothing, the empty real page is
     * returned (so the error screen keeps the browse census) with the
     * discovery outcome appended to the failure detail.
     */
    @NonNull
    private FeedPage fetchDiscoveryFirstPage(@NonNull final FeedPage emptyPage) {
        FeedPage discoveryPage = null;
        for (int round = 0; round < DISCOVERY_MAX_FIRST_PAGE_ROUNDS; round++) {
            discoveryPage = fetchDiscoveryPage();
            if (discoveryPage == null || !discoveryPage.getItems().isEmpty()
                    || discoveryPage.getContinuationToken() == null) {
                break;
            }
        }
        if (discoveryPage == null || discoveryPage.getItems().isEmpty()) {
            lastFailureDetail += "; discovery: items=0";
            Log.w(TAG, "Home feed discovery fallback is empty: " + lastFailureDetail);
            return emptyPage;
        }
        feedFromDiscovery = true;
        return discoveryPage;
    }

    /**
     * Fetches one discovery round: one search page from each of the
     * next topics in rotation, interleaved round-robin and deduplicated
     * against everything the discovery feed has already served. The
     * returned page's continuation is a placeholder token while any
     * topic still has pages left, and {@code null} once the whole
     * rotation is exhausted. Returns {@code null} when no topic could
     * be fetched at all in this round.
     */
    @Nullable
    private FeedPage fetchDiscoveryPage() {
        if (discoveryTopics.isEmpty()) {
            for (final String query : DISCOVERY_QUERIES) {
                discoveryTopics.add(new DiscoveryTopic(query));
            }
            discoveryCursor = 0;
        }
        final List<List<HomeFeedItem>> perTopicItems = new ArrayList<>();
        int fetched = 0;
        int scanned = 0;
        while (fetched < DISCOVERY_TOPICS_PER_PAGE
                && scanned < discoveryTopics.size()) {
            final DiscoveryTopic topic = discoveryTopics.get(discoveryCursor);
            discoveryCursor = (discoveryCursor + 1) % discoveryTopics.size();
            scanned++;
            if (topic.exhausted) {
                continue;
            }
            final List<HomeFeedItem> topicItems = fetchDiscoveryTopicPage(topic);
            if (topicItems != null) {
                perTopicItems.add(topicItems);
                fetched++;
            }
        }
        if (perTopicItems.isEmpty()) {
            return null;
        }
        final List<HomeFeedItem> items = new ArrayList<>();
        boolean anyLeft = true;
        for (int index = 0; anyLeft; index++) {
            anyLeft = false;
            for (final List<HomeFeedItem> topicItems : perTopicItems) {
                if (index < topicItems.size()) {
                    anyLeft = true;
                    final HomeFeedItem item = topicItems.get(index);
                    if (discoverySeenIds.add(item.getVideoId())) {
                        items.add(item);
                    }
                }
            }
        }
        return new FeedPage(items,
                hasDiscoveryTopicsLeft() ? DISCOVERY_TOKEN : null);
    }

    /**
     * Fetches the next search page of one discovery topic (its first
     * page when it has not started yet) through the session's search
     * plumbing. The parse deliberately does not touch the renderer
     * census, which keeps describing the last real browse response.
     * A failed topic is dropped from the rotation instead of being
     * retried forever.
     */
    @Nullable
    private List<HomeFeedItem> fetchDiscoveryTopicPage(
            @NonNull final DiscoveryTopic topic) {
        final String body;
        if (!topic.started) {
            body = "{" + contextJson() + ",\"query\":\""
                    + escapeJson(topic.query) + "\"}";
        } else if (topic.continuation != null) {
            body = "{" + contextJson() + ",\"continuation\":\""
                    + escapeJson(topic.continuation) + "\"}";
        } else {
            topic.exhausted = true;
            return null;
        }
        final FeedPage page;
        try {
            page = parsePage(postJson("search", body), false);
        } catch (final IOException e) {
            Log.w(TAG, "Discovery search failed for query: " + topic.query, e);
            topic.exhausted = true;
            return null;
        }
        topic.started = true;
        topic.continuation = page.getContinuationToken();
        if (topic.continuation == null) {
            topic.exhausted = true;
        }
        return page.getItems();
    }

    private boolean hasDiscoveryTopicsLeft() {
        for (final DiscoveryTopic topic : discoveryTopics) {
            if (!topic.exhausted) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetches the next page of the feed, using a continuation token from
     * a previous page. The request goes to the client that produced the
     * first page (the web session or the Android client), as a
     * continuation only makes sense with the client that issued it. In
     * discovery mode the token is a placeholder: the next topics of the
     * rotation are advanced instead, each with its own token.
     */
    @NonNull
    public FeedPage fetchNextPage(@NonNull final String continuationToken) throws IOException {
        ensureSession();
        if (feedFromDiscovery) {
            final FeedPage page = fetchDiscoveryPage();
            return page != null ? page : new FeedPage(new ArrayList<>(), null);
        }
        if (feedFromAndroid) {
            final String body = "{" + androidContextJson()
                    + ",\"continuation\":\"" + escapeJson(continuationToken) + "\"}";
            return parsePage(postJsonAndroid("browse", body));
        }
        final String body = "{" + contextJson()
                + ",\"continuation\":\"" + escapeJson(continuationToken) + "\"}";
        return parsePage(postJson("browse", body));
    }

    @NonNull
    private String firstPageBody() {
        return "{" + contextJson()
                + ",\"browseId\":\"" + HOME_BROWSE_ID + "\"}";
    }

    @NonNull
    private String androidFirstPageBody() {
        return "{" + androidContextJson()
                + ",\"browseId\":\"" + HOME_BROWSE_ID + "\"}";
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Session bootstrap
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Establishes the anonymous session, once per instance. A session
     * stored by an earlier run ({@link HomeSession}) is restored and
     * the homepage fetch skipped — the visitor identity must survive
     * restarts so it can accumulate the history YouTube attaches
     * recommendations to. Only a missing or incomplete stored session
     * (no client version to scrape-freeze) goes through the homepage
     * fetch: like a desktop browser, keeping every cookie YouTube sets,
     * adopting the client version and visitor data of the page's
     * config, and parsing the embedded {@code ytInitialData} as a
     * candidate first page. A failed bootstrap is not fatal — the
     * browse request still runs, just without a session — but its
     * outcome is remembered in {@link #bootstrapDetail} for the failure
     * diagnostics.
     */
    private void ensureSession() {
        if (sessionStarted) {
            return;
        }
        sessionStarted = true;
        final HomeSession stored = HomeSession.load(appContext);
        if (!stored.isEmpty()) {
            sessionCookies.putAll(stored.getCookies());
            if (visitorData == null) {
                visitorData = stored.getVisitorData();
            }
            if (stored.getClientVersion() != null) {
                clientVersion = stored.getClientVersion();
                bootstrapDetail = "html: stored session, ytInitialData=no, items=0";
                return;
            }
            // Partial stored session (no client version yet): fall
            // through to the homepage fetch, which refreshes the
            // cookies and scrapes the version; the preloaded values
            // stay as fallbacks.
        }
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
        persistSession();
    }

    /**
     * Writes the working session (cookies, visitor data, client
     * version) back to the persistent store, so the visitor identity
     * continues where it left off on the next app start.
     */
    private void persistSession() {
        HomeSession.store(appContext, sessionCookies, visitorData, clientVersion);
    }

    /**
     * Warms the fresh session up with one anonymous search whose results
     * are discarded. A real visitor session has activity before home
     * recommendations attach to it; a completely cold session is served
     * an empty home instead. Runs once per instance, inside the session
     * established by the bootstrap (same cookies, visitor data and
     * client version). A failed warm-up is not fatal.
     */
    private void warmUpSession() {
        if (sessionWarmedUp) {
            return;
        }
        sessionWarmedUp = true;
        try {
            postJson("search", "{" + contextJson() + ",\"query\":\"music\"}");
        } catch (final IOException e) {
            Log.w(TAG, "Home session warm-up failed", e);
        }
    }

    /**
     * Stores the cookies of a response in the session store, replacing
     * same-named ones, so they can be replayed on later requests. When
     * a cookie actually changed, the session is written back to the
     * persistent store right away, so rotations are never lost.
     */
    private void absorbCookies(@NonNull final Response response) {
        boolean changed = false;
        for (final String header : response.headers("Set-Cookie")) {
            final int semicolon = header.indexOf(';');
            final String pair = (semicolon < 0 ? header : header.substring(0, semicolon))
                    .trim();
            final int equals = pair.indexOf('=');
            if (equals > 0) {
                final String name = pair.substring(0, equals).trim();
                final String value = pair.substring(equals + 1).trim();
                if (!value.equals(sessionCookies.put(name, value))) {
                    changed = true;
                }
            }
        }
        if (changed) {
            persistSession();
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
        return parsePage(root, true);
    }

    /**
     * Parses one InnerTube response into a feed page. The renderer
     * census is only updated for real browse responses
     * ({@code trackCensus}): the failure diagnostics describe what the
     * home browse routes served, and discovery search parses must not
     * overwrite that picture with search results.
     */
    @NonNull
    private FeedPage parsePage(@NonNull final JsonObject root,
                               final boolean trackCensus) {
        final JsonObject responseContext = root.getObject("responseContext");
        if (responseContext != null) {
            final String newVisitorData = responseContext.getString("visitorData");
            if (newVisitorData != null && !newVisitorData.isEmpty()
                    && !newVisitorData.equals(visitorData)) {
                visitorData = newVisitorData;
                persistSession();
            }
        }
        final List<HomeFeedItem> items = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        final String[] continuation = new String[1];
        final int[] census = new int[CENSUS_KEYS.length];
        collectFromTree(root, items, seen, continuation, census);
        if (trackCensus) {
            lastCensus = formatCensus(census);
        }
        return new FeedPage(items, continuation[0]);
    }

    /**
     * Formats a renderer census compactly for the failure diagnostics.
     * Only the families that were actually served are listed, except the
     * two headline ones (plain videos and lockups), which are always
     * shown so that a zero there is visible instead of silent.
     */
    @NonNull
    private static String formatCensus(@NonNull final int[] census) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < census.length; i++) {
            if (census[i] == 0 && i != CENSUS_VIDEO_INDEX && i != CENSUS_LOCKUP_INDEX) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(CENSUS_LABELS[i]).append('=').append(census[i]);
        }
        return builder.toString();
    }

    /**
     * Walks the whole response tree collecting video items and the feed's
     * continuation token. Item order follows the arrays of the response
     * (grids, shelves and continuation items all carry their entries in
     * arrays); the token of the last continuation item found wins, as grid
     * continuations are emitted after the items they continue. The walk
     * also counts the renderer families it passes into {@code census}
     * (indexed by {@link #CENSUS_KEYS}), for the failure diagnostics.
     */
    private static void collectFromTree(@NonNull final Object node,
                                        @NonNull final List<HomeFeedItem> out,
                                        @NonNull final Set<String> seen,
                                        @NonNull final String[] continuation,
                                        @NonNull final int[] census) {
        if (node instanceof JsonObject) {
            final JsonObject object = (JsonObject) node;
            for (int i = 0; i < CENSUS_KEYS.length; i++) {
                if (object.has(CENSUS_KEYS[i])) {
                    census[i]++;
                }
            }
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
                collectFromTree(value, out, seen, continuation, census);
            }
        } else if (node instanceof JsonArray) {
            final JsonArray array = (JsonArray) node;
            for (int i = 0; i < array.size(); i++) {
                collectFromTree(array.get(i), out, seen, continuation, census);
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
    private String androidContextJson() {
        final StringBuilder builder = new StringBuilder();
        builder.append("\"context\":{\"client\":{\"clientName\":\"ANDROID\",")
                .append("\"clientVersion\":\"")
                .append(ANDROID_CLIENT_VERSION)
                .append("\",\"androidSdkVersion\":")
                .append(ANDROID_SDK_VERSION)
                .append(",\"hl\":\"id\",\"gl\":\"ID\"");
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
        return doPost(builder.build(), path, true);
    }

    /**
     * Posts as the Android client: its own public API key and client
     * headers, and no web session cookies — the session's visitor data
     * travels in the request context instead.
     */
    @NonNull
    private JsonObject postJsonAndroid(@NonNull final String path, @NonNull final String body)
            throws IOException {
        final Request request = new Request.Builder()
                .url(INNERTUBE_BASE_URL + path + "?key=" + ANDROID_API_KEY)
                .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8)))
                .addHeader("Content-Type", "application/json")
                .addHeader("X-YouTube-Client-Name", "3")
                .addHeader("X-YouTube-Client-Version", ANDROID_CLIENT_VERSION)
                .addHeader("User-Agent", "com.google.android.youtube/"
                        + ANDROID_CLIENT_VERSION + " (Linux; U; Android 11)")
                .build();
        return doPost(request, path, false);
    }

    @NonNull
    private JsonObject doPost(@NonNull final Request request, @NonNull final String path,
                              final boolean absorbResponseCookies) throws IOException {
        final OkHttpClient client = DownloaderImpl.getInstance().getClient();
        try (Response response = client.newCall(request).execute()) {
            lastHttpCode = response.code();
            if (absorbResponseCookies) {
                absorbCookies(response);
            }
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
