package com.nexachat.data;

import com.nexachat.core.network.ApiResult;
import io.reactivex.rxjava3.core.Single;

/** Network boundary for one persisted offline reaction or pin action. */
public interface OfflineActionApi {
    Single<ApiResult<?>> dispatch(OfflineOutboxAction action);
}
