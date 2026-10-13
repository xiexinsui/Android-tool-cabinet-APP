package com.decibelmeter.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * 应用内语言切换：auto=跟随系统，zh=简体中文，en=English。
 */
public final class LocaleHelper {

    static final String KEY_LANG = "lang";

    private LocaleHelper() {
    }

    public static String current(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(KEY_LANG, "auto");
    }

    public static void save(Context context, String lang) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putString(KEY_LANG, lang).apply();
    }

    public static Context wrap(Context base) {
        String lang = current(base);
        if (lang.equals("auto")) {
            return base;
        }
        Locale locale = Locale.forLanguageTag(lang.equals("zh") ? "zh-CN" : "en");
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(locale);
        return base.createConfigurationContext(cfg);
    }
}
