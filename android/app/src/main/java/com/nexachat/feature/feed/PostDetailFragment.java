package com.nexachat.feature.feed;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.core.common.Result;
import com.nexachat.core.ui.components.A11yContainerRules;
import com.nexachat.data.ContactsRepository;
import com.nexachat.databinding.FragmentPostDetailBinding;
import com.nexachat.feature.safety.ReportSheetFragment;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import okhttp3.OkHttpClient;

/**
 * One post with its flat comments, Chat-style pill composer, inline comment editing,
 * Media3 video playback, author profile sheets, and live sync back to PublicFeedFragment.
 */
@AndroidEntryPoint
public final class PostDetailFragment extends Fragment implements ReportSheetFragment.Host {

    public static final String ARG_POST_ID = "postId";

    private static final int MENU_EDIT = 1;
    private static final int MENU_DELETE = 2;
    private static final int MENU_REPORT = 3;
    private static final int MENU_MESSAGE = 4;
    private static final int MENU_COPY = 5;

    private FragmentPostDetailBinding binding;
    private PostDetailViewModel viewModel;
    private PostAdapter postAdapter;
    private CommentAdapter commentAdapter;
    private CommentsEmptyAdapter commentsEmptyAdapter;
    @Nullable private FeedVideoController videoController;
    @Nullable private String lastEditingCommentId;
    private boolean commentsErrorShown;
    private final CompositeDisposable chatOpens = new CompositeDisposable();
    private boolean openingChat;

    @Inject ContactsRepository contactsRepository;
    @Inject OkHttpClient okHttpClient;

