package com.nexachat.feature.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import org.junit.Test;

public final class RecoveryEmailManageUiRulesTest {

  @Test
  public void showsLoadingWhileProfileIsUnresolved() {
    assertTrue(RecoveryEmailManageUiRules.showInitialLoading(true, null, null));
    assertFalse(RecoveryEmailManageUiRules.showInitialLoading(false, null, null));
    assertFalse(
        RecoveryEmailManageUiRules.showInitialLoading(
            true, new UserDto("1", null, "a", null, null, false), null));
    assertFalse(
        RecoveryEmailManageUiRules.showInitialLoading(
            true, null, new ApiError.Network()));
  }
}
