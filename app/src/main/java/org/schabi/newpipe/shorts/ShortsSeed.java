package org.schabi.newpipe.shorts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import io.reactivex.rxjava3.core.Single;

/**
 * A one-shot hand-off that opens the Shorts pager on a given list of shorts
 * instead of the endless InnerTube feed — used when a Short is tapped in a
 * channel page's Shorts tab, so swiping continues through that channel's
 * shorts rather than dropping into the generic feed.
 *
 * <p>The seed is passed through a static slot (consumed exactly once by
 * {@link ShortsFragment}) because the item list and the paging callback
 * cannot travel in a fragment argument {@code Bundle}. If the slot is empty
 * when the fragment starts — e.g. after process death — the fragment falls
 * back to the normal feed.</p>
 */
public final class ShortsSeed {

    /**
     * Loads the next page of the seeded list. The returned {@link Single}
     * must already deliver fully mapped items; an empty list marks the end
     * of the seeded list.
     */
    public interface PageLoader {
        @NonNull
        Single<List<ShortsItem>> loadNextPage();
    }

    @Nullable
    private static ShortsSeed pending;

    private final List<ShortsItem> items;
    private final int startIndex;
    @Nullable
    private final PageLoader pageLoader;

    public ShortsSeed(@NonNull final List<ShortsItem> items, final int startIndex,
                      @Nullable final PageLoader pageLoader) {
        this.items = items;
        this.startIndex = startIndex;
        this.pageLoader = pageLoader;
    }

    public static void setPending(@NonNull final ShortsSeed seed) {
        pending = seed;
    }

    /**
     * Takes the pending seed, clearing the slot. Returns {@code null} when
     * no seed is waiting.
     */
    @Nullable
    public static ShortsSeed takePending() {
        final ShortsSeed seed = pending;
        pending = null;
        return seed;
    }

    @NonNull
    public List<ShortsItem> getItems() {
        return items;
    }

    public int getStartIndex() {
        return startIndex;
    }

    @Nullable
    public PageLoader getPageLoader() {
        return pageLoader;
    }
}
