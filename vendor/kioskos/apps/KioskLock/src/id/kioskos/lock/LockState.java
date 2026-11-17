package id.kioskos.lock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Status kunci perangkat. Disimpan di partisi FRP agar selamat dari factory reset. */
public final class LockState {
    /** Perangkat terdaftar di server (mis. masih ada cicilan). */
    public final boolean enrolled;
    /** Perangkat sedang dikunci (kiosk). */
    public final boolean locked;
    public final String message;
    public final String contact;
    /** Waktu server (epoch detik) saat status diterbitkan, untuk mencegah replay status lama. */
    public final long issuedAt;
    /** Paket aplikasi yang tetap boleh dibuka saat terkunci (mis. WhatsApp, aplikasi bank). */
    public final List<String> allowedApps;

    public static final LockState NONE =
            new LockState(false, false, "", "", 0, Collections.emptyList());

    public LockState(boolean enrolled, boolean locked, String message, String contact,
            long issuedAt, List<String> allowedApps) {
        this.enrolled = enrolled;
        this.locked = enrolled && locked;
        this.message = message == null ? "" : message;
        this.contact = contact == null ? "" : contact;
        this.issuedAt = issuedAt;
        this.allowedApps = Collections.unmodifiableList(
                allowedApps == null ? new ArrayList<>() : new ArrayList<>(allowedApps));
    }

    public JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("enrolled", enrolled)
                    .put("locked", locked)
                    .put("message", message)
                    .put("contact", contact)
                    .put("issued_at", issuedAt)
                    .put("allowed_apps", new JSONArray(allowedApps));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static LockState fromJson(JSONObject o) {
        return new LockState(
                o.optBoolean("enrolled", false),
                o.optBoolean("locked", false),
                o.optString("message", ""),
                o.optString("contact", ""),
                o.optLong("issued_at", 0),
                parseApps(o.optJSONArray("allowed_apps")));
    }

    private static List<String> parseApps(JSONArray a) {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length() && out.size() < 12; i++) {
            String pkg = a.optString(i, "");
            if (!pkg.isEmpty() && !out.contains(pkg)) out.add(pkg);
        }
        return out;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
