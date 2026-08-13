package dev.paperreader.extensions.api;

import android.os.Bundle;
import dev.paperreader.extensions.api.IPaperSourceCallback;

interface IPaperSourceService {
    Bundle getDescriptor();
    oneway void search(in Bundle request, in IPaperSourceCallback callback);
    oneway void getPaper(in Bundle request, in IPaperSourceCallback callback);
    oneway void cancel(String requestId);
}
