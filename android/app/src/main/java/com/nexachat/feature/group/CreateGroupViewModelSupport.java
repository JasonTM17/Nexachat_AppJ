package com.nexachat.feature.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure input helpers for create-group. */
final class CreateGroupViewModelSupport {

    private CreateGroupViewModelSupport() { }

    static boolean canCreate(CreateGroupUiState state) {
        return state.getCanCreate();
    }

    /** Preserve the user's selection order for the request payload. */
    static List<String> selectedIds(Set<String> selectedIds) {
        return new ArrayList<>(selectedIds);
    }
}
