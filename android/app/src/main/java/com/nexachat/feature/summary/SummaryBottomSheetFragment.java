package com.nexachat.feature.summary;

import android.os.Bundle; import android.view.LayoutInflater; import android.view.View; import android.view.ViewGroup; import android.widget.TextView; import androidx.annotation.NonNull; import androidx.annotation.Nullable; import androidx.lifecycle.ViewModelProvider; import androidx.recyclerview.widget.LinearLayoutManager; import androidx.recyclerview.widget.RecyclerView; import com.google.android.material.bottomsheet.BottomSheetDialogFragment; import com.nexachat.R; import com.nexachat.databinding.BottomSheetSummaryBinding; import com.nexachat.feature.summary.adapter.SummaryCitationAdapter; import com.nexachat.summary.SummaryScope; import com.nexachat.summary.SummaryTextFormatter; import com.nexachat.summary.SummaryUiState; import com.nexachat.summary.SummaryViewModel; import dagger.hilt.android.AndroidEntryPoint; import java.util.Locale;

/** Local summary sheet with loading, error, citation navigation, and dismiss hooks. */
@AndroidEntryPoint public final class SummaryBottomSheetFragment extends BottomSheetDialogFragment {
    public interface Listener { void onJumpToMessage(@NonNull String messageId); }
    private BottomSheetSummaryBinding binding; private SummaryViewModel viewModel; private Listener listener;
    public void setListener(@NonNull Listener value){listener=value;}
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater i,@Nullable ViewGroup c,@Nullable Bundle s){binding=BottomSheetSummaryBinding.inflate(i,c,false);return binding.getRoot();}
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){viewModel=new ViewModelProvider(this).get(SummaryViewModel.class);viewModel.state().observe(getViewLifecycleOwner(),this::render);viewModel.request(SummaryScope.UNREAD,null);}
    private void render(SummaryUiState state){if(binding==null||state==null)return;binding.summarySkeleton.getRoot().setVisibility(state.getStatus()==SummaryUiState.Status.LOADING?View.VISIBLE:View.GONE);binding.summaryError.getRoot().setVisibility(state.getStatus()==SummaryUiState.Status.ERROR?View.VISIBLE:View.GONE);binding.summaryBody.setVisibility(state.getStatus()==SummaryUiState.Status.READY?View.VISIBLE:View.GONE);if(state.getStatus()==SummaryUiState.Status.READY)binding.summaryBody.setText(SummaryTextFormatter.toShareText(state.getSummary(),Locale.getDefault()));if(state.getStatus()==SummaryUiState.Status.ERROR)SummaryErrorViewHelper.bind(binding.summaryError.getRoot(),state.getError(),viewModel::retry);}
    @Override public void onDestroyView(){super.onDestroyView();binding=null;}
}
