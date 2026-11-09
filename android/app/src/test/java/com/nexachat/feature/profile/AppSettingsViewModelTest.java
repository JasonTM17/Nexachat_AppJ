package com.nexachat.feature.profile;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.nexachat.data.SettingsRepository;
import com.nexachat.data.ThemeMode;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import org.junit.Rule;
import org.junit.Test;

public final class AppSettingsViewModelTest {

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Test
    public void mirrorsThemeModeFromRepositoryAndPersistsSelection() {
        SettingsRepository repository = mock(SettingsRepository.class);
        BehaviorProcessor<ThemeMode> theme = BehaviorProcessor.createDefault(ThemeMode.SYSTEM);
        when(repository.getThemeMode()).thenReturn(theme);

        AppSettingsViewModel viewModel = new AppSettingsViewModel(repository);
        assertEquals(ThemeMode.SYSTEM, viewModel.getThemeMode().getValue());

        theme.onNext(ThemeMode.DARK);
        assertEquals(ThemeMode.DARK, viewModel.getThemeMode().getValue());

        viewModel.setThemeMode(ThemeMode.LIGHT);
        verify(repository).setThemeMode(ThemeMode.LIGHT);
    }
}
