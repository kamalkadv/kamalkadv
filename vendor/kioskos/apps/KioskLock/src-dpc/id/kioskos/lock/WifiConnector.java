package id.kioskos.lock;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

/**
 * Edisi Device Owner: addNetwork + enableNetwork. Sejak Android 10 keduanya selalu gagal untuk
 * aplikasi biasa, tetapi masih diizinkan untuk Device Owner.
 */
final class WifiConnector {
    interface Callback {
        void onSuccess();

        void onFailure(int reason);
    }

    private WifiConnector() {}

    @SuppressWarnings("deprecation")
    static void connect(WifiManager wifi, WifiConfiguration cfg, Callback cb) {
        int id = wifi.addNetwork(cfg);
        if (id == -1) {
            cb.onFailure(-1);
        } else if (wifi.enableNetwork(id, /* attemptConnect= */ true)) {
            cb.onSuccess();
        } else {
            cb.onFailure(-2);
        }
    }
}
