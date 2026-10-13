package com.decibelmeter.app;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseActivity implements NoiseMeter.Listener {

    private static final String PREFS = "settings";
    private static final String KEY_OFFSET = "cal_offset";
    private static final String KEY_DEVICE_POS = "device_pos";
    private static final int REQ_MIC = 100;

    private NoiseMeter meter;
    private SharedPreferences prefs;

    private Spinner spinnerDevice;
    private Button btnToggle;
    private TextView tvValue, tvInfo, tvMax, tvMin, tvLeq, tvPeakBand;
    private MeterView meterView;
    private CurveView curveView;
    private SpectrumView spectrumView;

    private final List<AudioDeviceInfo> devices = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;

    private double smoothSpl = Double.NaN;
    private double maxSpl, minSpl, leqEnergySum;
    private int leqCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 标题显式取应用语言：清单里的 android:label 由系统按默认语言解析，
        // 会出现「正文中文、标题英文」的混排。
        setTitle(R.string.title_decibel);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        meter = new NoiseMeter(audioManager);
        meter.setListener(this);

        spinnerDevice = findViewById(R.id.spinner_device);
        btnToggle = findViewById(R.id.btn_toggle);
        tvValue = findViewById(R.id.tv_value);
        tvInfo = findViewById(R.id.tv_info);
        tvMax = findViewById(R.id.tv_max);
        tvMin = findViewById(R.id.tv_min);
        tvLeq = findViewById(R.id.tv_leq);
        tvPeakBand = findViewById(R.id.tv_peak_band);
        meterView = findViewById(R.id.meter);
        curveView = findViewById(R.id.curve);
        spectrumView = findViewById(R.id.spectrum);

        rebuildDeviceList(false);

        spinnerDevice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt(KEY_DEVICE_POS, position).apply();
                if (meter.isRunning()) {
                    startMeasurement();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            rebuildDeviceList(true);
            if (meter.isRunning()) {
                startMeasurement();
            }
        });

        btnToggle.setOnClickListener(v -> {
            if (!hasMicPermission()) {
                requestMicPermission();
                return;
            }
            if (meter.isRunning()) {
                stopMeasurement();
            } else {
                startMeasurement();
            }
        });

        findViewById(R.id.btn_calibrate).setOnClickListener(v -> showCalibrationDialog());
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestMicPermission() {
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startMeasurement();
        } else {
            Toast.makeText(this, R.string.need_mic_permission, Toast.LENGTH_LONG).show();
        }
    }

    private void rebuildDeviceList(boolean toastResult) {
        devices.clear();
        devices.addAll(NoiseMeter.listInputDevices(
                (AudioManager) getSystemService(AUDIO_SERVICE)));
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.device_default));
        for (AudioDeviceInfo d : devices) {
            labels.add(deviceLabel(d));
        }
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevice.setAdapter(deviceAdapter);

        int saved = prefs.getInt(KEY_DEVICE_POS, 0);
        if (saved > 0 && saved < labels.size()) {
            spinnerDevice.setSelection(saved);
        }
        if (toastResult) {
            Toast.makeText(this, getString(R.string.devices_found, labels.size() - 1),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String deviceLabel(AudioDeviceInfo d) {
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: type = getString(R.string.type_builtin_mic); break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: type = getString(R.string.type_wired_headset); break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: type = getString(R.string.type_wired_headphones); break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: type = getString(R.string.type_bluetooth_sco); break;
            case AudioDeviceInfo.TYPE_BLE_HEADSET: type = getString(R.string.type_ble_headset); break;
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: type = getString(R.string.type_ble_speaker); break;
            case AudioDeviceInfo.TYPE_USB_HEADSET: type = getString(R.string.type_usb_headset); break;
            case AudioDeviceInfo.TYPE_USB_DEVICE: type = getString(R.string.type_usb_device); break;
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: type = getString(R.string.type_usb_accessory); break;
            case AudioDeviceInfo.TYPE_FM_TUNER: type = getString(R.string.type_fm); break;
            case AudioDeviceInfo.TYPE_TV_TUNER: type = getString(R.string.type_tv); break;
            case AudioDeviceInfo.TYPE_TELEPHONY: type = getString(R.string.type_telephony); break;
            case AudioDeviceInfo.TYPE_AUX_LINE: type = getString(R.string.type_aux_line); break;
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX: type = getString(R.string.type_remote_submix); break;
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC: type = getString(R.string.type_hdmi); break;
            default: type = getString(R.string.type_unknown, d.getType()); break;
        }
        CharSequence product = d.getProductName();
        return TextUtils.isEmpty(product) ? type : type + " · " + product;
    }

    private AudioDeviceInfo selectedDevice() {
        int pos = spinnerDevice.getSelectedItemPosition();
        return pos <= 0 || pos > devices.size() ? null : devices.get(pos - 1);
    }

    private void startMeasurement() {
        meter.stop();
        resetStats();
        if (meter.start(selectedDevice())) {
            btnToggle.setText(R.string.stop);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            updateInfoLine();
        } else {
            Toast.makeText(this, R.string.meter_start_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void stopMeasurement() {
        meter.stop();
        btnToggle.setText(R.string.start);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateInfoLine();
    }

    private void resetStats() {
        smoothSpl = Double.NaN;
        maxSpl = Double.NEGATIVE_INFINITY;
        minSpl = Double.POSITIVE_INFINITY;
        leqEnergySum = 0;
        leqCount = 0;
        tvMax.setText("--");
        tvMin.setText("--");
        tvLeq.setText("--");
        tvPeakBand.setText(R.string.peak_band_none);
        curveView.clear();
        spectrumView.clear();
    }

    @Override
    public void onSample(double dbfs, int frames) {
        double offset = prefs.getFloat(KEY_OFFSET, 90f);
        double clamped = Math.max(-120.0, Math.min(0.0, dbfs));
        double spl = clamped + offset;

        double dt = frames / (double) NoiseMeter.SAMPLE_RATE;
        double alpha = 1.0 - Math.exp(-dt / 0.125);
        smoothSpl = Double.isNaN(smoothSpl) ? spl : smoothSpl + (spl - smoothSpl) * alpha;

        meterView.setLevel((float) smoothSpl);
        curveView.addPoint((float) spl, System.currentTimeMillis());
        tvValue.setText(formatDb(smoothSpl));

        if (spl > maxSpl) maxSpl = spl;
        if (spl < minSpl) minSpl = spl;
        leqEnergySum += Math.pow(10.0, spl / 10.0);
        leqCount++;
        tvMax.setText(formatDb(maxSpl));
        tvMin.setText(formatDb(minSpl));
        tvLeq.setText(formatDb(10.0 * Math.log10(leqEnergySum / leqCount)));

        tvInfo.setText(getString(R.string.info_format,
                formatDbfs(dbfs), NoiseMeter.SAMPLE_RATE,
                NoiseMeter.sourceName(meter.getActiveSource()),
                routedLabel()));
    }

    @Override
    public void onSpectrum(double[] bandDbfs) {
        // 频段 dBFS 与总 dBFS 同基准，共用同一个校准偏移。
        double offset = prefs.getFloat(KEY_OFFSET, 90f);
        double[] levels = new double[bandDbfs.length];
        double energySum = 0;
        for (int i = 0; i < levels.length; i++) {
            levels[i] = bandDbfs[i] + offset;
            energySum += Math.pow(10.0, levels[i] / 10.0);
        }
        spectrumView.setLevels(levels);

        int idx = spectrumView.dominantBandIndex();
        double share = energySum > 0
                ? Math.pow(10.0, levels[idx] / 10.0) / energySum * 100.0
                : 0;
        tvPeakBand.setText(getString(R.string.peak_band_format,
                bandLabel(SpectrumAnalyzer.BAND_CENTERS_HZ[idx]), (int) Math.round(share)));
    }

    /** 频段中心频率的可读写法：低于 1 kHz 用 Hz，以上用 kHz。 */
    private static String bandLabel(int hz) {
        return hz < 1000 ? hz + " Hz" : (hz / 1000) + " kHz";
    }

    @Override
    public void onFailed(String message) {
        Toast.makeText(this, getString(R.string.meter_error, message), Toast.LENGTH_LONG).show();
        if (!meter.isRunning()) {
            stopMeasurement();
        }
    }

    private String routedLabel() {
        AudioDeviceInfo routed = meter.getRoutedDevice();
        if (!meter.isRunning()) {
            return getString(R.string.not_running);
        }
        return routed == null ? getString(R.string.unknown) : deviceLabel(routed);
    }

    private void updateInfoLine() {
        tvInfo.setText(getString(R.string.info_format,
                "--", NoiseMeter.SAMPLE_RATE,
                getString(R.string.not_running), getString(R.string.not_running)));
    }

    private static String formatDb(double db) {
        if (Double.isNaN(db) || Double.isInfinite(db)) {
            return "--";
        }
        return String.format(java.util.Locale.US, "%.1f", db);
    }

    private static String formatDbfs(double dbfs) {
        if (Double.isNaN(dbfs) || Double.isInfinite(dbfs)) {
            return "-inf";
        }
        return String.format(java.util.Locale.US, "%.1f", dbfs);
    }

    private void showCalibrationDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, 0);

        TextView message = new TextView(this);
        float current = prefs.getFloat(KEY_OFFSET, 90f);
        message.setText(getString(R.string.cal_current, current));
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(120);
        seekBar.setProgress((int) (current - 30f));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                message.setText(getString(R.string.cal_current, 30f + progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        container.addView(message);
        container.addView(seekBar);

        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.cal_title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        prefs.edit().putFloat(KEY_OFFSET, 30f + seekBar.getProgress()).apply())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (meter.isRunning()) {
            stopMeasurement();
        }
    }
}
