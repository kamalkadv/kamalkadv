package id.kioskos.lock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pemilih Wi-Fi milik KioskLock, pengganti panel Wi-Fi Settings.
 *
 * Saat perangkat terkunci, hanya paket yang ada di daftar Lock Task yang boleh berjalan.
 * Panel Wi-Fi bawaan berasal dari com.android.settings, dan dari panel itu pengguna bisa
 * masuk ke Settings lengkap. Dengan layar sendiri, Settings tidak perlu diizinkan sama sekali.
 *
 * Memakai API Wi-Fi lama (WifiConfiguration + WifiManager.connect) yang masih diizinkan
 * untuk aplikasi sistem / Device Owner. Izin NETWORK_SETTINGS (signature, didapat karena
 * ditandatangani kunci platform) membuat pemindaian tidak butuh izin lokasi.
 */
public class WifiActivity extends Activity {
    private static final String TAG = "KioskLock.Wifi";

    private WifiManager mWifi;
    private TextView mStatus;
    private Button mToggle;
    private ListView mList;
    private NetworkAdapter mAdapter;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi);
        mWifi = getSystemService(WifiManager.class);
        mStatus = findViewById(R.id.wifi_status);
        mToggle = findViewById(R.id.btn_wifi_on);
        mList = findViewById(R.id.wifi_list);

        mAdapter = new NetworkAdapter(this);
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener((parent, view, position, id) ->
                onNetworkSelected(mAdapter.getItem(position)));

        mToggle.setOnClickListener(v -> {
            // Deprecated untuk aplikasi biasa, tetapi masih diizinkan untuk aplikasi sistem/DO.
            mWifi.setWifiEnabled(true);
            mStatus.setText(R.string.wifi_turning_on);
        });
        findViewById(R.id.btn_wifi_scan).setOnClickListener(v -> scan());
        findViewById(R.id.btn_wifi_hidden).setOnClickListener(v -> askHiddenNetwork());
        findViewById(R.id.btn_wifi_back).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Jika kunci sudah dibuka sementara layar ini terbuka, tidak ada alasan untuk tetap di sini.
        if (!LockController.get(this).currentState().locked) {
            finish();
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(mReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        refresh();
        scan();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(mReceiver);
        } catch (IllegalArgumentException ignored) {
            // Belum terdaftar (onResume langsung finish).
        }
        super.onPause();
    }

    private void scan() {
        if (mWifi.isWifiEnabled()) {
            mWifi.startScan();
            mStatus.setText(R.string.wifi_scanning);
        }
    }

    private void refresh() {
        boolean enabled = mWifi.isWifiEnabled();
        mToggle.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (!enabled) {
            mStatus.setText(R.string.wifi_off);
            mAdapter.setNetworks(new ArrayList<>());
            return;
        }
        WifiInfo info = mWifi.getConnectionInfo();
        String current = info == null ? null : stripQuotes(info.getSSID());
        if (current != null && info.getNetworkId() != -1
                && !WifiManager.UNKNOWN_SSID.equals(info.getSSID())) {
            mStatus.setText(getString(R.string.wifi_connected, current));
        } else {
            mStatus.setText(R.string.wifi_not_connected);
        }
        mAdapter.setCurrentSsid(current);
        mAdapter.setNetworks(dedupe(mWifi.getScanResults()));
    }

    /** Satu entri per SSID (sinyal terkuat), SSID kosong/tersembunyi dibuang. */
    private static List<Network> dedupe(List<ScanResult> results) {
        Map<String, Network> best = new HashMap<>();
        if (results != null) {
            for (ScanResult r : results) {
                String ssid = r.getWifiSsid() == null ? r.SSID : stripQuotes(r.getWifiSsid().toString());
                if (TextUtils.isEmpty(ssid)) continue;
                Network n = new Network(ssid, Security.from(r.capabilities), r.level);
                Network old = best.get(ssid);
                if (old == null || n.rssi > old.rssi) best.put(ssid, n);
            }
        }
        List<Network> list = new ArrayList<>(best.values());
        list.sort((a, b) -> Integer.compare(b.rssi, a.rssi));
        return list;
    }

    private void onNetworkSelected(Network n) {
        switch (n.security) {
            case OPEN:
                connect(n.ssid, Security.OPEN, null, false);
                break;
            case PSK:
            case SAE:
                askPassword(n.ssid, n.security, false);
                break;
            default:
                // EAP (WPA-Enterprise) dan WEP butuh UI lebih kompleks / tidak aman.
                new AlertDialog.Builder(this)
                        .setTitle(n.ssid)
                        .setMessage(R.string.wifi_unsupported)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
        }
    }

    private void askPassword(String ssid, Security security, boolean hidden) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.wifi_password);
        new AlertDialog.Builder(this)
                .setTitle(ssid)
                .setView(padded(input))
                .setPositiveButton(R.string.wifi_connect, (d, w) ->
                        connect(ssid, security, input.getText().toString(), hidden))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askHiddenNetwork() {
        EditText ssid = new EditText(this);
        ssid.setHint(R.string.wifi_ssid);
        ssid.setSingleLine();
        EditText pass = new EditText(this);
        pass.setHint(R.string.wifi_password_optional);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(ssid);
        box.addView(pass);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wifi_hidden)
                .setView(padded(box))
                .setPositiveButton(R.string.wifi_connect, (d, w) -> {
                    String name = ssid.getText().toString().trim();
                    String pw = pass.getText().toString();
                    if (name.isEmpty()) return;
                    connect(name, pw.isEmpty() ? Security.OPEN : Security.PSK, pw, true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void connect(String ssid, Security security, String password, boolean hidden) {
        if (security != Security.OPEN && (password == null || password.length() < 8)) {
            mStatus.setText(R.string.wifi_password_too_short);
            return;
        }
        WifiConfiguration cfg = new WifiConfiguration();
        cfg.SSID = quote(ssid);
        cfg.hiddenSSID = hidden;
        switch (security) {
            case OPEN:
                cfg.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
                break;
            case SAE:
                cfg.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
                cfg.preSharedKey = quote(password);
                break;
            default:
                cfg.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
                cfg.preSharedKey = quote(password);
        }
        mStatus.setText(getString(R.string.wifi_connecting, ssid));
        // @SystemApi, butuh NETWORK_SETTINGS. Menyimpan konfigurasi lalu langsung menyambung.
        mWifi.connect(cfg, new WifiManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "Menyambung ke " + ssid);
                // Begitu online, langsung cek status ke server.
                SyncJobService.syncNow(getApplicationContext());
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "Gagal menyambung ke " + ssid + ", alasan " + reason);
                runOnUiThread(() -> mStatus.setText(getString(R.string.wifi_failed, ssid)));
            }
        });
    }

    private View padded(View v) {
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String stripQuotes(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    enum Security {
        OPEN, PSK, SAE, UNSUPPORTED;

        static Security from(String capabilities) {
            String c = capabilities == null ? "" : capabilities;
            if (c.contains("EAP") || c.contains("WEP")) return UNSUPPORTED;
            if (c.contains("SAE") && !c.contains("PSK")) return SAE;
            if (c.contains("PSK") || c.contains("SAE")) return PSK; // transisi WPA2/WPA3 -> PSK
            return OPEN;
        }
    }

    static final class Network {
        final String ssid;
        final Security security;
        final int rssi;

        Network(String ssid, Security security, int rssi) {
            this.ssid = ssid;
            this.security = security;
            this.rssi = rssi;
        }
    }

    private static final class NetworkAdapter extends ArrayAdapter<Network> {
        private String mCurrent;

        NetworkAdapter(Context context) {
            super(context, R.layout.item_wifi, R.id.wifi_ssid);
        }

        void setCurrentSsid(String ssid) {
            mCurrent = ssid;
        }

        void setNetworks(List<Network> networks) {
            clear();
            addAll(networks);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = super.getView(position, convertView, parent);
            Network n = getItem(position);
            Context c = getContext();
            ((TextView) v.findViewById(R.id.wifi_ssid)).setText(n.ssid);
            // Berkata sandi ditandai ikon kunci; teks hanya menyebut kasus khusus.
            String line2 = c.getString(signalLabel(n.rssi));
            if (n.security == Security.OPEN) {
                line2 += " · " + c.getString(R.string.wifi_open);
            } else if (n.security == Security.UNSUPPORTED) {
                line2 += " · " + c.getString(R.string.wifi_unsupported_short);
            }
            boolean current = n.ssid.equals(mCurrent);
            if (current) line2 = c.getString(R.string.wifi_current) + " · " + line2;
            TextView detail = v.findViewById(R.id.wifi_detail);
            detail.setText(line2);
            detail.setTextColor(c.getColor(current ? R.color.kiosk_accent : R.color.kiosk_muted));

            // Ikon sinyal: makin lemah makin redup; jaringan tak didukung paling redup.
            ImageView icon = v.findViewById(R.id.wifi_icon);
            icon.setColorFilter(c.getColor(current ? R.color.kiosk_accent : R.color.kiosk_on_surface));
            icon.setImageAlpha(n.security == Security.UNSUPPORTED ? 90
                    : n.rssi >= -55 ? 255 : n.rssi >= -70 ? 200 : 140);
            v.findViewById(R.id.wifi_lock).setVisibility(
                    n.security == Security.OPEN ? View.INVISIBLE : View.VISIBLE);
            return v;
        }

        private static int signalLabel(int rssi) {
            if (rssi >= -55) return R.string.wifi_signal_strong;
            if (rssi >= -70) return R.string.wifi_signal_medium;
            return R.string.wifi_signal_weak;
        }
    }
}
