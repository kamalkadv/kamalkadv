package id.kioskos.lock;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Mengambil status perangkat dari server.
 *
 * Protokol:
 *   GET {server}/api/v1/devices/status?serial=..&imei=..&nonce=..
 *   200 OK, body JSON:
 *     {"serial":"..","nonce":"..","enrolled":true,"locked":false,
 *      "message":"..","contact":"..","issued_at":1730000000}
 *   Header X-Signature: Base64(ECDSA-P256-SHA256(body))
 *
 * Respons yang tanda tangannya tidak valid, serial/nonce-nya tidak cocok, ditolak.
 */
public final class ServerClient {
    private static final String TAG = "KioskLock.Server";
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_BODY = 64 * 1024;

    private final Context mContext;

    public ServerClient(Context context) {
        mContext = context;
    }

    /** @return status terverifikasi, atau null jika gagal (jaringan / verifikasi). */
    public LockState fetch() {
        String serial = DeviceIdentity.serial();
        String nonce = newNonce();
        Uri uri = Uri.parse(mContext.getString(R.string.config_server_url)).buildUpon()
                .appendEncodedPath("api/v1/devices/status")
                .appendQueryParameter("serial", serial)
                .appendQueryParameter("imei", DeviceIdentity.imei(mContext))
                .appendQueryParameter("nonce", nonce)
                .build();

        // HTTP polos hanya untuk uji di emulator (build userdebug/eng). Build "user" wajib HTTPS.
        if (!"https".equals(uri.getScheme()) && !Build.IS_DEBUGGABLE) {
            Log.e(TAG, "URL server harus HTTPS di build produksi");
            return null;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(uri.toString()).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Server HTTP " + code);
                return null;
            }
            byte[] body = readAll(conn.getInputStream());
            String sig = conn.getHeaderField("X-Signature");
            if (sig == null || !verify(body, Base64.decode(sig, Base64.DEFAULT))) {
                Log.e(TAG, "Tanda tangan respons TIDAK valid, diabaikan");
                return null;
            }
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (!serial.equals(json.optString("serial")) || !nonce.equals(json.optString("nonce"))) {
                Log.e(TAG, "Serial/nonce tidak cocok, diabaikan");
                return null;
            }
            return LockState.fromJson(json);
        } catch (Exception e) {
            Log.w(TAG, "Gagal menghubungi server", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean verify(byte[] body, byte[] signature) throws Exception {
        byte[] keyBytes = Base64.decode(
                mContext.getString(R.string.config_server_public_key), Base64.DEFAULT);
        PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initVerify(key);
        s.update(body);
        return s.verify(signature);
    }

    private static String newNonce() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) throw new IOException("Respons terlalu besar");
            }
            return out.toByteArray();
        }
    }
}
