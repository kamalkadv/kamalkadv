package id.kioskos.lock;

import org.json.JSONException;
import org.json.JSONObject;

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

    public static final LockState NONE = new LockState(false, false, "", "", 0);

    public LockState(boolean enrolled, boolean locked, String message, String contact, long issuedAt) {
        this.enrolled = enrolled;
        this.locked = enrolled && locked;
        this.message = message == null ? "" : message;
        this.contact = contact == null ? "" : contact;
        this.issuedAt = issuedAt;
    }

    public JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("enrolled", enrolled)
                    .put("locked", locked)
                    .put("message", message)
                    .put("contact", contact)
                    .put("issued_at", issuedAt);
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
                o.optLong("issued_at", 0));
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
