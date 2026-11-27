package com.nexachat.data;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.MessageTranslationDao;
import com.nexachat.core.network.TranslateApi;
import com.nexachat.core.network.dto.TranslateRequest;
import com.nexachat.core.network.dto.TranslateResponseDto;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.Test;
import retrofit2.Call;

public final class TranslationRepositoryTest {
    @Test
    public void socketTimeoutHasADifferentErrorFromNetworkFailure() throws IOException {
        assertSame(ApiError.Timeout.INSTANCE, translateWith(new SocketTimeoutException()));
        assertTrue(translateWith(new UnknownHostException()) instanceof ApiError.Network);
    }

    private static ApiError translateWith(IOException failure) throws IOException {
        TranslateApi api = mock(TranslateApi.class);
        MessageTranslationDao translationDao = mock(MessageTranslationDao.class);
        MessageDao messageDao = mock(MessageDao.class);
        MessageEntity message = mock(MessageEntity.class);
        when(message.getId()).thenReturn("message");
        when(message.getChangeVersion()).thenReturn(4L);
        when(messageDao.byId("message")).thenReturn(Maybe.just(message));
        when(translationDao.find("message", 4L, "vi")).thenReturn(Maybe.empty());
        @SuppressWarnings("unchecked")
        Call<TranslateResponseDto> call = mock(Call.class);
        when(api.translate(eq("message"), any(TranslateRequest.class))).thenReturn(call);
        when(call.execute()).thenThrow(failure);

        TranslationRepository repository = new TranslationRepository(api, translationDao, messageDao);
        Result<?> result = repository.translate("message", "vi").blockingGet();
        return ((Result.Failure<?>) result).getError();
    }
}
