package org.schabi.newpipe.homefeed;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent store for the anonymous YouTube visitor session that backs
 * the home feed: the cookies YouTube issued, the visitor data and the
 * scraped client version, plus when the session was first created.
 *
 * <p>The home feed is a recommendations surface, and YouTube only serves
 * recommendations to a visitor identity that has accumulated some
 * history. A session that lives purely in memory starts from zero on
 * every app start and is served an empty "get started" feed forever, so
 * the session is kept in the default {@link SharedPreferences} instead
 * and survives restarts. These are anonymous visitor cookies of a
 * throwaway identity — not account credentials; no Google account is
 * involved anywhere in the home feed.</p>
 *
 * <p>Instances are immutable snapshots: {@link #load(Context)} reads the
 * stored state, {@link #store(Context, Map, String, String)} replaces it
 * wholesale (keeping the original creation timestamp). All access is
 * serialized on a class lock, which is enough for the client's
 * background-thread use.</p>
 */
public final class HomeSession {
    private static final String KEY_COOKIES = "home_session_cookies";
    private static final String KEY_VISITOR_DATA = "home_session_visitor_data";
    private static final String KEY_CLIENT_VERSION = "home_session_client_version";
    private static final String KEY_CREATED_MS = "home_session_created_ms";

    private static final Object LOCK = new Object();

    private final Map<String, String> cookies;
    @Nullable
    private final String visitorData;
    @Nullable
    private final String clientVersion;
    private final long createdAtMs;

    private HomeSession(@NonNull final Map<String, String> cookies,
                        @Nullable final String visitorData,
                        @Nullable final String clientVersion,
                        final long createdAtMs) {
        this.cookies = cookies;
        this.visitorData = visitorData;
        this.clientVersion = clientVersion;
        this.createdAtMs = createdAtMs;
    }

    /**
     * Reads the stored session, or an empty one when nothing was stored
     * yet (or the stored values were unusable).
     */
    @NonNull
    public static HomeSession load(@NonNull final Context context) {
        synchronized (LOCK) {
            final SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(context);
            final Map<String, String> cookies = new LinkedHashMap<>();
            final String serialized = prefs.getString(KEY_COOKIES, null);
            if (serialized != null) {
                for (final String entry : serialized.split("\n")) {
                    final int equals = entry.indexOf('=');
                    if (equals > 0) {
                        cookies.put(entry.substring(0, equals).trim(),
                                entry.substring(equals + 1).trim());
                    }
                }
            }
            return new HomeSession(cookies,
                    prefs.getString(KEY_VISITOR_DATA, null),
                    prefs.getString(KEY_CLIENT_VERSION, null),
                    prefs.getLong(KEY_CREATED_MS, 0L));
        }
    }

    /**
     * Replaces the stored session with the given state. The creation
     * timestamp of the first stored session is kept, so the age of the
     * visitor identity stays truthful across updates.
     */
    public static void store(@NonNull final Context context,
                             @NonNull final Map<String, String> cookies,
                             @Nullable final String visitorData,
                             @Nullable final String clientVersion) {
        synchronized (LOCK) {
            final SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(context);
            long createdAtMs = prefs.getLong(KEY_CREATED_MS, 0L);
            if (createdAtMs == 0L) {
                createdAtMs = System.currentTimeMillis();
            }
            final StringBuilder serialized = new StringBuilder();
            for (final Map.Entry<String, String> entry : cookies.entrySet()) {
                if (serialized.length() > 0) {
                    serialized.append('\n');
                }
                serialized.append(entry.getKey()).append('=').append(entry.getValue());
            }
            prefs.edit()
                    .putString(KEY_COOKIES, serialized.toString())
                    .putString(KEY_VISITOR_DATA, visitorData)
                    .putString(KEY_CLIENT_VERSION, clientVersion)
                    .putLong(KEY_CREATED_MS, createdAtMs)
                    .apply();
        }
    }

    /**
     * Drops the stored session entirely; the next home feed load starts
     * a fresh visitor identity.
     */
    public static void clear(@NonNull final Context context) {
        synchronized (LOCK) {
            PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .remove(KEY_COOKIES)
                    .remove(KEY_VISITOR_DATA)
                    .remove(KEY_CLIENT_VERSION)
                    .remove(KEY_CREATED_MS)
                    .apply();
        }
    }

    /**
     * A copy of the stored cookies, in the order they were stored.
     */
    @NonNull
    public Map<String, String> getCookies() {
        return new LinkedHashMap<>(cookies);
    }

    @Nullable
    public String getVisitorData() {
        return visitorData;
    }

    @Nullable
    public String getClientVersion() {
        return clientVersion;
    }

    /**
     * When this visitor identity was first created (epoch millis), or
     * {@code 0} for an empty session.
     */
    public long getCreatedAtMs() {
        return createdAtMs;
    }

    /**
     * Whether this snapshot holds no identity at all (no cookies and no
     * visitor data), i.e. nothing worth restoring.
     */
    public boolean isEmpty() {
        return cookies.isEmpty() && visitorData == null;
    }
}