    public PostDetailFragment() {
        super(R.layout.fragment_post_detail);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentPostDetailBinding.bind(view);
        viewModel = new ViewModelProvider(this).get(PostDetailViewModel.class);
        videoController = new FeedVideoController(requireContext(), okHttpClient);

        binding.detailToolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        postAdapter = new PostAdapter(new PostAdapter.Listener() {
            @Override public void onOpenPost(@NonNull FeedRepository.Post post) { }

            @Override public void onReact(@NonNull FeedRepository.Post post) {
                FeedReactionPicker.show(requireContext(), viewModel::toggleReaction);
            }

            @Override public void onPostMenu(@NonNull FeedRepository.Post post, @NonNull View anchor) {
                showPostMenu(post, anchor);
            }

            @Override public void onReactionTap(
                    @NonNull FeedRepository.Post post, @NonNull String emoji) {
                viewModel.toggleReaction(emoji);
            }

            @Override public void onOpenAuthor(
                    @NonNull FeedRepository.Author author, boolean isMine) {
                openAuthorSheet(author, isMine);
            }
        }, videoController);
        commentAdapter = new CommentAdapter(new CommentAdapter.Listener() {
            @Override
            public void onCommentMenu(
                    @NonNull FeedRepository.Comment comment, @NonNull View anchor) {
                showCommentMenu(comment, anchor);
            }

            @Override
            public void onOpenAuthor(@NonNull FeedRepository.Author author, boolean isMine) {
                openAuthorSheet(author, isMine);
            }
        });
        commentsEmptyAdapter = new CommentsEmptyAdapter(() -> {
            if (binding != null) {
                binding.detailInput.requestFocus();
            }
        });
        binding.detailList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.detailList.setAdapter(
                new ConcatAdapter(postAdapter, commentAdapter, commentsEmptyAdapter));
        A11yContainerRules.suppressFocusTarget(binding.detailList);
        binding.detailList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager layout =
                        (LinearLayoutManager) recyclerView.getLayoutManager();
                if (layout != null && layout.findLastVisibleItemPosition()
                        >= binding.detailList.getAdapter().getItemCount() - 3) {
                    viewModel.loadMoreComments();
                }
            }
        });
        binding.detailSend.setOnClickListener(ignored -> sendComment());
        binding.detailCancelEdit.setOnClickListener(ignored -> {
            viewModel.cancelEditComment();
            binding.detailInput.setText("");
        });
        binding.detailError.setMessage(getString(R.string.feed_error));
        binding.detailError.setOnRetry(() -> viewModel.load(requirePostId()));

        getParentFragmentManager().setFragmentResultListener(
                PublicFeedFragment.RESULT_POST_EDITED, getViewLifecycleOwner(),
                (key, result) -> snackbar(R.string.feed_post_edited));
        getChildFragmentManager().setFragmentResultListener(
                AuthorProfileSheetFragment.RESULT_FILTER_AUTHOR, getViewLifecycleOwner(),
                (key, result) -> {
                    getParentFragmentManager().setFragmentResult(
                            AuthorProfileSheetFragment.RESULT_FILTER_AUTHOR, result);
                    NavHostFragment.findNavController(this).navigateUp();
                });

        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getOutcome().observe(getViewLifecycleOwner(), this::handleOutcome);
        viewModel.load(requirePostId());
    }

    private void render(@NonNull PostDetailViewModel.UiState state) {
        if (binding == null) {
            return;
        }
        FeedRepository.Post post = state.getPost();
        postAdapter.submitList(post == null ? List.of() : List.of(post));
        commentAdapter.submitList(state.getComments());
        binding.detailLoading.setVisibility(
                state.isLoading() && post == null ? View.VISIBLE : View.GONE);
        binding.detailError.setVisibility(state.isError() && post == null ? View.VISIBLE : View.GONE);
        commentsEmptyAdapter.setShown(emptyCommentsCtaVisible(state));
        binding.detailList.setVisibility(post == null ? View.GONE : View.VISIBLE);
        binding.detailComposer.setVisibility(post == null ? View.GONE : View.VISIBLE);
        binding.detailSend.setEnabled(!state.isSending());

        FeedRepository.Comment editing = state.getEditingComment();
        if (editing == null) {
            binding.detailEditBanner.setVisibility(View.GONE);
            if (lastEditingCommentId != null) {
                lastEditingCommentId = null;
            }
        } else {
            binding.detailEditBanner.setVisibility(View.VISIBLE);
            binding.detailEditPreview.setText(editing.body() == null ? "" : editing.body());
            if (!Objects.equals(lastEditingCommentId, editing.id())) {
                lastEditingCommentId = editing.id();
                String text = editing.body() == null ? "" : editing.body();
                binding.detailInput.setText(text);
                binding.detailInput.setSelection(text.length());
                binding.detailInput.requestFocus();
            }
        }

        renderCommentsFeedback(state, post);
    }

    /**
     * The composer CTA only counts as visible once the comment list is known: the
     * post on screen, nothing still loading, and no failed comment page.
     */
    private boolean emptyCommentsCtaVisible(@NonNull PostDetailViewModel.UiState state) {
        return state.getPost() != null
                && !state.isLoading()
                && !state.isLoadingMore()
                && !state.isCommentsError()
                && state.getComments().isEmpty();
    }

    /** One retry banner per failed comment page, shown only under a loaded post. */
    private void renderCommentsFeedback(
            @NonNull PostDetailViewModel.UiState state, @Nullable FeedRepository.Post post) {
        if (state.isCommentsError() && post != null) {
            if (!commentsErrorShown) {
                commentsErrorShown = true;
                Snackbar.make(binding.getRoot(), R.string.feed_error, Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_retry, ignored -> viewModel.retryComments())
                        .show();
            }
            return;
        }
        commentsErrorShown = false;
    }

    private void sendComment() {
        CharSequence raw = binding.detailInput.getText();
        String body = raw == null ? "" : raw.toString();
        if (!FeedRules.canSubmitComment(body)) {
            return;
        }
        viewModel.sendComment(body);
    }

    private void handleOutcome(@Nullable PostDetailViewModel.Outcome outcome) {
        if (outcome == null || binding == null) {
            return;
        }
        viewModel.onOutcomeShown();
        switch (outcome) {
            case COMMENT_SENT -> binding.detailInput.setText("");
            case COMMENT_EDITED -> {
                binding.detailInput.setText("");
                snackbar(R.string.feed_comment_edited);
            }
            case COMMENT_FAILED, COMMENT_EDIT_FAILED -> snackbar(R.string.feed_comment_failed);
            case COMMENT_RATE_LIMITED, COMMENT_EDIT_RATE_LIMITED ->
                    snackbar(R.string.feed_comment_rate_limited);
            case COMMENT_DELETED -> snackbar(R.string.feed_comment_deleted);
            case COMMENT_DELETE_FAILED -> snackbar(R.string.feed_comment_failed);
            case POST_DELETED -> {
                snackbar(R.string.feed_post_deleted);
                NavHostFragment.findNavController(this).navigateUp();
            }
            case POST_DELETE_FAILED -> snackbar(R.string.feed_post_delete_failed);
        }
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
                Bundle args = new Bundle();
                args.putString(PostComposeFragment.ARG_EDIT_POST_ID, post.id());
                args.putString(PostComposeFragment.ARG_EDIT_BODY, post.body());
                NavHostFragment.findNavController(this)
                        .navigate(R.id.postComposeFragment, args);
                return true;
            }
            if (id == MENU_DELETE) {
                confirmDeletePost();
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

    private void showCommentMenu(@NonNull FeedRepository.Comment comment, @NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        int order = 0;
        if (comment.mine()) {
            menu.getMenu().add(Menu.NONE, MENU_EDIT, order++, R.string.action_edit);
            menu.getMenu().add(Menu.NONE, MENU_DELETE, order++, R.string.feed_delete_comment);
        } else {
            menu.getMenu().add(Menu.NONE, MENU_REPORT, order++, R.string.feed_report);
        }
        if (comment.body() != null && !comment.body().isBlank()) {
            menu.getMenu().add(Menu.NONE, MENU_COPY, order, R.string.action_copy);
        }
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == MENU_EDIT) {
                viewModel.startEditComment(comment);
                return true;
            }
            if (id == MENU_DELETE) {
                confirmDeleteComment(comment);
                return true;
            }
            if (id == MENU_REPORT) {
                ReportSheetFragment.forComment(comment.id(), comment.body())
                        .show(getChildFragmentManager(), ReportSheetFragment.TAG);
                return true;
            }
            if (id == MENU_COPY && comment.body() != null) {
                copyText(comment.body());
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void copyText(@NonNull String text) {
        ClipboardManager clipboard =
                ContextCompat.getSystemService(requireContext(), ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("feed_text", text));
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
                                || nav.getCurrentDestination().getId() != R.id.postDetailFragment) {
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

    private void confirmDeletePost() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.feed_delete_post_title)
                .setMessage(R.string.feed_delete_body)
                .setNegativeButton(R.string.feed_cancel, null)
                .setPositiveButton(R.string.feed_delete_confirm,
                        (dialog, which) -> viewModel.deletePost())
                .show();
    }

    private void confirmDeleteComment(@NonNull FeedRepository.Comment comment) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.feed_delete_comment_title)
                .setMessage(R.string.feed_delete_body)
                .setNegativeButton(R.string.feed_cancel, null)
                .setPositiveButton(R.string.feed_delete_confirm,
                        (dialog, which) -> viewModel.deleteComment(comment.id()))
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
        Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT).show();
    }

    @NonNull
    private String requirePostId() {
        Bundle args = getArguments();
        String id = args == null ? null : args.getString(ARG_POST_ID);
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("postId argument is required");
        }
        return id;
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
        chatOpens.clear();
        openingChat = false;
        lastEditingCommentId = null;
        if (videoController != null) {
            videoController.release();
            videoController = null;
        }
        binding = null;
        super.onDestroyView();
    }
}
