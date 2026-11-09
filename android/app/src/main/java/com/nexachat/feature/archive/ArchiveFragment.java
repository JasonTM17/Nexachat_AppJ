package com.nexachat.feature.archive;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Menu;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.widget.PopupMenu;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.nexachat.R;
import com.nexachat.core.common.Result;
import com.nexachat.data.AttachmentRepository;
import com.nexachat.databinding.FragmentArchiveBinding;
import com.nexachat.feature.media.AttachmentViewBinder;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.File;
import java.util.Date;
import java.util.List;
import javax.inject.Inject;

/** Conversation-wide shared media, files, and links. */
@AndroidEntryPoint
public final class ArchiveFragment extends Fragment {

    private static final int ACTION_OPEN = 1;
    private static final int ACTION_ORIGINAL = 2;
    private static final int ACTION_SHARE = 3;
    private static final String DATE_PICKER_TAG = "archive_date_range";

    @Inject AttachmentRepository attachmentRepository;

    private final CompositeDisposable disposables = new CompositeDisposable();
    @Nullable private FragmentArchiveBinding binding;
    @Nullable private ArchiveViewModel viewModel;
    @Nullable private ArchiveUiState state;
    @Nullable private ArchiveRepository.Failure lastPagingError;
    private ArchiveMediaAdapter mediaAdapter;
    private ArchiveListAdapter fileAdapter;
    private ArchiveListAdapter linkAdapter;
    private ArchiveType adapterType;
    private String conversationId = "";
    private boolean selectingTab;

    public ArchiveFragment() {
        super(R.layout.fragment_archive);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding = FragmentArchiveBinding.bind(view);
        conversationId = getArguments() == null
                ? ""
                : getArguments().getString(ArchiveViewModel.ARG_CONVERSATION_ID, "");
        viewModel = new ViewModelProvider(this).get(ArchiveViewModel.class);
        binding.archiveToolbar.setNavigationOnClickListener(
                ignored -> NavHostFragment.findNavController(this).navigateUp());
        binding.archiveEmpty.setIcon(R.drawable.ic_image);
        binding.archiveEmpty.setMessage(getString(R.string.archive_empty));
        configureTabs();
        configureAdapters();
        configureFilters();
        configurePaging();
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.loadCurrentPage();
    }

    private void configureTabs() {
        binding.archiveTabs.addTab(binding.archiveTabs.newTab()
                .setText(R.string.archive_tab_media), true);
        binding.archiveTabs.addTab(binding.archiveTabs.newTab()
                .setText(R.string.archive_tab_files));
        binding.archiveTabs.addTab(binding.archiveTabs.newTab()
                .setText(R.string.archive_tab_links));
        binding.archiveTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (!selectingTab && viewModel != null) {
                    viewModel.selectType(typeAt(tab.getPosition()));
                }
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
    }

    private void configureAdapters() {
        ArchiveMediaAdapter.Listener mediaListener = new ArchiveMediaAdapter.Listener() {
            @Override
            public void onOpen(@NonNull ArchivePage.Item item) {
                openItem(item);
            }

            @Override
            public void onOptions(@NonNull ArchivePage.Item item, @NonNull View anchor) {
                showItemActions(item, anchor);
            }
        };
        ArchiveListAdapter.Listener listListener = new ArchiveListAdapter.Listener() {
            @Override
            public void onOpen(@NonNull ArchivePage.Item item) {
                openItem(item);
            }

            @Override
            public void onOptions(@NonNull ArchivePage.Item item, @NonNull View anchor) {
                showItemActions(item, anchor);
            }
        };
        mediaAdapter = new ArchiveMediaAdapter(mediaListener);
        fileAdapter = new ArchiveListAdapter(ArchiveType.FILE, listListener);
        linkAdapter = new ArchiveListAdapter(ArchiveType.LINK, listListener);
    }

    private void configureFilters() {
        binding.archiveSenderFilter.setOnClickListener(ignored -> showSenderFilter());
        binding.archiveDateFilter.setOnClickListener(ignored -> showDateFilter());
        binding.archiveClearFilters.setOnClickListener(ignored -> {
            if (viewModel != null) {
                viewModel.clearFilters();
            }
        });
    }

