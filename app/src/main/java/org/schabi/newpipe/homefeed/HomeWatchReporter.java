package org.schabi.newpipe.homefeed;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Registers real watches with YouTube under the home feed's shared
 * anonymous visitor identity ({@link HomeSession}), so the identity
 * accumulates the watch history YouTube requires before it serves home
 * recommendations.
 *
 * <p>The extractor exposes no playback-tracking (videostats) URLs, so
 * the signal is an InnerTube {@code player} request for the watched
 * video, sent with the session's cookies, visitor data and client
 * version — the same call a real client makes when a watch starts, and
 * the watch-intent signal YouTube attaches history to. The request runs
 * on a dedicated background thread, at most once per video per app
 * process, and every failure is swallowed: reporting must never disturb
 * playback.</p>
 */
public final class HomeWatchReporter {
    private static final Set<String> REPORTED_VIDEO_IDS =
            Collections.synchronizedSet(new HashSet<>());

    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "HomeWatchReporter");
                thread.setDaemon(true);
                return thread;
            });

    private HomeWatchReporter() {
    }

    /**
     * Reports that the given stream started playing. Cheap and
     * non-blocking; safe to call from the player's main thread.
     */
    public static void reportWatch(@NonNull final Context context,
                                   @NonNull final StreamInfo info) {
        try {
            if (info.getServiceId() != ServiceList.YouTube.getServiceId()) {
                return;
            }
            final String videoId = NewPipe.getService(info.getServiceId())
                    .getStreamLHFactory().getId(info.getUrl());
            if (videoId == null || videoId.isEmpty()
                    || !REPORTED_VIDEO_IDS.add(videoId)) {
                return;
            }
            final Context appContext = context.getApplicationContext();
            EXECUTOR.execute(() -> sendPlayerRequest(appContext, videoId));
        } catch (final Throwable ignored) {
            // Watch reporting must never disturb playback.
        }
    }

    private static void sendPlayerRequest(@NonNull final Context context,
                                          @NonNull final String videoId) {
        try {
            final HomeSession session = HomeSession.load(context);
            final String storedVersion = session.getClientVersion();
            final String clientVersion = storedVersion != null
                    ? storedVersion : HomeFeedClient.FALLBACK_CLIENT_VERSION;
            final String visitorData = session.getVisitorData();

            final StringBuilder contextJson = new StringBuilder();
            contextJson.append("\"context\":{\"client\":{\"clientName\":\"WEB\",")
                    .append("\"clientVersion\":\"").append(clientVersion)
                    .append("\",\"hl\":\"id\",\"gl\":\"ID\"");
            if (visitorData != null) {
                contextJson.append(",\"visitorData\":\"")
                        .append(escapeJson(visitorData)).append("\"");
            }
            contextJson.append("}}");
            final String body = "{" + contextJson
                    + ",\"videoId\":\"" + escapeJson(videoId) + "\""
                    + ",\"racyCheckOk\":true,\"contentCheckOk\":true}";

            final Request.Builder builder = new Request.Builder()
                    .url(HomeFeedClient.INNERTUBE_BASE_URL + "player?key="
                            + HomeFeedClient.API_KEY)
                    .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8)))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-YouTube-Client-Name", "1")
                    .addHeader("X-YouTube-Client-Version", clientVersion)
                    .addHeader("Origin", "https://www.youtube.com")
                    .addHeader("Referer", "https://www.youtube.com/");
            if (visitorData != null) {
                builder.addHeader("X-Goog-Visitor-Id", visitorData);
            }
            final Map<String, String> cookies = session.getCookies();
            final String cookieHeader = cookieHeader(cookies);
            if (cookieHeader != null) {
                builder.addHeader("Cookie", cookieHeader);
            }

            final OkHttpClient client = DownloaderImpl.getInstance().getClient();
            String newVisitorData = null;
            try (Response response = client.newCall(builder.build()).execute()) {
                absorbCookies(response, cookies);
                final ResponseBody responseBody = response.body();
                if (response.isSuccessful() && responseBody != null) {
                    final JsonObject root =
                            JsonParser.object().from(responseBody.string());
                    final JsonObject responseContext = root.getObject("responseContext");
                    if (responseContext != null) {
                        newVisitorData = responseContext.getString("visitorData");
                    }
                }
            }
            // Hand the session back with whatever YouTube rotated into it
            // (fresh cookies, possibly a new visitor data), so the shared
            // identity stays continuous for the home feed.
            HomeSession.store(context, cookies,
                    newVisitorData != null && !newVisitorData.isEmpty()
                            ? newVisitorData : visitorData,
                    storedVersion);
        } catch (final Throwable ignored) {
            // Watch reporting must never disturb playback.
        }
    }

    private static void absorbCookies(@NonNull final Response response,
                                      @NonNull final Map<String, String> cookies) {
        for (final String header : response.headers("Set-Cookie")) {
            final int semicolon = header.indexOf(';');
            final String pair = (semicolon < 0 ? header : header.substring(0, semicolon))
                    .trim();
            final int equals = pair.indexOf('=');
            if (equals > 0) {
                cookies.put(pair.substring(0, equals).trim(),
                        pair.substring(equals + 1).trim());
            }
        }
    }

    @Nullable
    private static String cookieHeader(@NonNull final Map<String, String> cookies) {
        if (cookies.isEmpty()) {
            return null;
        }
        final StringBuilder builder = new StringBuilder();
        for (final Map.Entry<String, String> entry : cookies.entrySet()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    @NonNull
    private static String escapeJson(@NonNull final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
