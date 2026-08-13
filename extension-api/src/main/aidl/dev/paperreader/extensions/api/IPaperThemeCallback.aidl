package dev.paperreader.extensions.api;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

oneway interface IPaperThemeCallback {
    void onTheme(in Bundle theme);
    void onIcon(String requestId, in ParcelFileDescriptor icon);
    void onFailure(in Bundle failure);
}
