package com.nexachat.core.ui.host;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * B1 shell fragment that only inflates its XML layout; B3 will replace with wired feature fragments.
 */
public abstract class LayoutHostFragment extends Fragment {

    @LayoutRes
    private final int layoutRes;

    protected LayoutHostFragment(@LayoutRes int layoutRes) {
        this.layoutRes = layoutRes;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(layoutRes, container, false);
    }
}
