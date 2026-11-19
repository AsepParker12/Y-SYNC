package org.schabi.newpipe.livechat;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.ListLiveChatItemBinding;
import org.schabi.newpipe.util.image.CoilHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter for the live chat message list. Owns the messages and the set of
 * already-seen message ids, and enforces the high-volume caps from the
 * design: at most {@value #MAX_ITEMS} rows are kept (oldest dropped from
 * the top in the same update), a single poll batch contributes at most
 * {@value #MAX_BATCH_ITEMS} newest items, and every change is delivered
 * with one range notification per poll — never one per message.
 *
 * <p>All methods must be called on the main thread.</p>
 */
public final class LiveChatAdapter extends RecyclerView.Adapter<LiveChatAdapter.ChatViewHolder> {
    static final int MAX_ITEMS = 200;
    static final int MAX_BATCH_ITEMS = 100;

    private final List<LiveChatItem> items = new ArrayList<>();
    private final Set<String> seenIds = new LinkedHashSet<>();

    /**
     * Appends the fresh items of one poll batch (deduplicated by message id
     * against everything already shown) and trims the list back to the cap.
     *
     * @return how many items were actually added
     */
    public int addItems(@NonNull final List<LiveChatItem> newItems) {
        final List<LiveChatItem> fresh = new ArrayList<>(newItems.size());
        for (final LiveChatItem item : newItems) {
            if (seenIds.add(item.getId())) {
                fresh.add(item);
            }
        }
        if (fresh.isEmpty()) {
            return 0;
        }
        final List<LiveChatItem> toAdd = fresh.size() > MAX_BATCH_ITEMS
                ? fresh.subList(fresh.size() - MAX_BATCH_ITEMS, fresh.size())
                : fresh;
        if (toAdd.size() < fresh.size()) {
            // The skipped middle of a firehose batch must not count as seen
            // forever; forget the ids we deliberately did not show.
            for (int i = 0; i < fresh.size() - toAdd.size(); i++) {
                seenIds.remove(fresh.get(i).getId());
            }
        }

        final int excess = items.size() + toAdd.size() - MAX_ITEMS;
        if (excess > 0) {
            for (int i = 0; i < excess; i++) {
                seenIds.remove(items.get(i).getId());
            }
            items.subList(0, excess).clear();
            notifyItemRangeRemoved(0, excess);
        }
        final int insertStart = items.size();
        items.addAll(toAdd);
        notifyItemRangeInserted(insertStart, toAdd.size());
        return toAdd.size();
    }

    /**
     * Replaces the text of an already-shown message (moderator deletion).
     *
     * @return whether the message was found and updated
     */
    public boolean markDeleted(@NonNull final String itemId, @NonNull final String text) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(itemId)) {
                items.set(i, items.get(i).withMessageText(text));
                notifyItemChanged(i);
                return true;
            }
        }
        return false;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                             final int viewType) {
        final ListLiveChatItemBinding binding = ListLiveChatItemBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ChatViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull final ChatViewHolder holder, final int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    final class ChatViewHolder extends RecyclerView.ViewHolder {
        private final ListLiveChatItemBinding binding;

        ChatViewHolder(@NonNull final ListLiveChatItemBinding itemBinding) {
            super(itemBinding.getRoot());
            this.binding = itemBinding;
        }

        void bind(@NonNull final LiveChatItem item) {
            final Context context = itemView.getContext();

            if (item.getType() == LiveChatItem.Type.SYSTEM) {
                binding.liveChatAvatar.setVisibility(View.GONE);
            } else {
                binding.liveChatAvatar.setVisibility(View.VISIBLE);
                CoilHelper.INSTANCE.loadAvatar(binding.liveChatAvatar,
                        item.getAuthorPhotoUrl());
            }

            binding.liveChatAuthor.setText(item.getAuthorName());
            binding.liveChatAuthor.setTextColor(authorColor(context, item));

            if (item.getType() == LiveChatItem.Type.PAID
                    && item.getPaidAmountText() != null) {
                binding.liveChatAmount.setVisibility(View.VISIBLE);
                binding.liveChatAmount.setText(item.getPaidAmountText());
            } else {
                binding.liveChatAmount.setVisibility(View.GONE);
            }

            if (item.getTimestampUsec() > 0) {
                binding.liveChatTime.setVisibility(View.VISIBLE);
                // Formatted once at parse time (see LiveChatItem#getTimeText).
                binding.liveChatTime.setText(item.getTimeText());
            } else {
                binding.liveChatTime.setVisibility(View.GONE);
            }

            if (item.isSuperSticker()) {
                binding.liveChatMessage.setText(context.getString(
                        R.string.live_chat_super_sticker_message,
                        item.getPaidAmountText() == null ? "" : item.getPaidAmountText()));
            } else {
                binding.liveChatMessage.setText(item.getMessageText());
            }

            final int background = item.getType() == LiveChatItem.Type.PAID
                    ? item.getPaidBackgroundColor() : 0;
            binding.getRoot().setBackgroundColor(background == 0
                    ? Color.TRANSPARENT : background);
        }

        private int authorColor(@NonNull final Context context,
                                @NonNull final LiveChatItem item) {
            if (item.isAuthorOwner()) {
                return ContextCompat.getColor(context, R.color.live_chat_author_owner);
            }
            if (item.isAuthorModerator()) {
                return ContextCompat.getColor(context, R.color.live_chat_author_moderator);
            }
            if (item.isAuthorMember()) {
                return ContextCompat.getColor(context, R.color.live_chat_author_member);
            }
            return ContextCompat.getColor(context, R.color.live_chat_author_default);
        }
    }
}
