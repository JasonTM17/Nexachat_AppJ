package com.nexachat.feature.contacts;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.nexachat.R;
import com.nexachat.data.Contact;
import com.nexachat.databinding.FragmentContactsBinding;
import com.nexachat.feature.contacts.adapter.ContactListAdapter;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.List;

/** Displays cached contacts and opens contact search. */
@AndroidEntryPoint
public final class ContactsFragment extends Fragment {
    private FragmentContactsBinding binding;
    private ContactsViewModel viewModel;
    private ContactListAdapter adapter;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        binding = FragmentContactsBinding.inflate(inflater, container, false); return binding.getRoot();
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        viewModel = new ViewModelProvider(this).get(ContactsViewModel.class);
        adapter = new ContactListAdapter(id -> {
            List<Contact> contacts = viewModel.getUiState().getValue().getContacts();
            for (Contact contact : contacts) if (contact.getUserId().equals(id)) { viewModel.openContactDetail(contact); showContactActions(contact); break; }
        });
        binding.contactList.setLayoutManager(new LinearLayoutManager(requireContext())); binding.contactList.setAdapter(adapter);
        binding.toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(v).navigateUp());
        binding.searchButton.setOnClickListener(v -> Navigation.findNavController(v).navigate(R.id.searchFragment));
        adapter.submitList(viewModel.getUiState().getValue().getContacts());
    }
    private void showContactActions(Contact contact) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(contact.getPrimaryLabel()).setItems(new CharSequence[]{getString(R.string.action_message), getString(R.string.action_edit), getString(R.string.action_remove_contact)}, (dialog, which) -> {
                    if (which == 0) viewModel.messageSelected();
                    else if (which == 1) showAliasDialog(contact);
                    else new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setMessage(getString(R.string.action_remove_contact) + "?").setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.action_remove_contact, (d, w) -> viewModel.removeSelectedContact()).show();
                }).show();
    }
    private void showAliasDialog(Contact contact) {
        android.widget.EditText input = new android.widget.EditText(requireContext()); input.setSingleLine(); input.setText(contact.getAliasName());
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.contact_alias_label).setView(input).setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, (d, w) -> { viewModel.onAliasChange(input.getText().toString()); viewModel.saveAlias(); }).show();
    }
    @Override public void onDestroyView() { super.onDestroyView(); binding = null; }
}
