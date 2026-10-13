package com.decibelmeter.app;

/**
 * 声音频段分析器：把一段麦克风采样转换成各倍频程频段（63 Hz ~ 16 kHz）的声压级。
 *
 * 原理简述（入门）：
 * 1. 时域采样经过 FFT（快速傅里叶变换）拆成一个个频率成分，频率分辨率 = 采样率 / FFT 点数。
 *    本工程 48000 Hz 采样、4096 点 FFT，分辨率约 11.7 Hz，最高可表示 24000 Hz（奈奎斯特频率）。
 * 2. 做 FFT 前先乘 Hann 窗（两端渐变为 0 的曲线）。直接把音频截断成一段会产生"频谱泄漏"，
 *    即某个频率的能量扩散到邻近频点上；加窗可以显著抑制这种扩散。
 * 3. 把频点按声学上的"1/1 倍频程"分组：每个频段上下边界是中心频率的 2^(±1/6) 倍，
 *    也就是每个频段的上边界约为下边界的 2 倍（一个八度），符合人耳对音高的感受。
 * 4. 频段声级用"能量占比"换算，保证与总分贝自洽：
 *    频段 dBFS = 总 dBFS + 10 * log10(该频段能量 / 所有频段能量之和)。
 *    这样所有频段加起来正好等于当前测量的总声压，不会出现"频段之和比总量还大"的矛盾。
 */
class SpectrumAnalyzer {

    /** 1/1 倍频程标准中心频率，界面柱状条与文字标签都按此顺序显示。 */
    static final int[] BAND_CENTERS_HZ = {63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    /** 一个倍频程的 1/6 次方，用于由中心频率求频段上下边界。 */
    private static final double OCTAVE_HALF = Math.pow(2.0, 1.0 / 6.0);

    private final int size;
    private final double[] re;
    private final double[] im;
    private final double[] window;
    private final double[] binEnergy;
    private final int[] bandLowBin;
    private final int[] bandHighBin;

    /**
     * @param fftSize     FFT 点数，必须是 2 的整数次幂（如 4096）
     * @param sampleRate  采样率，用于把频点序号换算成真实频率
     */
    SpectrumAnalyzer(int fftSize, int sampleRate) {
        if ((fftSize & (fftSize - 1)) != 0) {
            throw new IllegalArgumentException("fftSize must be a power of two");
        }
        size = fftSize;
        re = new double[size];
        im = new double[size];
        window = new double[size];
        binEnergy = new double[size / 2];

        for (int i = 0; i < size; i++) {
            // 升余弦（Hann）窗：中间为 1，两端平滑降到 0。
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1));
        }

        // 预计算每个频段覆盖的频点区间，避免每次分析都重复换算频率。
        // 频点 k 对应的频率 = k * sampleRate / size。
        int usableBins = size / 2 - 1; // 去掉 DC（k=0）与奈奎斯特点（k=size/2）
        bandLowBin = new int[BAND_CENTERS_HZ.length];
        bandHighBin = new int[BAND_CENTERS_HZ.length];
        for (int b = 0; b < BAND_CENTERS_HZ.length; b++) {
            double center = BAND_CENTERS_HZ[b];
            double low = center / OCTAVE_HALF;   // 下边界 = 中心频率 * 2^(-1/6)
            double high = center * OCTAVE_HALF;  // 上边界 = 中心频率 * 2^(+1/6)
            bandLowBin[b] = clampBin((int) Math.floor(low * size / (double) sampleRate), usableBins);
            bandHighBin[b] = clampBin((int) Math.ceil(high * size / (double) sampleRate), usableBins);
        }
    }

    private static int clampBin(int bin, int maxBin) {
        return Math.max(1, Math.min(maxBin, bin));
    }

    /**
     * 分析一段采样，返回每个频段的 dBFS（与 NoiseMeter 的总 dBFS 同一基准，可共用校准偏移）。
     *
     * @param samples    麦克风原始 PCM（16 bit）
     * @param length     本次实际读到的采样个数，不足 FFT 点数时尾部补零
     * @param totalDbfs  这段采样的总体 dBFS，用于按能量占比换算频段电平
     * @return 长度等于 BAND_CENTERS_HZ 的频段 dBFS 数组，无信号时为 -120
     */
    double[] analyzeDbfs(short[] samples, int length, double totalDbfs) {
        double[] bands = new double[BAND_CENTERS_HZ.length];

        int count = Math.min(length, size);
        for (int i = 0; i < count; i++) {
            re[i] = samples[i] * window[i]; // 加窗后填入 FFT 实部，虚部保持 0
            im[i] = 0;
        }
        for (int i = count; i < size; i++) {
            re[i] = 0;
            im[i] = 0;
        }

        transform(re, im);

        double total = 0;
        for (int k = 1; k < size / 2; k++) {
            // |X(k)|^2 即该频点能量；实信号频谱共轭对称，只统计前一半即可。
            binEnergy[k - 1] = re[k] * re[k] + im[k] * im[k];
            total += binEnergy[k - 1];
        }
        if (total <= 0 || Double.isInfinite(totalDbfs)) {
            java.util.Arrays.fill(bands, -120.0);
            return bands;
        }

        double sum = 0;
        for (int b = 0; b < bands.length; b++) {
            double energy = 0;
            for (int k = bandLowBin[b]; k <= bandHighBin[b]; k++) {
                energy += binEnergy[k - 1];
            }
            bands[b] = energy;
            sum += energy;
        }
        if (sum <= 0) {
            java.util.Arrays.fill(bands, -120.0);
            return bands;
        }

        // 频段占比 -> dB，再叠加到总 dBFS 上，保证频段能量与总能量一致。
        for (int b = 0; b < bands.length; b++) {
            double fraction = bands[b] / sum;
            bands[b] = fraction > 0
                    ? totalDbfs + 10 * Math.log10(fraction)
                    : -120.0;
        }
        return bands;
    }

    /**
     * 就地迭代式 radix-2 快速傅里叶变换（Cooley-Tukey）。
     * 先用位反转把数据重排成蝶形运算需要的顺序，再自下而上逐级做蝶形合并。
     *
     * @param re 实部数组，长度为 2 的幂
     * @param im 虚部数组，长度与 re 相同
     */
    private void transform(double[] re, double[] im) {
        int n = re.length;

        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            // 每一级用一个单位根（旋转因子）w = e^(-2πi/len)，跨度为 len/2 的蝶形配对运算。
            double angle = -2 * Math.PI / len;
            double wRe = Math.cos(angle), wIm = Math.sin(angle);
            for (int start = 0; start < n; start += len) {
                double curRe = 1, curIm = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = start + k, b = start + k + len / 2;
                    double xRe = re[b] * curRe - im[b] * curIm;
                    double xIm = re[b] * curIm + im[b] * curRe;
                    re[b] = re[a] - xRe;
                    im[b] = im[a] - xIm;
                    re[a] += xRe;
                    im[a] += xIm;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = nextRe;
                }
            }
        }
    }
}
