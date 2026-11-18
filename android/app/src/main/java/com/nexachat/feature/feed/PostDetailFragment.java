package com.nexachat.feature.feed;

import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.databinding.FragmentPostDetailBinding;
import com.nexachat.feature.safety.ReportSheetFragment;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.List;

/** One post with its flat comments and the comment composer. */
@AndroidEntryPoint
public final class PostDetailFragment extends Fragment implements ReportSheetFragment.Host {

    public static final String ARG_POST_ID = "postId";

    private static final int MENU_REPORT = 1;
    private static final int MENU_DELETE = 2;

    private FragmentPostDetailBinding binding;
    private PostDetailViewModel viewModel;
    private PostAdapter postAdapter;
    private CommentAdapter commentAdapter;

    public PostDetailFragment() {
        super(R.layout.fragment_post_detail);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentPostDetailBinding.bind(view);
        viewModel = new ViewModelProvider(this).get(PostDetailViewModel.class);
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
        });
        commentAdapter = new CommentAdapter(this::showCommentMenu);
        binding.detailList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.detailList.setAdapter(new ConcatAdapter(postAdapter, commentAdapter));
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
        binding.detailError.setMessage(getString(R.string.feed_error));
        binding.detailError.setOnRetry(() -> viewModel.load(requirePostId()));
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
        binding.detailList.setVisibility(post == null ? View.GONE : View.VISIBLE);
        binding.detailComposer.setVisibility(post == null ? View.GONE : View.VISIBLE);
        binding.detailSend.setEnabled(!state.isSending());
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
            case COMMENT_FAILED -> snackbar(R.string.feed_comment_failed);
            case COMMENT_RATE_LIMITED -> snackbar(R.string.feed_comment_rate_limited);
            case COMMENT_DELETED -> snackbar(R.string.feed_comment_deleted);
            case COMMENT_DELETE_FAILED -> snackbar(R.string.feed_comment_failed);
            case POST_DELETED -> {
                snackbar(R.string.feed_post_deleted);
                NavHostFragment.findNavController(this).navigateUp();
            }
            case POST_DELETE_FAILED -> snackbar(R.string.feed_post_failed);
        }
    }

    private void showPostMenu(@NonNull FeedRepository.Post post, @NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(Menu.NONE, MENU_REPORT, 0, R.string.feed_report);
        if (post.mine()) {
            menu.getMenu().add(Menu.NONE, MENU_DELETE, 1, R.string.feed_delete_post);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_REPORT) {
                ReportSheetFragment.forPost(post.id(), post.body())
                        .show(getChildFragmentManager(), ReportSheetFragment.TAG);
                return true;
            }
            if (item.getItemId() == MENU_DELETE) {
                confirmDeletePost();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showCommentMenu(@NonNull FeedRepository.Comment comment, @NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(Menu.NONE, MENU_REPORT, 0, R.string.feed_report);
        menu.getMenu().add(Menu.NONE, MENU_DELETE, 1, R.string.feed_delete_comment);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_REPORT) {
                ReportSheetFragment.forComment(comment.id(), comment.body())
                        .show(getChildFragmentManager(), ReportSheetFragment.TAG);
                return true;
            }
            if (item.getItemId() == MENU_DELETE) {
                confirmDeleteComment(comment);
                return true;
            }
            return false;
        });
        menu.show();
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
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
