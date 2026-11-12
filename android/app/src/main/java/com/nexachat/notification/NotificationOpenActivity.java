package com.nexachat.notification;

import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;
import androidx.navigation.NavDeepLinkBuilder;
import com.nexachat.MainActivity;
import com.nexachat.R;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import javax.inject.Inject;

/** Revalidates account, session and conversation membership before following a notification link. */
@AndroidEntryPoint
public final class NotificationOpenActivity extends ComponentActivity {
    @Inject NotificationAdmission admission;
    @Nullable private Disposable validation;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NotificationWorkRequest request = NotificationWorkRequest.fromIntent(getIntent());
        if (request == null) {
            finish();
            return;
        }
        validation = admission.syncAndCheck(request.getRecipientId(),
                        request.getSessionEpoch(), request.getConversationId())
                .flatMap(allowed -> allowed
                        ? Single.fromCallable(() -> admission.runIfCurrent(
                                request.getRecipientId(), request.getSessionEpoch(),
                                request.getConversationId(), conversation -> open(request,
                                        "GROUP".equalsIgnoreCase(conversation.getType()))))
                        : Single.just(false))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> {
                    finish();
                }, ignored -> finish());
    }

    @Override
    protected void onDestroy() {
        if (validation != null) validation.dispose();
        super.onDestroy();
    }

    private void open(NotificationWorkRequest request, boolean isGroup) {
        Bundle arguments = new Bundle();
        arguments.putString("conversationId", request.getConversationId());
        int destination = R.id.chatFragment;
        if (NotificationWorkRequest.TYPE_REMINDER.equals(request.getType())) {
            destination = R.id.groupBoardFragment;
            arguments.putBoolean("isGroup", isGroup);
        }
        new NavDeepLinkBuilder(this)
                .setComponentName(MainActivity.class)
                .setGraph(R.navigation.nav_main)
                .setDestination(destination)
                .setArguments(arguments)
                .createTaskStackBuilder()
                .startActivities();
    }
}
