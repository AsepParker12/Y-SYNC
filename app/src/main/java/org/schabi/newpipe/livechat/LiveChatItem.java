package org.schabi.newpipe.livechat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One immutable live chat message, flattened from an InnerTube chat item
 * renderer by {@link LiveChatClient}. Emoji runs are already flattened into
 * {@link #getMessageText()}: standard emoji as their unicode character,
 * channel custom emoji as their {@code :shortcut:} text, so a run is never
 * dropped even though v1 does not render emoji images.
 */
public final class LiveChatItem {

    public enum Type {
        TEXT,
        PAID,
        MEMBERSHIP,
        SYSTEM
    }

    private final String id;
    private final String authorName;
    private final String authorChannelId;
    private final String messageText;
    @Nullable
    private final String authorPhotoUrl;
    private final long timestampUsec;
    private final Type type;
    @Nullable
    private final String paidAmountText;
    private final int paidBackgroundColor;
    private final boolean superSticker;
    private final boolean authorIsOwner;
    private final boolean authorIsModerator;
    private final boolean authorIsVerified;
    private final boolean authorIsMember;

    public LiveChatItem(@NonNull final String id,
                        @NonNull final String authorName,
                        @NonNull final String authorChannelId,
                        @NonNull final String messageText,
                        @Nullable final String authorPhotoUrl,
                        final long timestampUsec,
                        @NonNull final Type type,
                        @Nullable final String paidAmountText,
                        final int paidBackgroundColor,
                        final boolean superSticker,
                        final boolean authorIsOwner,
                        final boolean authorIsModerator,
                        final boolean authorIsVerified,
                        final boolean authorIsMember) {
        this.id = id;
        this.authorName = authorName;
        this.authorChannelId = authorChannelId;
        this.messageText = messageText;
        this.authorPhotoUrl = authorPhotoUrl;
        this.timestampUsec = timestampUsec;
        this.type = type;
        this.paidAmountText = paidAmountText;
        this.paidBackgroundColor = paidBackgroundColor;
        this.superSticker = superSticker;
        this.authorIsOwner = authorIsOwner;
        this.authorIsModerator = authorIsModerator;
        this.authorIsVerified = authorIsVerified;
        this.authorIsMember = authorIsMember;
    }

    /**
     * Returns a copy of this item with a different message text (used when a
     * moderator deletes the message and only its replacement text remains).
     */
    @NonNull
    public LiveChatItem withMessageText(@NonNull final String newMessageText) {
        return new LiveChatItem(id, authorName, authorChannelId, newMessageText,
                authorPhotoUrl, timestampUsec, type, paidAmountText,
                paidBackgroundColor, superSticker, authorIsOwner, authorIsModerator,
                authorIsVerified, authorIsMember);
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getAuthorName() {
        return authorName;
    }

    @NonNull
    public String getAuthorChannelId() {
        return authorChannelId;
    }

    @NonNull
    public String getMessageText() {
        return messageText;
    }

    @Nullable
    public String getAuthorPhotoUrl() {
        return authorPhotoUrl;
    }

    public long getTimestampUsec() {
        return timestampUsec;
    }

    @NonNull
    public Type getType() {
        return type;
    }

    @Nullable
    public String getPaidAmountText() {
        return paidAmountText;
    }

    /**
     * ARGB background colour of a paid message, or 0 when the renderer did
     * not carry one (or this is not a paid message).
     */
    public int getPaidBackgroundColor() {
        return paidBackgroundColor;
    }

    public boolean isSuperSticker() {
        return superSticker;
    }

    public boolean isAuthorOwner() {
        return authorIsOwner;
    }

    public boolean isAuthorModerator() {
        return authorIsModerator;
    }

    public boolean isAuthorVerified() {
        return authorIsVerified;
    }

    public boolean isAuthorMember() {
        return authorIsMember;
    }
}
