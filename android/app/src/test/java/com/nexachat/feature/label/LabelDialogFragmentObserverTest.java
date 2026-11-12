package com.nexachat.feature.label;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.lang.reflect.Field;
import java.util.List;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.Silent.class)
public final class LabelDialogFragmentObserverTest {
  @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
      new InstantTaskExecutorRule();

  @Mock private Context context;
  @Mock private Resources resources;
  @Mock private Resources.Theme theme;

  private LabelViewModel viewModel;

  @Before
  public void setUp() {
    DisplayMetrics metrics = new DisplayMetrics();
    metrics.density = 2f;
    when(context.getResources()).thenReturn(resources);
    when(context.getTheme()).thenReturn(theme);
    when(resources.getDisplayMetrics()).thenReturn(metrics);
    when(theme.resolveAttribute(
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(false);

    // load() subscribes on the io scheduler. Run it inline so no late publish from another
    // thread can render into the rows while the observer assertions below run.
    RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
    viewModel = new LabelViewModel(new StaticLabelRepository());
    viewModel.load();
  }

  @After
  public void tearDown() {
    RxJavaPlugins.reset();
  }

  @Test
  public void uiStateObserverInstanceStaysStableAcrossStopStart() throws Exception {
    LabelDialogFragment fragment = bindFragment(org.mockito.Mockito.mock(LinearLayout.class));
    startFragment(fragment, context);
    Object first = fragment.uiStateObserverForTests();
    fragment.detachUiStateObserver();
    startFragment(fragment, context);
    assertEquals(first, fragment.uiStateObserverForTests());
  }

  @Test
  public void stopStartKeepsSingleUiStateObserver() throws Exception {
    LabelDialogFragment fragment = bindFragment(org.mockito.Mockito.mock(LinearLayout.class));
    startFragment(fragment, context);
    fragment.detachUiStateObserver();
    startFragment(fragment, context);

    assertEquals(1, liveDataObserverCount(viewModel.getUiState()));
  }

  @Test
  public void stopStartDeliversOneRenderPerState() throws Exception {
    LabelDialogFragment fragment = bindFragment(org.mockito.Mockito.mock(LinearLayout.class));
    startFragment(fragment, context);
    fragment.detachUiStateObserver();
    startFragment(fragment, context);
    resetRenderCount(fragment);

    dispatchUpdatedState(fragment);

    assertEquals(1, fragment.renderInvocationCountForTests());
  }

  @Test
  public void dialogRecreationTargetsOnlyActiveRows() throws Exception {
    LinearLayout firstRows = org.mockito.Mockito.mock(LinearLayout.class);
    LabelDialogFragment fragment = bindFragment(firstRows);
    startFragment(fragment, context);
    clearInvocations(firstRows);
    fragment.releaseDialogBindingForTests();

    LinearLayout secondRows = org.mockito.Mockito.mock(LinearLayout.class);
    wireDialogContent(fragment, secondRows, context);
    startFragment(fragment, context);
    resetRenderCount(fragment);

    dispatchUpdatedState(fragment);

    verify(firstRows, never()).removeAllViews();
    assertEquals(1, fragment.renderInvocationCountForTests());
  }

  @Test
  public void dismissedDialogDoesNotRenderIntoDiscardedRows() throws Exception {
    LinearLayout rows = org.mockito.Mockito.mock(LinearLayout.class);
    LabelDialogFragment fragment = bindFragment(rows);
    startFragment(fragment, context);
    clearInvocations(rows);
    fragment.releaseDialogBindingForTests();

    publishUpdatedManageState();

    verify(rows, never()).removeAllViews();
  }

  private LabelDialogFragment bindFragment(LinearLayout rows) throws Exception {
    LabelDialogFragment fragment = spy(new LabelDialogFragment());
    doReturn(context).when(fragment).requireContext();
    doReturn(context).when(fragment).getContext();
    doReturn(resources).when(fragment).getResources();
    doReturn(true).when(fragment).isAdded();
    doReturn("label").when(fragment).getString(anyInt());
    doReturn("label").when(fragment).getString(anyInt(), any());

    setField(fragment, "viewModel", viewModel);
    setField(fragment, "manageMode", true);
    setField(fragment, "conversationId", null);
    wireDialogContent(fragment, rows, context);
    return fragment;
  }

  private static void startFragment(LabelDialogFragment fragment, Context context)
      throws Exception {
    markFragmentAdded(fragment);
    LifecycleRegistry lifecycle = (LifecycleRegistry) fragment.getLifecycle();
    lifecycle.setCurrentState(Lifecycle.State.CREATED);
    lifecycle.setCurrentState(Lifecycle.State.STARTED);
    fragment.bindUiStateObserverIfNeeded();
  }

  private static void wireDialogContent(
      LabelDialogFragment fragment, LinearLayout rows, Context context) throws Exception {
    LinearLayout content = org.mockito.Mockito.mock(LinearLayout.class);
    TextView status = org.mockito.Mockito.mock(TextView.class);
    ProgressBar progress = org.mockito.Mockito.mock(ProgressBar.class);
    Button retryButton = org.mockito.Mockito.mock(Button.class);
    Button modeButton = org.mockito.Mockito.mock(Button.class);
    Button createButton = org.mockito.Mockito.mock(Button.class);

    setField(fragment, "content", content);
    setField(fragment, "rows", rows);
    setField(fragment, "status", status);
    setField(fragment, "progress", progress);
    setField(fragment, "retryButton", retryButton);
    setField(fragment, "modeButton", modeButton);
    setField(fragment, "createButton", createButton);
    setField(fragment, "renderedErrorVersion", -1L);
  }

  private static void dispatchUpdatedState(LabelDialogFragment fragment) {
    fragment.uiStateObserverForTests().onChanged(updatedManageState());
  }

  private void publishUpdatedManageState() throws Exception {
    Field field = LabelViewModel.class.getDeclaredField("uiState");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    MutableLiveData<LabelUiState> liveData = (MutableLiveData<LabelUiState>) field.get(viewModel);
    liveData.setValue(updatedManageState());
  }

  private static LabelUiState updatedManageState() {
    return new LabelUiState(
        List.of(),
        false,
        true,
        null,
        Set.of(),
        false,
        LabelUiState.Error.NONE,
        null,
        List.of(),
        false,
        false,
        false,
        LabelUiState.Error.NONE,
        1L);
  }

  private static int liveDataObserverCount(LiveData<?> liveData) throws Exception {
    Field field = LiveData.class.getDeclaredField("mObservers");
    field.setAccessible(true);
    Object observers = field.get(liveData);
    Field sizeField = observers.getClass().getDeclaredField("mSize");
    sizeField.setAccessible(true);
    return sizeField.getInt(observers);
  }

  private static void markFragmentAdded(Fragment fragment) throws Exception {
    Field field = Fragment.class.getDeclaredField("mAdded");
    field.setAccessible(true);
    field.setBoolean(fragment, true);
  }

  private static void resetRenderCount(LabelDialogFragment fragment) throws Exception {
    setField(fragment, "renderPassCountForTests", 0);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void clearInvocations(Object mock) {
    org.mockito.Mockito.clearInvocations(mock);
  }

  private static final class StaticLabelRepository implements LabelRepository {
    @Override
    public io.reactivex.rxjava3.core.Single<List<ConversationLabel>> listLabels() {
      return io.reactivex.rxjava3.core.Single.just(List.of());
    }

    @Override
    public io.reactivex.rxjava3.core.Single<ConversationLabel> createLabel(
        String name, LabelColor color) {
      return io.reactivex.rxjava3.core.Single.error(new UnsupportedOperationException());
    }

    @Override
    public io.reactivex.rxjava3.core.Single<ConversationLabel> updateLabel(
        String labelId, String name, LabelColor color) {
      return io.reactivex.rxjava3.core.Single.error(new UnsupportedOperationException());
    }

    @Override
    public io.reactivex.rxjava3.core.Completable deleteLabel(String labelId) {
      return io.reactivex.rxjava3.core.Completable.error(new UnsupportedOperationException());
    }

    @Override
    public io.reactivex.rxjava3.core.Completable assignLabel(String conversationId, String labelId) {
      return io.reactivex.rxjava3.core.Completable.error(new UnsupportedOperationException());
    }

    @Override
    public io.reactivex.rxjava3.core.Completable unassignLabel(String conversationId, String labelId) {
      return io.reactivex.rxjava3.core.Completable.error(new UnsupportedOperationException());
    }

    @Override
    public io.reactivex.rxjava3.core.Single<Set<String>> filterConversationIds(String labelId) {
      return io.reactivex.rxjava3.core.Single.error(new UnsupportedOperationException());
    }

    @Override
    public io.reactivex.rxjava3.core.Single<List<String>> cachedLabelIds(String conversationId) {
      return io.reactivex.rxjava3.core.Single.just(List.of());
    }

    @Override
    public io.reactivex.rxjava3.core.Completable cacheLabelIds(
        String conversationId, List<String> labelIds) {
      return io.reactivex.rxjava3.core.Completable.complete();
    }

    @Override
    public io.reactivex.rxjava3.core.Completable removeLabelFromCache(String labelId) {
      return io.reactivex.rxjava3.core.Completable.complete();
    }
  }
}