    private void configurePaging() {
        binding.archiveList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                ArchiveViewModel current = viewModel;
                if (current != null && dy > 0 && !recyclerView.canScrollVertically(1)) {
                    current.loadNextPage();
                }
            }
        });
    }

    private void showSenderFilter() {
        ArchiveUiState current = state;
        ArchiveViewModel currentViewModel = viewModel;
        if (current == null || currentViewModel == null || !current.areSendersLoaded()) {
            return;
        }
        List<ArchiveSender> senders = current.getSenders();
        CharSequence[] labels = new CharSequence[senders.size() + 1];
        labels[0] = getString(R.string.archive_filter_all_senders);
        int checked = current.getSenderId() == null ? 0 : -1;
        for (int index = 0; index < senders.size(); index++) {
            ArchiveSender sender = senders.get(index);
            labels[index + 1] = sender.getDisplayName() == null || sender.getDisplayName().isBlank()
                    ? getString(R.string.archive_unknown_sender)
                    : sender.getDisplayName();
            if (sender.getId().equals(current.getSenderId())) {
                checked = index + 1;
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.archive_filter_sender)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    currentViewModel.selectSender(which == 0 ? null : senders.get(which - 1).getId());
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showDateFilter() {
        ArchiveUiState current = state;
        if (current == null) {
            return;
        }
        MaterialDatePicker.Builder<Pair<Long, Long>> builder =
                MaterialDatePicker.Builder.dateRangePicker()
                        .setTitleText(R.string.archive_filter_date);
        Long start = ArchiveDateRange.toPickerMillis(current.getFrom());
        Long end = ArchiveDateRange.toPickerMillis(current.getTo());
        if (start != null && end != null) {
            builder.setSelection(new Pair<>(start, end));
        }
        MaterialDatePicker<Pair<Long, Long>> picker = builder.build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (viewModel != null && selection != null
                    && selection.first != null && selection.second != null) {
                viewModel.selectDateRange(
                        ArchiveDateRange.startOfDay(selection.first),
                        ArchiveDateRange.endOfDay(selection.second));
            }
        });
        picker.show(getParentFragmentManager(), DATE_PICKER_TAG);
    }

    private void render(@NonNull ArchiveUiState nextState) {
        FragmentArchiveBinding ui = binding;
        if (ui == null) {
            return;
        }
        state = nextState;
        int tabPosition = nextState.getSelectedType().ordinal();
        if (ui.archiveTabs.getSelectedTabPosition() != tabPosition) {
            selectingTab = true;
            ui.archiveTabs.selectTab(ui.archiveTabs.getTabAt(tabPosition));
            selectingTab = false;
        }
        renderFilters(ui, nextState);
        setActiveAdapter(nextState.getSelectedType());

        ArchivePageState page = nextState.getPage(nextState.getSelectedType());
        List<ArchivePage.Item> items = page.getItems();
        if (nextState.getSelectedType() == ArchiveType.MEDIA) {
            ui.archiveEmpty.setIcon(R.drawable.ic_image);
            mediaAdapter.setState(nextState);
            mediaAdapter.submitList(items);
        } else if (nextState.getSelectedType() == ArchiveType.FILE) {
            ui.archiveEmpty.setIcon(R.drawable.ic_attach_file);
            fileAdapter.setState(nextState);
            fileAdapter.submitList(items);
        } else {
            ui.archiveEmpty.setIcon(R.drawable.ic_description);
            linkAdapter.setState(nextState);
            linkAdapter.submitList(items);
        }

        boolean hasItems = !items.isEmpty();
        boolean isTerminalError = page.getError() == ArchiveRepository.Failure.FORBIDDEN
                || page.getError() == ArchiveRepository.Failure.NOT_FOUND;
        boolean blockedByError = page.getError() != null && (!hasItems || isTerminalError);
        ui.archiveList.setVisibility(hasItems && !blockedByError ? View.VISIBLE : View.GONE);
        ui.archiveLoading.setVisibility(page.isLoading() && !hasItems ? View.VISIBLE : View.GONE);
        ui.archiveLoading.setLabel(getString(R.string.archive_loading));
        ui.archiveEmpty.setVisibility(page.isEmpty() ? View.VISIBLE : View.GONE);
        ui.archiveError.setVisibility(blockedByError ? View.VISIBLE : View.GONE);
        ui.archiveNextPageProgress.setVisibility(page.isLoading() && hasItems
                ? View.VISIBLE
                : View.GONE);
        if (blockedByError) {
            ui.archiveError.setMessage(errorMessage(page.getError()));
            ui.archiveError.setOnRetry(isTerminalError
                    ? null
                    : () -> {
                        if (viewModel != null) viewModel.retry();
                    });
        } else if (page.getError() != null && page.getError() != lastPagingError) {
            Snackbar.make(ui.getRoot(), errorMessage(page.getError()), Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_retry, ignored -> {
                        if (viewModel != null) viewModel.retry();
                    })
                    .show();
        }
        lastPagingError = page.getError();
    }

    private void renderFilters(FragmentArchiveBinding ui, ArchiveUiState current) {
        if (current.getSenderId() == null) {
            ui.archiveSenderFilter.setText(R.string.archive_filter_all_senders);
            ui.archiveSenderFilter.setContentDescription(getString(R.string.archive_filter_sender));
        } else {
            String senderName = ArchiveText.senderName(requireContext(), current, current.getSenderId());
            ui.archiveSenderFilter.setText(getString(R.string.archive_filter_sender_value, senderName));
            ui.archiveSenderFilter.setContentDescription(getString(R.string.archive_filter_sender_value, senderName));
        }
        ui.archiveSenderFilter.setEnabled(current.areSendersLoaded());
        if (current.getFrom() == null || current.getTo() == null) {
            ui.archiveDateFilter.setText(R.string.archive_filter_date);
            ui.archiveDateFilter.setContentDescription(getString(R.string.archive_filter_date));
        } else {
            String dateRange = getString(
                    R.string.archive_filter_date_value,
                    dateLabel(current.getFrom()), dateLabel(current.getTo()));
            ui.archiveDateFilter.setText(dateRange);
            ui.archiveDateFilter.setContentDescription(dateRange);
        }
        ui.archiveClearFilters.setVisibility(current.hasFilters() ? View.VISIBLE : View.GONE);
    }

    private String dateLabel(String iso) {
        Long millis = ArchiveDateRange.toPickerMillis(iso);
        return millis == null
                ? ""
                : DateFormat.getDateFormat(requireContext()).format(new Date(millis));
    }

    private void setActiveAdapter(ArchiveType type) {
        FragmentArchiveBinding ui = binding;
        if (ui == null || adapterType == type) {
            return;
        }
        adapterType = type;
        if (type == ArchiveType.MEDIA) {
            ui.archiveList.setLayoutManager(new GridLayoutManager(requireContext(), 2));
            ui.archiveList.setAdapter(mediaAdapter);
        } else {
            ui.archiveList.setLayoutManager(new LinearLayoutManager(requireContext()));
            ui.archiveList.setAdapter(type == ArchiveType.FILE ? fileAdapter : linkAdapter);
        }
    }

    private void showItemActions(@NonNull ArchivePage.Item item, @NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(Menu.NONE, ACTION_OPEN, 0, R.string.archive_action_open);
        menu.getMenu().add(Menu.NONE, ACTION_ORIGINAL, 1, R.string.archive_action_original);
        menu.getMenu().add(Menu.NONE, ACTION_SHARE, 2, R.string.archive_action_share);
        menu.setOnMenuItemClickListener(clicked -> {
            if (clicked.getItemId() == ACTION_OPEN) {
                openItem(item);
                return true;
            }
            if (clicked.getItemId() == ACTION_ORIGINAL) {
                openOriginalMessage(item);
                return true;
            }
            if (clicked.getItemId() == ACTION_SHARE) {
                shareItem(item);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void openItem(@NonNull ArchivePage.Item item) {
        if (item.getLink() != null) {
            openLink(item.getLink().getUrl());
            return;
        }
        ArchivePage.Attachment attachment = item.getAttachment();
        if (attachment == null || isBlank(attachment.getId())) {
            showOpenFailure();
            return;
        }
        if ("IMAGE".equals(item.getKind()) || "VIDEO".equals(item.getKind())) {
            Bundle args = new Bundle();
            args.putString("attachmentId", attachment.getId());
            args.putString("name", attachment.getFileName());
            args.putString("mime", mimeType(attachment, item.getKind()));
            try {
                NavHostFragment.findNavController(this).navigate(R.id.mediaViewerFragment, args);
            } catch (RuntimeException ignored) {
                showOpenFailure();
            }
            return;
        }
        downloadAttachment(attachment, file -> AttachmentViewBinder.openExternally(
                requireContext(), file, mimeType(attachment, item.getKind())));
    }

    private void shareItem(@NonNull ArchivePage.Item item) {
        if (item.getLink() != null) {
            String safeUrl = ArchiveLinkRules.safeHttpsUrl(item.getLink().getUrl());
            if (safeUrl == null) {
                Toast.makeText(requireContext(), R.string.archive_link_unsafe, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, safeUrl);
            startShareChooser(share);
            return;
        }
        ArchivePage.Attachment attachment = item.getAttachment();
        if (attachment == null || isBlank(attachment.getId())) {
            showOpenFailure();
            return;
        }
        downloadAttachment(attachment, file -> {
            Intent share = AttachmentViewBinder.createShareIntent(
                    requireContext(), file, mimeType(attachment, item.getKind()));
            startShareChooser(share);
        });
    }

    private void openLink(@Nullable String rawUrl) {
        String safeUrl = ArchiveLinkRules.safeHttpsUrl(rawUrl);
        if (safeUrl == null) {
            Toast.makeText(requireContext(), R.string.archive_link_unsafe, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)));
        } catch (ActivityNotFoundException ignored) {
            showOpenFailure();
        }
    }

    private void startShareChooser(@NonNull Intent intent) {
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.archive_share_chooser)));
        } catch (ActivityNotFoundException ignored) {
            showOpenFailure();
        }
    }

    private void downloadAttachment(
            @NonNull ArchivePage.Attachment attachment,
            @NonNull FileCallback callback) {
        disposables.add(attachmentRepository.downloadToCache(
                        attachment.getId(), attachment.getFileName())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (!isAdded() || binding == null) {
                        return;
                    }
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof File file) {
                        callback.onFile(file);
                    } else {
                        showOpenFailure();
                    }
                }, ignored -> {
                    if (isAdded() && binding != null) {
                        showOpenFailure();
                    }
                }));
    }

    private void openOriginalMessage(@NonNull ArchivePage.Item item) {
        if (isBlank(item.getMessageId()) || isBlank(conversationId)) {
            showOpenFailure();
            return;
        }
        ArchiveNavigationArgs args = ArchiveNavigationArgs.forOriginalMessage(
                conversationId, item.getMessageId());
        Bundle bundle = new Bundle();
        bundle.putString(ArchiveViewModel.ARG_CONVERSATION_ID, args.getConversationId());
        bundle.putString("archiveJumpMessageId", args.getMessageId());
        NavOptions options = new NavOptions.Builder()
                .setPopUpTo(R.id.chatFragment, true)
                .build();
        try {
            NavHostFragment.findNavController(this)
                    .navigate(R.id.chatFragment, bundle, options);
        } catch (RuntimeException ignored) {
            showOpenFailure();
        }
    }

    private void showOpenFailure() {
        if (isAdded()) {
            Toast.makeText(requireContext(), R.string.archive_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @NonNull
    private String errorMessage(@Nullable ArchiveRepository.Failure failure) {
        if (failure == ArchiveRepository.Failure.FORBIDDEN) {
            return getString(R.string.archive_forbidden);
        }
        if (failure == ArchiveRepository.Failure.NOT_FOUND) {
            return getString(R.string.archive_not_found);
        }
        if (failure == ArchiveRepository.Failure.OFFLINE) {
            return getString(R.string.archive_offline);
        }
        return getString(R.string.archive_error);
    }

    @NonNull
    private static String mimeType(
            @NonNull ArchivePage.Attachment attachment,
            @Nullable String kind) {
        if (!isBlank(attachment.getMimeType())) {
            return attachment.getMimeType();
        }
        if ("VIDEO".equals(kind)) {
            return "video/mp4";
        }
        if ("IMAGE".equals(kind)) {
            return "image/*";
        }
        return "application/octet-stream";
    }

    private static ArchiveType typeAt(int position) {
        return switch (position) {
            case 1 -> ArchiveType.FILE;
            case 2 -> ArchiveType.LINK;
            default -> ArchiveType.MEDIA;
        };
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        state = null;
        adapterType = null;
        binding = null;
        super.onDestroyView();
    }

    @FunctionalInterface
    private interface FileCallback {
        void onFile(@NonNull File file);
    }
}
