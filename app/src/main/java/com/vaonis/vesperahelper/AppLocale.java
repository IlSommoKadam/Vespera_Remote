package com.vaonis.vesperahelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/** Persists and applies IT / EN / ES UI language. */
public final class AppLocale {
    public static final String IT = "it";
    public static final String EN = "en";
    public static final String ES = "es";

    private static final String PREFS = "vespera_locale";
    private static final String KEY_LANG = "lang";

    private AppLocale() {}

    public static String getLanguage(Context context) {
        String lang = prefs(context).getString(KEY_LANG, IT);
        if (EN.equals(lang) || ES.equals(lang) || IT.equals(lang)) return lang;
        return IT;
    }

    public static void setLanguage(Context context, String language) {
        String lang = EN.equals(language) || ES.equals(language) ? language : IT;
        prefs(context).edit().putString(KEY_LANG, lang).apply();
    }

    public static Context wrap(Context context) {
        return apply(context, getLanguage(context));
    }

    public static Context apply(Context context, String language) {
        Locale locale = localeFor(language);
        Configuration current = context.getResources().getConfiguration();
        Locale currentLocale = current.getLocales().isEmpty()
                ? Locale.getDefault()
                : current.getLocales().get(0);
        if (locale.getLanguage().equals(currentLocale.getLanguage())) {
            return context;
        }
        Locale.setDefault(locale);
        Configuration config = new Configuration(current);
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }

    public static Locale localeFor(String language) {
        if (EN.equals(language)) return Locale.ENGLISH;
        if (ES.equals(language)) return new Locale("es");
        return Locale.ITALIAN;
    }

    public static int indexOf(String language) {
        if (EN.equals(language)) return 1;
        if (ES.equals(language)) return 2;
        return 0;
    }

    public static String languageAt(int index) {
        if (index == 1) return EN;
        if (index == 2) return ES;
        return IT;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
