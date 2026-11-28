package com.nexachat.feature.feed;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.core.common.Result;
import com.nexachat.core.ui.components.A11yContainerRules;
import com.nexachat.data.ContactsRepository;
import com.nexachat.databinding.FragmentPublicFeedBinding;
import com.nexachat.feature.safety.ReportSheetFragment;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import javax.inject.Inject;
import okhttp3.OkHttpClient;

/**
 * The public feed: newest-first posts, keyword search, author filter, pull-to-refresh,
 * Media3 video playback, reactions, post editing/deletion, and author profile sheets.
 */
@AndroidEntryPoint
public final class PublicFeedFragment extends Fragment implements ReportSheetFragment.Host {

    /** Set by the compose screen so the list picks up the new post. */
    public static final String RESULT_POST_CREATED = "feed_post_created";
    /** Set by the compose screen when a post edit completes. */
    public static final String RESULT_POST_EDITED = "feed_post_edited";

    private static final int MENU_EDIT = 1;
    private static final int MENU_DELETE = 2;
    private static final int MENU_REPORT = 3;
    private static final int MENU_MESSAGE = 4;
    private static final int MENU_COPY = 5;
    private static final long SEARCH_DEBOUNCE_MS = 300L;

    private FragmentPublicFeedBinding binding;
    private FeedViewModel viewModel;
    private PostAdapter adapter;
    @Nullable private FeedVideoController videoController;
    private boolean loadMoreErrorShown;
    private final CompositeDisposable chatOpens = new CompositeDisposable();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    @Nullable private Runnable pendingSearch;
    private boolean syncingSearchInput;
    private boolean openingChat;
    private boolean scrollToTopOnRefresh;
    @Inject FeedNoticeStore noticeStore;
    @Inject ContactsRepository contactsRepository;
    @Inject OkHttpClient okHttpClient;

