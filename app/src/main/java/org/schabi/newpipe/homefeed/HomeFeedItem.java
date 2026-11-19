package org.schabi.newpipe.homefeed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One entry of the home feed: a video as delivered by the InnerTube browse
 * response for {@code FEwhat_to_watch}. All display strings are taken from the
 * response as-is (title, channel, duration, view count and publish time), so
 * no per-item extractor call is needed.
 */
public final class HomeFeedItem {
    private final String videoId;
    @Nullable
    private final String title;
    @Nullable
    private final String uploaderName;
    @Nullable
    private final String thumbnailUrl;
    @Nullable
    private final String durationText;
    @Nullable
    private final String viewCountText;
    @Nullable
    private final String publishedTimeText;
    private final boolean live;

    public HomeFeedItem(@NonNull final String videoId,
                        @Nullable final String title,
                        @Nullable final String uploaderName,
                        @Nullable final String thumbnailUrl,
                        @Nullable final String durationText,
                        @Nullable final String viewCountText,
                        @Nullable final String publishedTimeText,
                        final boolean live) {
        this.videoId = videoId;
        this.title = title;
        this.uploaderName = uploaderName;
        this.thumbnailUrl = thumbnailUrl;
        this.durationText = durationText;
        this.viewCountText = viewCountText;
        this.publishedTimeText = publishedTimeText;
        this.live = live;
    }

    @NonNull
    public String getVideoId() {
        return videoId;
    }

    @NonNull
    public String getUrl() {
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    @Nullable
    public String getTitle() {
        return title;
    }

    @Nullable
    public String getUploaderName() {
        return uploaderName;
    }

    @Nullable
    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    @Nullable
    public String getDurationText() {
        return durationText;
    }

    @Nullable
    public String getViewCountText() {
        return viewCountText;
    }

    @Nullable
    public String getPublishedTimeText() {
        return publishedTimeText;
    }

    public boolean isLive() {
        return live;
    }
}
