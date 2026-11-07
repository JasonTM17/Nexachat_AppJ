package com.nexachat.feature.summary;

import android.content.Context; import android.util.AttributeSet; import android.view.LayoutInflater; import android.widget.FrameLayout; import android.widget.TextView; import androidx.annotation.NonNull; import androidx.annotation.Nullable; import com.nexachat.R; import com.nexachat.databinding.IncludeUnreadBannerBinding;

/** ViewBinding wrapper for the unread-summary banner include. */
public final class UnreadBannerView extends FrameLayout {
    private IncludeUnreadBannerBinding binding;
    public UnreadBannerView(@NonNull Context context,@Nullable AttributeSet attrs){super(context,attrs);}
    @Override protected void onFinishInflate(){super.onFinishInflate(); binding=IncludeUnreadBannerBinding.bind(this);}
    public void bind(int count,@NonNull Runnable onSummarize,@NonNull Runnable onDismiss){if(binding==null)binding=IncludeUnreadBannerBinding.inflate(LayoutInflater.from(getContext()),this,true);binding.unreadBannerText.setText(getResources().getString(R.string.unread_banner_format,count));setOnClickListener(v->onSummarize.run());binding.dismissUnreadBanner.setOnClickListener(v->onDismiss.run());}
}
