package com.decibelmeter.app;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.List;

/**
 * 麦克风采集与声压计算。回调均在主线程触发。
 *
 * 采集线程每帧做两件事：
 * 1. RMS（均方根）-> dBFS，即整体声压级；
 * 2. 把同一帧交给 SpectrumAnalyzer 做 FFT，得到各倍频程频段电平（onSpectrum）。
 * 两者共用一次读缓冲，避免重复采集带来的额外延迟。
 */
public class NoiseMeter {

    public interface Listener {
        /**
         * @param dbfs  本帧整体声压，单位 dBFS（0 为满量程，越小越安静）
         * @param frames 本帧采样点数，用于计算时间常数
         */
        void onSample(double dbfs, int frames);

        /**
         * 频段电平，单位同为 dBFS，顺序与 SpectrumAnalyzer.BAND_CENTERS_HZ 一致。
         */
        void onSpectrum(double[] bandDbfs);

        void onFailed(String message);
    }

    static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FRAMES_PER_READ = 4096;

    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SpectrumAnalyzer spectrum = new SpectrumAnalyzer(FRAMES_PER_READ, SAMPLE_RATE);

    private Listener listener;
    private volatile AudioRecord record;
    private volatile Thread thread;
    private volatile boolean running;
    private volatile int activeSource = -1;

    public NoiseMeter(AudioManager audioManager) {
        this.audioManager = audioManager;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public static List<AudioDeviceInfo> listInputDevices(AudioManager am) {
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
        Arrays.sort(devices, (a, b) -> a.getType() - b.getType());
        return Arrays.asList(devices);
    }

    public boolean isRunning() {
        return running;
    }

    public int getActiveSource() {
        return activeSource;
    }

    public AudioDeviceInfo getRoutedDevice() {
        AudioRecord r = record;
        return r == null ? null : r.getRoutedDevice();
    }

    /**
     * 开始采集。preferred 为 null 时使用系统默认麦克风。
     */
    public boolean start(AudioDeviceInfo preferred) {
        stop();

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minBuffer <= 0) {
            minBuffer = FRAMES_PER_READ * 2;
        }
        int bufSize = Math.max(minBuffer, FRAMES_PER_READ * 2);

        // 优先 UNPROCESSED（绕过 AGC，测量更真实），失败退回 MIC。
        AudioRecord r = createRecord(MediaRecorder.AudioSource.UNPROCESSED, bufSize);
        if (r == null) {
            r = createRecord(MediaRecorder.AudioSource.MIC, bufSize);
        }
        if (r == null) {
            postFailed("AudioRecord init failed");
            return false;
        }

        if (preferred != null && !r.setPreferredDevice(preferred)) {
            postFailed("setPreferredDevice rejected");
        }

        try {
            r.startRecording();
        } catch (IllegalStateException e) {
            r.release();
            postFailed("startRecording failed");
            return false;
        }
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            r.release();
            postFailed("not recording");
            return false;
        }

        record = r;
        running = true;
        thread = new Thread(this::loop, "NoiseMeter");
        thread.start();
        return true;
    }

    private AudioRecord createRecord(int source, int bufSize) {
        try {
            AudioRecord r = new AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, bufSize);
            if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                activeSource = source;
                return r;
            }
            r.release();
        } catch (SecurityException denied) {
            // 麦克风权限在运行期被撤回时构造方法会抛此异常；调用方已拦一层，这里兜底返回失败。
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private void loop() {
        AudioRecord r = record;
        short[] buf = new short[FRAMES_PER_READ];
        while (running) {
            int n = r.read(buf, 0, buf.length);
            if (n <= 0) {
                if (running) {
                    postFailed("read error (" + n + ")");
                }
                break;
            }
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += (double) buf[i] * buf[i];
            }
            double rms = Math.sqrt(sum / n);
            double dbfs = rms > 0 ? 20 * Math.log10(rms / 32768.0) : Double.NEGATIVE_INFINITY;
            final double value = dbfs;
            final int frames = n;
            // FFT 在采集线程完成，避免占用主线程造成界面卡顿。
            final double[] bands = spectrum.analyzeDbfs(buf, n, dbfs);
            mainHandler.post(() -> {
                Listener l = listener;
                if (l != null) {
                    l.onSample(value, frames);
                    l.onSpectrum(bands);
                }
            });
        }
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try {
                if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    r.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            r.release();
        }
    }

    private void postFailed(String message) {
        mainHandler.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onFailed(message);
            }
        });
    }

    public static String sourceName(int source) {
        if (source == MediaRecorder.AudioSource.UNPROCESSED) {
            return "UNPROCESSED";
        }
        if (source == MediaRecorder.AudioSource.MIC) {
            return "MIC";
        }
        return "SRC" + source;
    }
}
