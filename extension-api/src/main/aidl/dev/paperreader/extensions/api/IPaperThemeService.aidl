package dev.paperreader.extensions.api;

import android.os.Bundle;
import dev.paperreader.extensions.api.IPaperThemeCallback;

interface IPaperThemeService {
    Bundle getDescriptor();
    oneway void getTheme(String requestId, String themeId, in IPaperThemeCallback callback);
    oneway void openIcon(String requestId, String themeId, String semanticKey, in IPaperThemeCallback callback);
    oneway void cancel(String requestId);
}
