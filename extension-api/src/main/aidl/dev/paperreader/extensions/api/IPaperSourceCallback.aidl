package dev.paperreader.extensions.api;

import android.os.Bundle;

oneway interface IPaperSourceCallback {
    void onSuccess(in Bundle response);
    void onFailure(in Bundle failure);
}
