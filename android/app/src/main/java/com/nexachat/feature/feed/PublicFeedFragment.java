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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.databinding.FragmentPublicFeedBinding;
import com.nexachat.feature.safety.ReportSheetFragment;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

/** The public feed: newest-first posts, pull-to-refresh, paging and the first-use notice. */
@AndroidEntryPoint
public final class PublicFeedFragment extends Fragment implements ReportSheetFragment.Host {

    /** Set by the compose screen so the list picks up the new post. */
    public static final String RESULT_POST_CREATED = "feed_post_created";

    private static final int MENU_REPORT = 1;
    private static final int MENU_DELETE = 2;

    private FragmentPublicFeedBinding binding;
    private FeedViewModel viewModel;
    private PostAdapter adapter;
    @Inject FeedNoticeStore noticeStore;

    public PublicFeedFragment() {
        super(R.layout.fragment_public_feed);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentPublicFeedBinding.bind(view);
        viewModel = new ViewModelProvider(this).get(FeedViewModel.class);
        binding.feedToolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        binding.feedCompose.setOnClickListener(ignored ->
                NavHostFragment.findNavController(this).navigate(R.id.postComposeFragment));
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
        });
        binding.feedList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.feedList.setAdapter(adapter);
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
        binding.feedEmpty.setMessage(getString(R.string.feed_empty));
        binding.feedEmpty.setIcon(R.drawable.ic_public);
        binding.feedError.setMessage(getString(R.string.feed_error));
        binding.feedError.setOnRetry(viewModel::retry);
        getParentFragmentManager().setFragmentResultListener(
                RESULT_POST_CREATED, getViewLifecycleOwner(),
                (key, result) -> viewModel.refresh());
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        showNoticeOnce();
    }

    private void render(@NonNull FeedViewModel.UiState state) {
        if (binding == null) {
            return;
        }
        adapter.submitList(state.getItems());
        boolean empty = state.getItems().isEmpty();
        binding.feedList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.feedLoading.setVisibility(state.isLoading() && empty ? View.VISIBLE : View.GONE);
        binding.feedEmpty.setVisibility(
                !state.isLoading() && !state.isError() && empty ? View.VISIBLE : View.GONE);
        binding.feedError.setVisibility(state.isError() ? View.VISIBLE : View.GONE);
        binding.feedRefresh.setRefreshing(state.isRefreshing());
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
                confirmDeletePost(post);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void confirmDeletePost(@NonNull FeedRepository.Post post) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.feed_delete_post_title)
                .setMessage(R.string.feed_delete_body)
                .setNegativeButton(R.string.feed_cancel, null)
                .setPositiveButton(R.string.feed_delete_confirm, (dialog, which) -> {
                    viewModel.deletePost(post.id());
                    snackbar(R.string.feed_post_deleted);
                })
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

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
