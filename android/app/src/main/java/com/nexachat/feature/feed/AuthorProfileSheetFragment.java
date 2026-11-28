package com.nexachat.feature.feed;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.navigation.fragment.NavHostFragment;
import com.nexachat.R;
import com.nexachat.call.CallLauncher;
import com.nexachat.call.core.CallMediaType;
import com.nexachat.core.common.Result;
import com.nexachat.data.ContactsRepository;
import com.nexachat.databinding.SheetAuthorProfileBinding;
import com.nexachat.feature.ui.NexaBottomSheetFragment;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import javax.inject.Inject;

/**
 * Bottom sheet showing a feed author's public profile, post count,
 * filter-by-author shortcut, and direct Chat / Voice / Video call actions.
 */
@AndroidEntryPoint
public final class AuthorProfileSheetFragment extends NexaBottomSheetFragment {

    public static final String TAG = "author_profile_sheet";
    public static final String RESULT_FILTER_AUTHOR = "feed_filter_author";
    public static final String EXTRA_AUTHOR_ID = "authorId";
    public static final String EXTRA_AUTHOR_NAME = "authorName";

    private static final String ARG_AUTHOR_ID = "authorId";
    private static final String ARG_DISPLAY_NAME = "displayName";
    private static final String ARG_AVATAR_URL = "avatarUrl";
    private static final String ARG_IS_MINE = "isMine";

    @Inject FeedRepository feedRepository;
    @Inject ContactsRepository contactsRepository;

    private final CompositeDisposable disposables = new CompositeDisposable();
    private SheetAuthorProfileBinding binding;
    private String authorId = "";
    @Nullable private String resolvedName;
    private boolean actionInFlight;

    @NonNull
    public static AuthorProfileSheetFragment newInstance(
            @NonNull String authorId,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            boolean isMine) {
        AuthorProfileSheetFragment sheet = new AuthorProfileSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_AUTHOR_ID, authorId);
        args.putString(ARG_DISPLAY_NAME, displayName);
        args.putString(ARG_AVATAR_URL, avatarUrl);
        args.putBoolean(ARG_IS_MINE, isMine);
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SheetAuthorProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        applySheetInsets(view);
        ViewCompat.setAccessibilityHeading(binding.authorProfileName, true);

        Bundle args = getArguments();
        authorId = args == null ? "" : args.getString(ARG_AUTHOR_ID, "");
        resolvedName = args == null ? null : args.getString(ARG_DISPLAY_NAME);
        String initialAvatar = args == null ? null : args.getString(ARG_AVATAR_URL);
        boolean isMine = args != null && args.getBoolean(ARG_IS_MINE, false);

        renderHeader(resolvedName, initialAvatar);
        binding.authorProfileMessage.setVisibility(isMine ? View.GONE : View.VISIBLE);
        binding.authorProfileCallRow.setVisibility(isMine ? View.GONE : View.VISIBLE);

        binding.authorProfileFilter.setOnClickListener(ignored -> {
            Bundle result = new Bundle();
            result.putString(EXTRA_AUTHOR_ID, authorId);
            result.putString(EXTRA_AUTHOR_NAME, resolvedName);
            getParentFragmentManager().setFragmentResult(RESULT_FILTER_AUTHOR, result);
            dismissAllowingStateLoss();
        });
        binding.authorProfileMessage.setOnClickListener(ignored -> openChat());
        binding.authorProfileVoiceCall.setOnClickListener(ignored -> startCall(CallMediaType.AUDIO));
        binding.authorProfileVideoCall.setOnClickListener(ignored -> startCall(CallMediaType.VIDEO));

        loadProfile();
    }

    private void loadProfile() {
        if (authorId.isBlank()) {
            showError(R.string.feed_author_unavailable);
            return;
        }
        binding.authorProfileProgress.setVisibility(View.VISIBLE);
        binding.authorProfileError.setVisibility(View.GONE);
        disposables.add(feedRepository.getAuthorProfile(authorId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(profile -> {
                    if (binding == null) {
                        return;
                    }
                    binding.authorProfileProgress.setVisibility(View.GONE);
                    resolvedName = profile.displayName();
                    renderHeader(profile.displayName(), profile.avatarUrl());
                    int count = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, profile.postCount()));
                    binding.authorProfilePostCount.setText(getResources().getQuantityString(
                            R.plurals.feed_author_post_count, count, count));
                    binding.authorProfilePostCount.setVisibility(View.VISIBLE);
                }, error -> {
                    if (binding == null) {
                        return;
                    }
                    binding.authorProfileProgress.setVisibility(View.GONE);
                    showError(R.string.feed_author_unavailable);
                }));
    }

    private void renderHeader(@Nullable String name, @Nullable String avatarUrl) {
        String safeName = name == null ? "" : name;
        binding.authorProfileName.setText(safeName);
        binding.authorProfileAvatar.setName(safeName);
        binding.authorProfileAvatar.setAvatarUrl(avatarUrl);
        binding.authorProfileAvatar.setOnlineStatus(null);
    }

    private void openChat() {
        openConversationAndRun(conversationId -> {
            if (!isAdded() || getParentFragmentManager().isStateSaved()) {
                return;
            }
            var nav = NavHostFragment.findNavController(this);
            var destination = nav.getCurrentDestination();
            if (destination == null
                    || (destination.getId() != R.id.publicFeedFragment
                            && destination.getId() != R.id.postDetailFragment)) {
                return;
            }
            Bundle args = new Bundle();
            args.putString("conversationId", conversationId);
            dismissAllowingStateLoss();
            nav.navigate(R.id.chatFragment, args);
        });
    }

    private void startCall(@NonNull CallMediaType mediaType) {
        openConversationAndRun(conversationId -> {
            if (!isAdded() || getParentFragmentManager().isStateSaved()) {
                return;
            }
            Context context = getContext();
            if (context == null) {
                return;
            }
            dismissAllowingStateLoss();
            CallLauncher.start(
                    context,
                    conversationId,
                    authorId,
                    resolvedName == null ? "" : resolvedName,
                    null,
                    mediaType);
        });
    }

    private void openConversationAndRun(@NonNull java.util.function.Consumer<String> onReady) {
        if (actionInFlight || binding == null || authorId.isBlank()) {
            return;
        }
        actionInFlight = true;
        setActionsEnabled(false);
        binding.authorProfileProgress.setVisibility(View.VISIBLE);
        binding.authorProfileError.setVisibility(View.GONE);
        disposables.add(contactsRepository.openDirectConversation(authorId, null)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    actionInFlight = false;
                    if (binding == null) {
                        return;
                    }
                    binding.authorProfileProgress.setVisibility(View.GONE);
                    setActionsEnabled(true);
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof String conversationId) {
                        onReady.accept(conversationId);
                    } else {
                        showError(R.string.feed_open_chat_error);
                    }
                }, error -> {
                    actionInFlight = false;
                    if (binding == null) {
                        return;
                    }
                    binding.authorProfileProgress.setVisibility(View.GONE);
                    setActionsEnabled(true);
                    showError(R.string.feed_open_chat_error);
                }));
    }

    private void setActionsEnabled(boolean enabled) {
        binding.authorProfileFilter.setEnabled(enabled);
        binding.authorProfileMessage.setEnabled(enabled);
        binding.authorProfileVoiceCall.setEnabled(enabled);
        binding.authorProfileVideoCall.setEnabled(enabled);
    }

    private void showError(int messageRes) {
        binding.authorProfileError.setText(messageRes);
        binding.authorProfileError.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        actionInFlight = false;
        binding = null;
        super.onDestroyView();
    }
}
