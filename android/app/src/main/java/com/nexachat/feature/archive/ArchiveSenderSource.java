package com.nexachat.feature.archive;

import java.util.List;

/** Loads the current conversation members for the archive sender filter. */
public interface ArchiveSenderSource {

    Cancellable load(String conversationId, Callback callback);

    interface Cancellable {
        void cancel();
    }

    interface Callback {
        void onLoaded(List<ArchiveSender> senders);
    }
}
