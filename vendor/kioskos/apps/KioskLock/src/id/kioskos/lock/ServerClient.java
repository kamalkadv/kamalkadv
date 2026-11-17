package id.kioskos.lock;

import android.content.Context;

import id.kioskos.common.SignedJsonClient;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mengambil status kunci perangkat dari server.
 *
 *   GET {server}/api/v1/devices/status?serial=..&imei=..&nonce=..
 *   -> {"serial","nonce","enrolled","locked","message","contact","issued_at"} + X-Signature
 *
 * Verifikasi tanda tangan, nonce, dan serial dilakukan oleh {@link SignedJsonClient}.
 */
public final class ServerClient {
    private static final String TAG = "KioskLock.Server";

    private final Context mContext;

    public ServerClient(Context context) {
        mContext = context;
    }

    /** @return status terverifikasi, atau null jika gagal (jaringan / verifikasi). */
    public LockState fetch() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("serial", DeviceIdentity.serial());
        params.put("imei", DeviceIdentity.imei(mContext));
        JSONObject json = new SignedJsonClient(
                mContext.getString(R.string.config_server_url),
                mContext.getString(R.string.config_server_public_key),
                TAG).get("api/v1/devices/status", params);
        return json == null ? null : LockState.fromJson(json);
    }
}
