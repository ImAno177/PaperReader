package dev.paperreader.extensions.api;

import android.os.Bundle;

oneway interface IPaperReadableDocumentCallback {
    void onChunk(in Bundle chunk);
    void onComplete(in Bundle metadata);
    void onFailure(in Bundle failure);
}
