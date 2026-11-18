package id.kioskos.lock;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

/** ROM AOSP: WifiManager.connect (@SystemApi, butuh NETWORK_SETTINGS dari kunci platform). */
final class WifiConnector {
    interface Callback {
        void onSuccess();

        void onFailure(int reason);
    }

    private WifiConnector() {}

    static void connect(WifiManager wifi, WifiConfiguration cfg, Callback cb) {
        wifi.connect(cfg, new WifiManager.ActionListener() {
            @Override
            public void onSuccess() {
                cb.onSuccess();
            }

            @Override
            public void onFailure(int reason) {
                cb.onFailure(reason);
            }
        });
    }
}
