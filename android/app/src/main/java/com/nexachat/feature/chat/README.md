# feature.chat — `package com.nexachat.feature.chat`

The chat feature is implemented in Java 17 with Android Fragments, XML layouts,
and ViewBinding. `ChatFragment` wires the message list and input bar;
`ChatViewModel` coordinates screen state with the repositories. RecyclerView
adapters render message rows, and Glide loads remote media. Repository and
realtime operations use RxJava 3 streams.
