package id.kioskos.common;

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
import java.util.Map;

/**
 * Mengambil JSON dari server KioskOS dan memverifikasinya.
 *
 * Setiap request diberi nonce acak. Respons hanya diterima jika:
 *   - header X-Signature = ECDSA-P256-SHA256 yang valid atas body, dengan public key bawaan ROM;
 *   - field "nonce" di body sama dengan nonce request (mencegah replay respons lama);
 *   - field "serial" (jika dikirim) sama dengan serial request.
 *
 * HTTP polos hanya diizinkan di build non-"user" (userdebug/eng) untuk pengujian.
 */
public final class SignedJsonClient {
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_BODY = 64 * 1024;

    private final String mBaseUrl;
    private final String mPublicKeyBase64;
    private final String mTag;

    public SignedJsonClient(String baseUrl, String publicKeyBase64, String logTag) {
        mBaseUrl = baseUrl;
        mPublicKeyBase64 = publicKeyBase64;
        mTag = logTag;
    }

    /** @return JSON terverifikasi, atau null jika gagal (jaringan / verifikasi). */
    public JSONObject get(String path, Map<String, String> params) {
        String nonce = newNonce();
        Uri.Builder b = Uri.parse(mBaseUrl).buildUpon().appendEncodedPath(path);
        for (Map.Entry<String, String> e : params.entrySet()) {
            b.appendQueryParameter(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        Uri uri = b.appendQueryParameter("nonce", nonce).build();

        if (!"https".equals(uri.getScheme()) && "user".equals(Build.TYPE)) {
            Log.e(mTag, "URL server harus HTTPS di build produksi");
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
                Log.w(mTag, "Server HTTP " + code);
                return null;
            }
            byte[] body = readAll(conn.getInputStream());
            String sig = conn.getHeaderField("X-Signature");
            if (sig == null || !verify(body, Base64.decode(sig, Base64.DEFAULT))) {
                Log.e(mTag, "Tanda tangan respons TIDAK valid, diabaikan");
                return null;
            }
            JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (!nonce.equals(json.optString("nonce"))) {
                Log.e(mTag, "Nonce tidak cocok, diabaikan");
                return null;
            }
            String serial = params.get("serial");
            if (serial != null && !serial.equals(json.optString("serial"))) {
                Log.e(mTag, "Serial tidak cocok, diabaikan");
                return null;
            }
            return json;
        } catch (Exception e) {
            Log.w(mTag, "Gagal menghubungi server", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean verify(byte[] body, byte[] signature) throws Exception {
        byte[] keyBytes = Base64.decode(mPublicKeyBase64, Base64.DEFAULT);
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));
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
