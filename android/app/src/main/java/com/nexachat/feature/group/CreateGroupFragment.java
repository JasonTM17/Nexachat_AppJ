package com.nexachat.feature.group;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.nexachat.R;
import com.nexachat.data.Contact;
import com.nexachat.databinding.FragmentCreateGroupBinding;
import dagger.hilt.android.AndroidEntryPoint;

/** Builds a group from selected contacts and opens its conversation. */
@AndroidEntryPoint
public final class CreateGroupFragment extends Fragment {
    private FragmentCreateGroupBinding binding; private CreateGroupViewModel viewModel;
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater i,@Nullable ViewGroup c,@Nullable Bundle s){ binding=FragmentCreateGroupBinding.inflate(i,c,false); return binding.getRoot(); }
    @Override public void onViewCreated(@NonNull View view,@Nullable Bundle state){
        viewModel=new ViewModelProvider(this).get(CreateGroupViewModel.class); binding.memberPickerList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.toolbar.setNavigationOnClickListener(v->Navigation.findNavController(v).navigateUp());
        binding.groupNameInput.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int d){} public void onTextChanged(CharSequence s,int a,int b,int c){viewModel.onTitleChange(s.toString());} public void afterTextChanged(Editable e){}});
        binding.createButton.setOnClickListener(v->viewModel.create());
        viewModel.getUiState().getValue().getContacts().forEach(this::addContactRow);
    }
    private void addContactRow(Contact contact){ CheckBox row=new CheckBox(requireContext()); row.setText(contact.getPrimaryLabel()); row.setMinHeight(getResources().getDimensionPixelSize(R.dimen.min_touch_target)); row.setOnCheckedChangeListener((b,checked)->viewModel.toggleMember(contact.getUserId())); binding.memberPickerList.addView(row,new RecyclerView.LayoutParams(-1,-2)); }
    @Override public void onDestroyView(){super.onDestroyView();binding=null;}
}
