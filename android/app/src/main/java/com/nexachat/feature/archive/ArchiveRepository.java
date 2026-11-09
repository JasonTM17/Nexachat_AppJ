package com.nexachat.feature.archive;

/** Cancellable archive data boundary used by the screen and its unit tests. */
public interface ArchiveRepository {

    Cancellable load(ArchiveQuery query, Callback callback);

    interface Cancellable {
        void cancel();
    }

    interface Callback {
        void onSuccess(ArchivePage page);

        void onFailure(Failure failure);
    }

    enum Failure {
        OFFLINE,
        FORBIDDEN,
        NOT_FOUND,
        UNKNOWN
    }
}
