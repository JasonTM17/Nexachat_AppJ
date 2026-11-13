package com.nexachat.feature.archive;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.widget.ImageView;
import androidx.appcompat.content.res.AppCompatResources;
import com.google.android.material.color.MaterialColors;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.nexachat.R;
import com.nexachat.core.common.AppConfig;
import com.nexachat.core.media.AttachmentBlurHashRegistry;
import com.nexachat.core.media.BlurHashDecoder;
import com.nexachat.core.media.BlurHashPlaceholderCache;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.databinding.ItemArchiveMediaBinding;
import java.util.Objects;

/** Two-column media grid using the same authenticated Glide content path as chat. */
public final class ArchiveMediaAdapter
        extends ListAdapter<ArchivePage.Item, ArchiveMediaAdapter.Holder> {

    private static final int BLUR_HASH_EDGE = 24;
    private static final BlurHashPlaceholderCache<Bitmap> PLACEHOLDERS =
            new BlurHashPlaceholderCache<>();

    private final Listener listener;
    @Nullable private ArchiveUiState state;

    public ArchiveMediaAdapter(@NonNull Listener listener) {
        super(DIFF);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public void setState(@NonNull ArchiveUiState nextState) {
        state = nextState;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemArchiveMediaBinding binding = ItemArchiveMediaBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ArchivePage.Item item = getItem(position);
        ArchiveUiState currentState = state;
        if (currentState == null) {
            return;
        }
        ItemArchiveMediaBinding binding = holder.binding;
        ArchivePage.Attachment attachment = item.getAttachment();
        String kind = item.getKind();
        boolean video = "VIDEO".equals(kind);
        String title = attachment == null || isBlank(attachment.getFileName())
                ? video
                        ? binding.getRoot().getContext().getString(R.string.archive_kind_video)
                        : binding.getRoot().getContext().getString(R.string.archive_tab_media)
                : attachment.getFileName();
        binding.archiveMediaTitle.setText(title);
        binding.archiveMediaDetails.setText(
                ArchiveText.details(binding.getRoot().getContext(), currentState, item));
        binding.archiveMediaKind.setText(R.string.archive_kind_video);
        binding.archiveMediaKind.setVisibility(video ? View.VISIBLE : View.GONE);
        binding.getRoot().setContentDescription(ArchiveText.accessibilityDescription(
                binding.getRoot().getContext(), currentState, title, item));
        binding.archiveMediaOptions.setContentDescription(
                binding.getRoot().getContext().getString(R.string.archive_item_options_for, title));
        bindThumbnail(binding, attachment);
        binding.getRoot().setOnClickListener(view -> listener.onOpen(item));
        binding.archiveMediaOptions.setOnClickListener(view -> listener.onOptions(item, view));
    }

    private static void bindThumbnail(
            @NonNull ItemArchiveMediaBinding binding,
            @Nullable ArchivePage.Attachment attachment) {
        if (attachment == null) {
            Glide.with(binding.archiveThumbnail).clear(binding.archiveThumbnail);
            showTintedImagePlaceholder(binding.archiveThumbnail);
            return;
        }
        AttachmentBlurHashRegistry.remember(attachment.getId(), attachment.getBlurHash());
        Drawable placeholder = placeholder(binding.archiveThumbnail, attachment.getBlurHash());
        AttachmentDto dto = new AttachmentDto(
                attachment.getId(),
                null,
                attachment.getFileName(),
                attachment.getMimeType(),
                attachment.getSizeBytes(),
                attachment.getWidth(),
                attachment.getHeight(),
                attachment.getDurationMs(),
                null,
                attachment.getBlurHash());
        String url = dto.contentUrl();
        if (url == null) {
            Glide.with(binding.archiveThumbnail).clear(binding.archiveThumbnail);
            binding.archiveThumbnail.setImageDrawable(placeholder);
            return;
        }
        String absoluteUrl = AppConfig.absoluteMediaUrl(url);
        Glide.with(binding.archiveThumbnail)
                .load(absoluteUrl)
                .centerCrop()
                .placeholder(placeholder)
                .error(tintedImagePlaceholder(binding.archiveThumbnail))
                .into(binding.archiveThumbnail);
    }

    private static void showTintedImagePlaceholder(@NonNull ImageView target) {
        target.setImageDrawable(tintedImagePlaceholder(target));
    }

    @NonNull
    private static Drawable tintedImagePlaceholder(@NonNull ImageView target) {
        Drawable drawable = AppCompatResources.getDrawable(target.getContext(), R.drawable.ic_image);
        Objects.requireNonNull(drawable, "ic_image");
        drawable = drawable.mutate();
        int tint = MaterialColors.getColor(
                target, com.google.android.material.R.attr.colorOnSurfaceVariant);
        drawable.setTint(tint);
        return drawable;
    }

    @Nullable
    private static Drawable placeholder(
            @NonNull android.widget.ImageView image,
            @Nullable String hash) {
        Bitmap bitmap = PLACEHOLDERS.getOrCreate(hash, value -> {
            try {
                int[] pixels = BlurHashDecoder.decode(value, BLUR_HASH_EDGE, BLUR_HASH_EDGE);
                return Bitmap.createBitmap(
                        pixels, BLUR_HASH_EDGE, BLUR_HASH_EDGE, Bitmap.Config.ARGB_8888);
            } catch (RuntimeException | OutOfMemoryError ignored) {
                return null;
            }
        });
        return bitmap == null ? null : new BitmapDrawable(image.getResources(), bitmap);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static final DiffUtil.ItemCallback<ArchivePage.Item> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull ArchivePage.Item oldItem,
                        @NonNull ArchivePage.Item newItem) {
                    return oldItem.stableKey().equals(newItem.stableKey());
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull ArchivePage.Item oldItem,
                        @NonNull ArchivePage.Item newItem) {
                    return oldItem.equals(newItem);
                }
            };

    public interface Listener {
        void onOpen(@NonNull ArchivePage.Item item);

        void onOptions(@NonNull ArchivePage.Item item, @NonNull View anchor);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        private final ItemArchiveMediaBinding binding;

        Holder(ItemArchiveMediaBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