    public PublicFeedFragment() {
        super(R.layout.fragment_public_feed);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentPublicFeedBinding.bind(view);
        viewModel = new ViewModelProvider(this).get(FeedViewModel.class);
        videoController = new FeedVideoController(requireContext(), okHttpClient);

        binding.feedToolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        binding.feedCompose.setOnClickListener(ignored ->
                NavHostFragment.findNavController(this).navigate(R.id.postComposeFragment));

        setupSearchAndFilter();

        adapter = new PostAdapter(new PostAdapter.Listener() {
            @Override public void onOpenPost(@NonNull FeedRepository.Post post) {
                openPost(post);
            }

            @Override public void onReact(@NonNull FeedRepository.Post post) {
                FeedReactionPicker.show(requireContext(),
                        emoji -> viewModel.toggleReaction(post.id(), emoji));
            }

            @Override public void onPostMenu(@NonNull FeedRepository.Post post, @NonNull View anchor) {
                showPostMenu(post, anchor);
            }

            @Override public void onReactionTap(
                    @NonNull FeedRepository.Post post, @NonNull String emoji) {
                viewModel.toggleReaction(post.id(), emoji);
            }

            @Override public void onOpenAuthor(
                    @NonNull FeedRepository.Author author, boolean isMine) {
                openAuthorSheet(author, isMine);
            }
        }, videoController);
        binding.feedList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.feedList.setAdapter(adapter);
        A11yContainerRules.suppressFocusTarget(binding.feedList);
        binding.feedList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager layout =
                        (LinearLayoutManager) recyclerView.getLayoutManager();
                if (layout != null && layout.findLastVisibleItemPosition()
                        >= adapter.getItemCount() - 3) {
                    viewModel.loadMore();
                }
            }
        });
        binding.feedRefresh.setOnRefreshListener(viewModel::refresh);
        binding.feedEmpty.setIcon(R.drawable.ic_public);
        binding.feedError.setMessage(getString(R.string.feed_error));
        binding.feedError.setOnRetry(viewModel::retry);

        getParentFragmentManager().setFragmentResultListener(
                RESULT_POST_CREATED, getViewLifecycleOwner(),
                (key, result) -> {
                    scrollToTopOnRefresh = true;
                    viewModel.refresh();
                });
        getParentFragmentManager().setFragmentResultListener(
                RESULT_POST_EDITED, getViewLifecycleOwner(),
                (key, result) -> snackbar(R.string.feed_post_edited));
        getParentFragmentManager().setFragmentResultListener(
                AuthorProfileSheetFragment.RESULT_FILTER_AUTHOR, getViewLifecycleOwner(),
                (key, result) -> applyAuthorFilterResult(result));
        getChildFragmentManager().setFragmentResultListener(
                AuthorProfileSheetFragment.RESULT_FILTER_AUTHOR, getViewLifecycleOwner(),
                (key, result) -> applyAuthorFilterResult(result));

        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getOutcome().observe(getViewLifecycleOwner(), this::handleOutcome);
        showNoticeOnce();
    }

    private void setupSearchAndFilter() {
        binding.feedSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable editable) {
                if (syncingSearchInput || binding == null) {
                    return;
                }
                String text = editable == null ? "" : editable.toString();
                binding.feedSearchClear.setVisibility(text.isBlank() ? View.GONE : View.VISIBLE);
                cancelPendingSearch();
                pendingSearch = () -> viewModel.setSearchQuery(text);
                searchHandler.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
            }
        });
        binding.feedSearchInput.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                cancelPendingSearch();
                CharSequence current = binding.feedSearchInput.getText();
                viewModel.setSearchQuery(current == null ? "" : current.toString());
                return true;
            }
            return false;
        });
        binding.feedSearchClear.setOnClickListener(ignored -> {
            cancelPendingSearch();
            binding.feedSearchInput.setText("");
            viewModel.setSearchQuery("");
        });
        binding.feedAuthorFilterChip.setOnCloseIconClickListener(
                ignored -> viewModel.setAuthorFilter(null, null));
        binding.feedAuthorFilterChip.setOnClickListener(
                ignored -> viewModel.setAuthorFilter(null, null));
    }

    private void cancelPendingSearch() {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
    }

    private void applyAuthorFilterResult(@NonNull Bundle result) {
        String authorId = result.getString(AuthorProfileSheetFragment.EXTRA_AUTHOR_ID);
        String authorName = result.getString(AuthorProfileSheetFragment.EXTRA_AUTHOR_NAME);
        if (authorId != null && !authorId.isBlank()) {
            viewModel.setAuthorFilter(authorId, authorName);
        }
    }

    private void render(@NonNull FeedViewModel.UiState state) {
        if (binding == null) {
            return;
        }
        boolean shouldScrollTop = scrollToTopOnRefresh
                && !state.isLoading()
                && !state.isRefreshing()
                && !state.getItems().isEmpty();
        if (shouldScrollTop) {
            scrollToTopOnRefresh = false;
        }
        adapter.submitList(state.getItems(), () -> {
            if (binding == null || !shouldScrollTop) {
                return;
            }
            if (binding.feedList.getLayoutManager() instanceof LinearLayoutManager layout) {
                layout.scrollToPositionWithOffset(0, 0);
            } else {
                binding.feedList.scrollToPosition(0);
            }
            binding.feedAppBar.setExpanded(true, false);
        });
        boolean empty = state.getItems().isEmpty();
        binding.feedList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.feedLoading.setVisibility(state.isLoading() && empty ? View.VISIBLE : View.GONE);
        if (state.hasActiveFilter()) {
            binding.feedEmpty.setTitle(getString(R.string.feed_empty_filtered));
            binding.feedEmpty.setMessage(getString(R.string.feed_empty_filtered_hint));
        } else {
            binding.feedEmpty.setTitle(getString(R.string.feed_empty));
            binding.feedEmpty.setMessage(getString(R.string.feed_empty_hint));
        }
        binding.feedEmpty.setVisibility(
                !state.isLoading() && !state.isError() && empty ? View.VISIBLE : View.GONE);
        binding.feedError.setVisibility(state.isError() ? View.VISIBLE : View.GONE);
        // The existing swipe indicator is the screen's progress view: it also tracks
        // a background page load, not only the pull-to-refresh gesture.
        binding.feedRefresh.setRefreshing(state.isRefreshing() || state.isLoadingMore());

        String authorId = state.getAuthorId();
        boolean hasAuthorFilter = authorId != null && !authorId.isBlank();
        binding.feedAuthorFilterRow.setVisibility(hasAuthorFilter ? View.VISIBLE : View.GONE);
        if (hasAuthorFilter) {
            String labelName = state.getAuthorName() == null || state.getAuthorName().isBlank()
                    ? getString(R.string.feed_title)
                    : state.getAuthorName();
            binding.feedAuthorFilterChip.setText(
                    getString(R.string.feed_filter_author_chip, labelName));
        }
        binding.feedSearchClear.setVisibility(
                binding.feedSearchInput.getText() == null
                        || binding.feedSearchInput.getText().toString().isBlank()
                        ? View.GONE : View.VISIBLE);

        renderLoadMoreFeedback(state);
    }

    private void handleOutcome(@Nullable FeedViewModel.Outcome outcome) {
        if (outcome == null || binding == null) {
            return;
        }
        viewModel.onOutcomeShown();
        switch (outcome) {
            case POST_DELETED -> snackbar(R.string.feed_post_deleted);
            case POST_DELETE_FAILED -> snackbar(R.string.feed_post_delete_failed);
        }
    }

    /** One retry banner per failed page: auto-loading already stopped in the model. */
    private void renderLoadMoreFeedback(@NonNull FeedViewModel.UiState state) {
        if (state.isLoadMoreError()) {
            if (!loadMoreErrorShown) {
                loadMoreErrorShown = true;
                snackbar(R.string.feed_error, R.string.action_retry, viewModel::retryLoadMore);
            }
            return;
        }
        loadMoreErrorShown = false;
    }

    private void showNoticeOnce() {
        FeedNoticeDialogFragment.showIfNeeded(getChildFragmentManager(), noticeStore);
    }

    private void openPost(@NonNull FeedRepository.Post post) {
        Bundle args = new Bundle();
        args.putString(PostDetailFragment.ARG_POST_ID, post.id());
        NavHostFragment.findNavController(this)
                .navigate(R.id.postDetailFragment, args);
    }

    private void openAuthorSheet(@NonNull FeedRepository.Author author, boolean isMine) {
        String authorId = author.id();
        if (authorId == null || authorId.isBlank()) {
            return;
        }
        AuthorProfileSheetFragment.newInstance(
                        authorId, author.displayName(), author.avatarUrl(), isMine)
                .show(getChildFragmentManager(), AuthorProfileSheetFragment.TAG);
    }

    private void showPostMenu(@NonNull FeedRepository.Post post, @NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        int order = 0;
        if (post.mine()) {
            menu.getMenu().add(Menu.NONE, MENU_EDIT, order++, R.string.action_edit);
            menu.getMenu().add(Menu.NONE, MENU_DELETE, order++, R.string.feed_delete_post);
        } else {
            menu.getMenu().add(Menu.NONE, MENU_REPORT, order++, R.string.feed_report);
            menu.getMenu().add(Menu.NONE, MENU_MESSAGE, order++, R.string.feed_message_author);
        }
        if (post.body() != null && !post.body().isBlank()) {
            menu.getMenu().add(Menu.NONE, MENU_COPY, order, R.string.action_copy);
        }
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == MENU_EDIT) {
                openEditPost(post);
                return true;
            }
            if (id == MENU_DELETE) {
                confirmDeletePost(post);
                return true;
            }
            if (id == MENU_REPORT) {
                ReportSheetFragment.forPost(post.id(), post.body())
                        .show(getChildFragmentManager(), ReportSheetFragment.TAG);
                return true;
            }
            if (id == MENU_MESSAGE) {
                openAuthorChat(post.author().id());
                return true;
            }
            if (id == MENU_COPY && post.body() != null) {
                copyText(post.body());
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void openEditPost(@NonNull FeedRepository.Post post) {
        Bundle args = new Bundle();
        args.putString(PostComposeFragment.ARG_EDIT_POST_ID, post.id());
        args.putString(PostComposeFragment.ARG_EDIT_BODY, post.body());
        NavHostFragment.findNavController(this).navigate(R.id.postComposeFragment, args);
    }

    private void copyText(@NonNull String text) {
        ClipboardManager clipboard =
                ContextCompat.getSystemService(requireContext(), ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("feed_post", text));
            snackbar(R.string.copied_to_clipboard);
        }
    }

    private void openAuthorChat(@Nullable String authorId) {
        if (openingChat || binding == null || authorId == null) {
            return;
        }
        openingChat = true;
        chatOpens.add(contactsRepository.openDirectConversation(authorId, null)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    openingChat = false;
                    if (binding == null) {
                        return;
                    }
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof String conversationId) {
                        var nav = NavHostFragment.findNavController(this);
                        if (nav.getCurrentDestination() == null
                                || nav.getCurrentDestination().getId() != R.id.publicFeedFragment) {
                            return;
                        }
                        Bundle args = new Bundle();
                        args.putString("conversationId", conversationId);
                        nav.navigate(R.id.chatFragment, args);
                    } else {
                        snackbar(R.string.feed_open_chat_error);
                    }
                }, error -> {
                    openingChat = false;
                    snackbar(R.string.feed_open_chat_error);
                }));
    }

    private void confirmDeletePost(@NonNull FeedRepository.Post post) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.feed_delete_post_title)
                .setMessage(R.string.feed_delete_body)
                .setNegativeButton(R.string.feed_cancel, null)
                .setPositiveButton(R.string.feed_delete_confirm,
                        (dialog, which) -> viewModel.deletePost(post.id()))
                .show();
    }

    @Override
    public void onReportSent() {
        snackbar(R.string.feed_report_sent);
    }

    private void snackbar(int message) {
        if (binding == null) {
            return;
        }
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT)
                .setAnchorView(binding.feedCompose)
                .show();
    }

    private void snackbar(int message, int actionLabel, @NonNull Runnable action) {
        if (binding == null) {
            return;
        }
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.feedCompose)
                .setAction(actionLabel, ignored -> action.run())
                .show();
    }

    @Override
    public void onPause() {
        if (videoController != null) {
            videoController.pause();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        cancelPendingSearch();
        chatOpens.clear();
        openingChat = false;
        if (videoController != null) {
            videoController.release();
            videoController = null;
        }
        binding = null;
        super.onDestroyView();
    }
}
