package org.schabi.newpipe.shorts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One entry of the Shorts feed: a video id and a thumbnail, as delivered by the
 * InnerTube reel sequence. Title and uploader are not part of the feed response
 * and are filled in later, once the short's {@code StreamInfo} has been resolved.
 */
public final class ShortsItem {
    private final String videoId;
    private final String thumbnailUrl;
    @Nullable
    private String title;
    @Nullable
    private String uploaderName;

    public ShortsItem(@NonNull final String videoId, @Nullable final String thumbnailUrl) {
        this.videoId = videoId;
        this.thumbnailUrl = thumbnailUrl;
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
    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    @Nullable
    public String getTitle() {
        return title;
    }

    public void setTitle(@Nullable final String title) {
        this.title = title;
    }

    @Nullable
    public String getUploaderName() {
        return uploaderName;
    }

    public void setUploaderName(@Nullable final String uploaderName) {
        this.uploaderName = uploaderName;
    }
}
