package com.nexachat.feature.safety;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import com.nexachat.databinding.SheetReportBinding;
import com.nexachat.feature.ui.NexaBottomSheetFragment;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;

/**
 * Abuse-report sheet shared by message, user, and conversation targets: the
 * caller picks a reason chip, optionally adds details, and submits. On success
 * the host shows a confirmation snackbar through {@link Host#onReportSent()}.
 */
@AndroidEntryPoint
public final class ReportSheetFragment extends NexaBottomSheetFragment {

    public static final String TAG = "report_sheet";

    private static final String ARG_TARGET_TYPE = "targetType";
    private static final String ARG_TARGET_ID = "targetId";
    private static final String ARG_SNAPSHOT = "snapshot";

    /** Notified after the report is accepted so the host can acknowledge it. */
    public interface Host {
        void onReportSent();
    }

    @Inject SafetyRepository safetyRepository;

    private final CompositeDisposable disposables = new CompositeDisposable();
    private SheetReportBinding binding;
    private Map<Integer, ReportReason> reasonByChipId;

    @NonNull
    public static ReportSheetFragment forMessage(@NonNull String messageId, @Nullable String snapshot) {
        return newInstance("MESSAGE", messageId, snapshot);
    }

    @NonNull
    public static ReportSheetFragment forUser(@NonNull String userId) {
        return newInstance("USER", userId, null);
    }

    @NonNull
    public static ReportSheetFragment forConversation(@NonNull String conversationId) {
        return newInstance("CONVERSATION", conversationId, null);
    }

    /** A public feed post; the snapshot is the server's copy of the body. */
    @NonNull
    public static ReportSheetFragment forPost(@NonNull String postId, @Nullable String snapshot) {
        return newInstance("POST", postId, snapshot);
    }

    /** A public feed comment; the snapshot is the server's copy of the body. */
    @NonNull
    public static ReportSheetFragment forComment(
            @NonNull String commentId, @Nullable String snapshot) {
        return newInstance("COMMENT", commentId, snapshot);
    }

    @NonNull
    private static ReportSheetFragment newInstance(
            @NonNull String targetType, @NonNull String targetId, @Nullable String snapshot) {
        ReportSheetFragment sheet = new ReportSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TARGET_TYPE, targetType);
        args.putString(ARG_TARGET_ID, targetId);
        if (snapshot != null) {
            args.putString(ARG_SNAPSHOT, snapshot);
        }
        sheet.setArguments(args);
        return sheet;
    }

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = SheetReportBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewCompat.setAccessibilityHeading(binding.reportTitle, true);
        reasonByChipId = new LinkedHashMap<>();
        reasonByChipId.put(R.id.reasonSpam, ReportReason.SPAM);
        reasonByChipId.put(R.id.reasonHarassment, ReportReason.HARASSMENT);
        reasonByChipId.put(R.id.reasonViolence, ReportReason.VIOLENCE);
        reasonByChipId.put(R.id.reasonSexual, ReportReason.SEXUAL);
        reasonByChipId.put(R.id.reasonScam, ReportReason.SCAM);
        reasonByChipId.put(R.id.reasonOther, ReportReason.OTHER);
        binding.reportReasons.setOnCheckedStateChangeListener(
                (group, checkedIds) -> binding.reportSubmit.setEnabled(!checkedIds.isEmpty()));
        binding.reportSubmit.setOnClickListener(ignored -> submit());
    }

    private void submit() {
        int checkedId = binding.reportReasons.getCheckedChipId();
        ReportReason reason = reasonByChipId.get(checkedId);
        Bundle args = getArguments();
        if (reason == null || args == null) {
            return;
        }
        binding.reportSubmit.setEnabled(false);
        CharSequence raw = binding.reportDetailsInput.getText();
        String details = raw == null ? null : raw.toString();
        disposables.add(safetyRepository
                .report(
                        args.getString(ARG_TARGET_TYPE, ""),
                        args.getString(ARG_TARGET_ID, ""),
                        reason,
                        details,
                        args.getString(ARG_SNAPSHOT))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        () -> {
                            notifySent();
                            dismissAllowingStateLoss();
                        },
                        error -> {
                            if (binding == null) {
                                return;
                            }
                            binding.reportSubmit.setEnabled(true);
                            int message = error instanceof SafetyApiException api
                                            && api.isRateLimited()
                                    ? R.string.report_rate_limited
                                    : R.string.report_error_snackbar;
                            Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_SHORT).show();
                        }));
    }

    private void notifySent() {
        Fragment parent = getParentFragment();
        if (parent instanceof Host host) {
            host.onReportSent();
            return;
        }
        if (getActivity() instanceof Host host) {
            host.onReportSent();
        }
    }

    @Override
    public void onDestroyView() {
        disposables.clear();
        binding = null;
        reasonByChipId = null;
        super.onDestroyView();
    }
}
