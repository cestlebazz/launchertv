package com.bastien.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Écran d'accueil minimaliste pour Android TV / Google TV.
 * Heure, météo, prochain match de l'OM et une rangée d'applis.
 */
public class MainActivity extends Activity {

    // ---------- Réglages faciles à modifier ----------
    private static final String DEFAULT_CITY = "Marseille";
    private static final String TEAM_ID = "176"; // Olympique de Marseille chez ESPN
    private static final String[][] COMPETITIONS = {
            {"fra.1", "Ligue 1"},
            {"uefa.champions", "Ligue des champions"},
            {"uefa.europa", "Ligue Europa"},
            {"uefa.europa.conf", "Ligue Conférence"},
            {"fra.coupe_de_france", "Coupe de France"},
    };
    private static final int BG_TOP = Color.parseColor("#15161A");
    private static final int BG_BOTTOM = Color.parseColor("#000000");
    private static final int TILE_BG = Color.parseColor("#1C1C1E");
    private static final int TEXT_DIM = Color.parseColor("#8E8E93");
    private static final int ACCENT_LIVE = Color.parseColor("#FF453A");
    // --------------------------------------------------

    private static final String PREFS = "launcher";
    private static final String KEY_ORDER = "order";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_CITY = "city";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";
    private static final String KEY_SHOW_ALL = "show_all";
    private static final String KEY_WALLPAPER_OFF = "wallpaper_off";

    /**
     * Photo de Hong Kong la nuit (image « featured » de Wikimedia Commons, licence libre — crédit sur la page du fichier).
     * Pour changer de fond, remplace ce lien par celui d'une autre image.
     */
    private static final String WALLPAPER_URL =
            "https://commons.wikimedia.org/wiki/Special:FilePath/Hong_Kong_Night_Skyline.jpg?width=1920";
    private ImageView wallpaperView;

    private void loadWallpaper() {
        if (prefs.getBoolean(KEY_WALLPAPER_OFF, false)) {
            wallpaperView.setImageDrawable(null);
            return;
        }
        final java.io.File cache = new java.io.File(getFilesDir(),
                "wallpaper_" + Integer.toHexString(WALLPAPER_URL.hashCode()) + ".jpg");
        io.execute(() -> {
            try {
                if (!cache.exists() || cache.length() == 0) download(WALLPAPER_URL, cache);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(cache.getPath(), o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= 1920) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                o2.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap bmp = BitmapFactory.decodeFile(cache.getPath(), o2);
                if (bmp == null) {
                    cache.delete();
                    return;
                }
                main.post(() -> {
                    if (!prefs.getBoolean(KEY_WALLPAPER_OFF, false)) {
                        wallpaperView.setAlpha(0f);
                        wallpaperView.setImageBitmap(bmp);
                        wallpaperView.animate().alpha(1f).setDuration(600).start();
                    }
                });
            } catch (Exception ignored) {
                cache.delete();
            }
        });
    }

    private static void download(String url, java.io.File dest) throws Exception {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "LauncherTV/1.0 (personal Android TV launcher)");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new Exception("redirection vide");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code >= 400) {
                conn.disconnect();
                throw new Exception("HTTP " + code);
            }
            java.io.File tmp = new java.io.File(dest.getPath() + ".part");
            InputStream in = conn.getInputStream();
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            conn.disconnect();
            if (!tmp.renameTo(dest)) throw new Exception("écriture impossible");
            return;
        }
        throw new Exception("trop de redirections");
    }

    /**
     * Les seules applis affichées, dans cet ordre.
     * Chaque ligne : identifiants Android connus et/ou noms (en minuscules, sans espaces ni symboles).
     */
    private static final String[][] FAVORITES = {
            {"com.canal.android.canal", "mycanal", "canal", "canalplus"},
            {"com.google.android.youtube.tv", "youtube"},
            {"com.netflix.ninja", "netflix"},
            {"com.amazon.amazonvideo.livingroom", "primevideo", "amazonprimevideo", "prime"},
            {"com.spotify.tv.android", "spotify"},
            {"ligue1", "ligue1plus"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Map<String, Bitmap> logoCache = new java.util.concurrent.ConcurrentHashMap<>();

    private SharedPreferences prefs;
    private LinearLayout appRow;
    private TextView focusedLabel;
    private TextView weatherTemp, weatherDesc, weatherCity;
    private LinearLayout matchCard;
    private TextView matchComp, matchHome, matchAway, matchScore, matchWhen;
    private ImageView matchHomeLogo, matchAwayLogo;
    private final List<AppEntry> apps = new ArrayList<>();
    private String lastFocusedPkg;
    private boolean matchLive = false;

    private static class AppEntry {
        String pkg;
        String label;
        Intent launch;
        Drawable banner;
        Drawable icon;
    }

    // ================================================================
    // Cycle de vie
    // ================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        loadWallpaper();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        loadApps();
        refreshWeather();
        refreshMatch();
        main.removeCallbacks(periodic);
        main.postDelayed(periodic, 60_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(periodic);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // C'est l'écran d'accueil : le bouton Retour ne ferme rien.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private int minutesSinceWeather = 0;
    private int minutesSinceMatch = 0;

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            minutesSinceWeather++;
            minutesSinceMatch++;
            if (minutesSinceWeather >= 30) {
                minutesSinceWeather = 0;
                refreshWeather();
            }
            if (minutesSinceMatch >= (matchLive ? 1 : 15)) {
                minutesSinceMatch = 0;
                refreshMatch();
            }
            main.postDelayed(this, 60_000);
        }
    };

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ================================================================
    // Construction de l'interface
    // ================================================================

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM});
        root.setBackground(bg);

        // Fond d'écran photo + voile sombre pour garder le texte lisible
        wallpaperView = new ImageView(this);
        wallpaperView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaperView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View veilV = new View(this);
        veilV.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#99000000"), Color.parseColor("#4D000000"), Color.parseColor("#E6000000")}));
        root.addView(veilV, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View veilH = new View(this);
        veilH.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#80000000"), Color.TRANSPARENT, Color.parseColor("#66000000")}));
        root.addView(veilH, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(64), dp(40), dp(64), dp(36));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Ligne du haut : heure à gauche, météo à droite ---
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        styleText(time, 64, Color.WHITE, "sans-serif-thin");
        time.setIncludeFontPadding(false);
        clockBox.addView(time);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        styleText(date, 20, TEXT_DIM, "sans-serif-light");
        clockBox.addView(date);
        top.addView(clockBox);

        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        LinearLayout weatherBox = new LinearLayout(this);
        weatherBox.setOrientation(LinearLayout.VERTICAL);
        weatherBox.setGravity(Gravity.END);
        weatherTemp = new TextView(this);
        styleText(weatherTemp, 50, Color.WHITE, "sans-serif-thin");
        weatherTemp.setIncludeFontPadding(false);
        weatherTemp.setText("--°");
        weatherDesc = new TextView(this);
        styleText(weatherDesc, 18, Color.WHITE, "sans-serif-light");
        weatherCity = new TextView(this);
        styleText(weatherCity, 15, TEXT_DIM, "sans-serif");
        weatherCity.setText(prefs.getString(KEY_CITY, DEFAULT_CITY));
        weatherBox.addView(weatherTemp);
        weatherBox.addView(weatherDesc);
        weatherBox.addView(weatherCity);
        for (int i = 0; i < weatherBox.getChildCount(); i++) {
            ((TextView) weatherBox.getChildAt(i)).setGravity(Gravity.END);
        }
        top.addView(weatherBox);

        // --- Carte du prochain match ---
        matchCard = new LinearLayout(this);
        matchCard.setOrientation(LinearLayout.VERTICAL);
        matchCard.setPadding(dp(28), dp(20), dp(28), dp(22));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#66000000"));
        cardBg.setStroke(dp(1), Color.parseColor("#26FFFFFF"));
        cardBg.setCornerRadius(dp(20));
        matchCard.setBackground(cardBg);
        matchCard.setVisibility(View.INVISIBLE);

        matchComp = new TextView(this);
        styleText(matchComp, 13, TEXT_DIM, "sans-serif-medium");
        matchComp.setLetterSpacing(0.12f);
        matchCard.addView(matchComp);

        LinearLayout teams = new LinearLayout(this);
        teams.setOrientation(LinearLayout.HORIZONTAL);
        teams.setGravity(Gravity.CENTER_VERTICAL);
        teams.setPadding(0, dp(12), 0, dp(10));
        matchHomeLogo = logoView();
        matchHome = new TextView(this);
        styleText(matchHome, 26, Color.WHITE, "sans-serif-light");
        matchScore = new TextView(this);
        styleText(matchScore, 26, TEXT_DIM, "sans-serif-light");
        matchScore.setPadding(dp(18), 0, dp(18), 0);
        matchAway = new TextView(this);
        styleText(matchAway, 26, Color.WHITE, "sans-serif-light");
        matchAwayLogo = logoView();
        teams.addView(matchHomeLogo);
        teams.addView(matchHome);
        teams.addView(matchScore);
        teams.addView(matchAway);
        teams.addView(matchAwayLogo);
        matchCard.addView(teams);

        matchWhen = new TextView(this);
        styleText(matchWhen, 16, TEXT_DIM, "sans-serif");
        matchCard.addView(matchWhen);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(28);
        column.addView(matchCard, cardLp);

        View grow = new View(this);
        column.addView(grow, new LinearLayout.LayoutParams(1, 0, 1f));

        // --- Nom de l'appli sélectionnée ---
        focusedLabel = new TextView(this);
        styleText(focusedLabel, 18, Color.WHITE, "sans-serif");
        focusedLabel.setPadding(dp(4), 0, 0, dp(14));
        column.addView(focusedLabel);

        // --- Rangée d'applis ---
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setSmoothScrollingEnabled(true);
        appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setClipChildren(false);
        appRow.setClipToPadding(false);
        appRow.setPadding(dp(4), dp(12), dp(64), dp(12));
        scroller.addView(appRow);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.leftMargin = -dp(4);
        column.addView(scroller, rowLp);

        return root;
    }

    private ImageView logoView() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        iv.setLayoutParams(lp);
        return iv;
    }

    private void styleText(TextView tv, float sp, int color, String family) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create(family, Typeface.NORMAL));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ================================================================
    // Applis
    // ================================================================

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> found = new HashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
        if (found.isEmpty()) collect(pm, Intent.CATEGORY_LAUNCHER, found);

        Set<String> hidden = prefs.getStringSet(KEY_HIDDEN, new HashSet<String>());
        List<String> order = getOrder();

        if (!prefs.getBoolean(KEY_SHOW_ALL, false)) {
            // Mode « mes applis » : on ne garde que les favoris, dans l'ordre défini.
            Map<String, AppEntry> favs = new HashMap<>();
            List<String> favOrder = new ArrayList<>();
            for (String[] fav : FAVORITES) {
                for (AppEntry e : found.values()) {
                    if (favs.containsKey(e.pkg)) continue;
                    if (matchesFavorite(e, fav)) {
                        favs.put(e.pkg, e);
                        favOrder.add(e.pkg);
                        break;
                    }
                }
            }
            found = favs;
            if (order.isEmpty()) order = favOrder;
        }

        apps.clear();
        for (String pkg : order) {
            AppEntry e = found.remove(pkg);
            if (e != null && !hidden.contains(pkg)) apps.add(e);
        }
        List<AppEntry> rest = new ArrayList<>();
        for (AppEntry e : found.values()) {
            if (!hidden.contains(e.pkg)) rest.add(e);
        }
        Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));
        apps.addAll(rest);
        renderApps();
    }

    private static String normalize(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return n.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean matchesFavorite(AppEntry e, String[] fav) {
        String label = normalize(e.label);
        for (String key : fav) {
            if (key.contains(".")) {
                if (key.equals(e.pkg)) return true;
            } else if (label.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private void collect(PackageManager pm, String category, Map<String, AppEntry> out) {
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(category);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = String.valueOf(ri.loadLabel(pm));
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(category);
            launch.setClassName(pkg, ri.activityInfo.name);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            e.launch = launch;
            try {
                e.banner = ri.activityInfo.loadBanner(pm);
                if (e.banner == null) {
                    ApplicationInfo ai = ri.activityInfo.applicationInfo;
                    e.banner = ai.loadBanner(pm);
                }
            } catch (Exception ignored) {
            }
            e.icon = ri.loadIcon(pm);
            out.put(pkg, e);
        }
    }

    private void renderApps() {
        appRow.removeAllViews();
        View toFocus = null;
        for (int i = 0; i < apps.size(); i++) {
            final AppEntry e = apps.get(i);
            final int index = i;
            View tile = makeTile(e.banner, e.icon, e.label);
            tile.setOnClickListener(v -> launch(e));
            tile.setOnLongClickListener(v -> {
                showAppMenu(index);
                return true;
            });
            tile.setOnFocusChangeListener((v, has) -> onTileFocus(v, has, e.label, e.pkg));
            appRow.addView(tile);
            if (toFocus == null || e.pkg.equals(lastFocusedPkg)) {
                if (toFocus == null || e.pkg.equals(lastFocusedPkg)) toFocus = tile;
            }
        }
        View settings = makeTile(null, null, "Réglages");
        settings.setOnClickListener(v -> showSettingsMenu());
        settings.setOnFocusChangeListener((v, has) -> onTileFocus(v, has, "Réglages", null));
        appRow.addView(settings);
        if (toFocus == null) toFocus = settings;
        final View f = toFocus;
        appRow.post(f::requestFocus);
    }

    private View makeTile(Drawable banner, Drawable icon, String label) {
        FrameLayout tile = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(192), dp(108));
        lp.rightMargin = dp(22);
        tile.setLayoutParams(lp);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(TILE_BG);
        bg.setCornerRadius(dp(16));
        tile.setBackground(bg);
        tile.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        tile.setClipToOutline(true);

        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            tile.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(icon);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER);
            tile.addView(iv, ilp);
        } else {
            TextView tv = new TextView(this);
            styleText(tv, 18, Color.WHITE, "sans-serif-light");
            tv.setText(label);
            tv.setGravity(Gravity.CENTER);
            tile.addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(dp(16));
        ring.setStroke(dp(3), Color.WHITE);
        ring.setAlpha(0);
        tile.setForeground(ring);
        tile.setTag(ring);
        return tile;
    }

    private void onTileFocus(View v, boolean has, String label, String pkg) {
        float s = has ? 1.12f : 1f;
        v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(12) : 0).setDuration(160).start();
        Object tag = v.getTag();
        if (tag instanceof GradientDrawable) ((GradientDrawable) tag).setAlpha(has ? 255 : 0);
        if (has) {
            focusedLabel.setText(label);
            if (pkg != null) lastFocusedPkg = pkg;
        }
    }

    private void launch(AppEntry e) {
        try {
            startActivity(e.launch);
        } catch (ActivityNotFoundException ex) {
            Intent alt = getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg);
            if (alt == null) alt = getPackageManager().getLaunchIntentForPackage(e.pkg);
            if (alt != null) startActivity(alt);
            else Toast.makeText(this, "Impossible d'ouvrir " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> getOrder() {
        String raw = prefs.getString(KEY_ORDER, "");
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(raw)) Collections.addAll(out, raw.split(","));
        return out;
    }

    private void saveCurrentOrder() {
        List<String> pkgs = new ArrayList<>();
        for (AppEntry e : apps) pkgs.add(e.pkg);
        prefs.edit().putString(KEY_ORDER, TextUtils.join(",", pkgs)).apply();
    }

    private void showAppMenu(final int index) {
        final AppEntry e = apps.get(index);
        String[] items = {"Placer en premier", "Déplacer à gauche", "Déplacer à droite", "Masquer cette appli"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(e.label)
                .setItems(items, (d, which) -> {
                    lastFocusedPkg = e.pkg;
                    if (which == 0) {
                        apps.remove(index);
                        apps.add(0, e);
                    } else if (which == 1 && index > 0) {
                        Collections.swap(apps, index, index - 1);
                    } else if (which == 2 && index < apps.size() - 1) {
                        Collections.swap(apps, index, index + 1);
                    } else if (which == 3) {
                        Set<String> hidden = new HashSet<>(
                                prefs.getStringSet(KEY_HIDDEN, new HashSet<String>()));
                        hidden.add(e.pkg);
                        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply();
                        apps.remove(index);
                        lastFocusedPkg = null;
                    }
                    saveCurrentOrder();
                    renderApps();
                })
                .show();
    }

    private void showSettingsMenu() {
        final boolean showAll = prefs.getBoolean(KEY_SHOW_ALL, false);
        String[] items = {
                "Changer la ville de la météo",
                showAll ? "N'afficher que mes applis" : "Afficher toutes les applis",
                "Réafficher les applis masquées",
                prefs.getBoolean(KEY_WALLPAPER_OFF, false) ? "Remettre le fond photo" : "Fond noir uni",
                "Paramètres de la télé"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Réglages")
                .setItems(items, (d, which) -> {
                    if (which == 0) askCity();
                    else if (which == 3) {
                        boolean off = !prefs.getBoolean(KEY_WALLPAPER_OFF, false);
                        prefs.edit().putBoolean(KEY_WALLPAPER_OFF, off).apply();
                        loadWallpaper();
                    } else if (which == 1) {
                        prefs.edit().putBoolean(KEY_SHOW_ALL, !showAll).remove(KEY_ORDER).apply();
                        loadApps();
                    } else if (which == 2) {
                        prefs.edit().remove(KEY_HIDDEN).apply();
                        loadApps();
                    } else {
                        try {
                            startActivity(new Intent(Settings.ACTION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ex) {
                            Toast.makeText(this, "Paramètres introuvables", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void askCity() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setSingleLine(true);
        input.setText(prefs.getString(KEY_CITY, DEFAULT_CITY));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Ville pour la météo")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    String city = input.getText().toString().trim();
                    if (city.isEmpty()) return;
                    prefs.edit().putString(KEY_CITY, city).remove(KEY_LAT).remove(KEY_LON).apply();
                    weatherCity.setText(city);
                    refreshWeather();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ================================================================
    // Météo (Open-Meteo, gratuit, sans clé)
    // ================================================================

    private void refreshWeather() {
        final String city = prefs.getString(KEY_CITY, DEFAULT_CITY);
        io.execute(() -> {
            try {
                String lat = prefs.getString(KEY_LAT, null);
                String lon = prefs.getString(KEY_LON, null);
                String shownCity = city;
                if (lat == null || lon == null) {
                    JSONObject geo = new JSONObject(get(
                            "https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&name="
                                    + URLEncoder.encode(city, "UTF-8")));
                    JSONArray res = geo.optJSONArray("results");
                    if (res == null || res.length() == 0) {
                        main.post(() -> weatherDesc.setText("Ville introuvable"));
                        return;
                    }
                    JSONObject r = res.getJSONObject(0);
                    lat = String.valueOf(r.getDouble("latitude"));
                    lon = String.valueOf(r.getDouble("longitude"));
                    shownCity = r.optString("name", city);
                    prefs.edit().putString(KEY_LAT, lat).putString(KEY_LON, lon)
                            .putString(KEY_CITY, shownCity).apply();
                }
                JSONObject w = new JSONObject(get(
                        "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                                + "&current=temperature_2m,weather_code"
                                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"));
                JSONObject cur = w.getJSONObject("current");
                final int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                final int code = cur.getInt("weather_code");
                JSONObject daily = w.optJSONObject("daily");
                String range = "";
                if (daily != null) {
                    int max = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
                    int min = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
                    range = "  ·  " + max + "° / " + min + "°";
                }
                final String desc = describe(code) + range;
                final String c = shownCity;
                main.post(() -> {
                    weatherTemp.setText(temp + "°");
                    weatherDesc.setText(desc);
                    weatherCity.setText(c);
                });
            } catch (Exception ex) {
                main.post(() -> weatherDesc.setText("Météo indisponible"));
            }
        });
    }

    private static String describe(int code) {
        if (code == 0) return "Ciel dégagé";
        if (code <= 2) return "Éclaircies";
        if (code == 3) return "Couvert";
        if (code == 45 || code == 48) return "Brouillard";
        if (code >= 51 && code <= 57) return "Bruine";
        if (code >= 61 && code <= 67) return "Pluie";
        if (code >= 71 && code <= 77) return "Neige";
        if (code >= 80 && code <= 82) return "Averses";
        if (code == 85 || code == 86) return "Averses de neige";
        if (code >= 95) return "Orage";
        return "";
    }

    // ================================================================
    // Prochain match de l'OM (données publiques ESPN)
    // ================================================================

    private static class Match {
        long time;
        String state; // pre, in, post
        String comp;
        String home, away, homeLogo, awayLogo, homeScore, awayScore;
        String venue, detail;
    }

    private void refreshMatch() {
        io.execute(() -> {
            Match best = null;
            long now = System.currentTimeMillis();
            for (String[] c : COMPETITIONS) {
                try {
                    JSONObject json = new JSONObject(get(
                            "https://site.api.espn.com/apis/site/v2/sports/soccer/" + c[0]
                                    + "/teams/" + TEAM_ID + "/schedule?fixture=true"));
                    JSONArray events = json.optJSONArray("events");
                    if (events == null) continue;
                    for (int i = 0; i < events.length(); i++) {
                        Match m = parseEvent(events.getJSONObject(i), c[1]);
                        if (m == null) continue;
                        if ("post".equals(m.state)) continue;
                        if (!"in".equals(m.state) && m.time < now - 3 * 3600_000L) continue;
                        if (best == null
                                || ("in".equals(m.state) && !"in".equals(best.state))
                                || (!"in".equals(best.state) && m.time < best.time)) {
                            best = m;
                        }
                    }
                } catch (Exception ignored) {
                    // compétition non disputée cette saison, ou réseau : on passe
                }
            }
            final Match m = best;
            main.post(() -> showMatch(m));
            if (m != null) {
                loadLogo(m.homeLogo, matchHomeLogo);
                loadLogo(m.awayLogo, matchAwayLogo);
            }
        });
    }

    private Match parseEvent(JSONObject ev, String compName) {
        try {
            JSONObject comp = ev.getJSONArray("competitions").getJSONObject(0);
            Match m = new Match();
            m.time = parseDate(ev.optString("date", comp.optString("date")));
            JSONObject status = comp.optJSONObject("status");
            if (status == null) status = ev.optJSONObject("status");
            JSONObject type = status != null ? status.optJSONObject("type") : null;
            m.state = type != null ? type.optString("state", "pre") : "pre";
            m.detail = status != null ? status.optString("displayClock", "") : "";
            m.comp = compName;
            JSONArray cs = comp.getJSONArray("competitors");
            for (int i = 0; i < cs.length(); i++) {
                JSONObject t = cs.getJSONObject(i);
                JSONObject team = t.getJSONObject("team");
                String name = team.optString("shortDisplayName", team.optString("displayName"));
                if (TEAM_ID.equals(team.optString("id"))) name = "OM";
                String logo = null;
                JSONArray logos = team.optJSONArray("logos");
                if (logos != null && logos.length() > 0) logo = logos.getJSONObject(0).optString("href");
                if (logo == null) logo = team.optString("logo", null);
                String score = scoreOf(t.opt("score"));
                if ("away".equals(t.optString("homeAway"))) {
                    m.away = name; m.awayLogo = logo; m.awayScore = score;
                } else {
                    m.home = name; m.homeLogo = logo; m.homeScore = score;
                }
            }
            JSONObject venue = comp.optJSONObject("venue");
            if (venue != null) m.venue = venue.optString("fullName", null);
            return m.home != null && m.away != null && m.time > 0 ? m : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String scoreOf(Object o) {
        if (o == null) return "0";
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            return j.optString("displayValue", String.valueOf((int) j.optDouble("value", 0)));
        }
        String s = String.valueOf(o);
        return s.isEmpty() ? "0" : s;
    }

    private static long parseDate(String s) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private void showMatch(Match m) {
        if (m == null) {
            matchLive = false;
            matchCard.setVisibility(View.GONE);
            return;
        }
        matchLive = "in".equals(m.state);
        matchCard.setVisibility(View.VISIBLE);
        matchHome.setText(m.home);
        matchAway.setText(m.away);
        if (matchLive) {
            matchComp.setText("EN DIRECT  ·  " + m.comp.toUpperCase(Locale.FRENCH));
            matchComp.setTextColor(ACCENT_LIVE);
            matchScore.setText(m.homeScore + "  –  " + m.awayScore);
            matchScore.setTextColor(Color.WHITE);
            matchWhen.setText(TextUtils.isEmpty(m.detail) ? "Match en cours" : m.detail);
        } else {
            matchComp.setText("PROCHAIN MATCH  ·  " + m.comp.toUpperCase(Locale.FRENCH));
            matchComp.setTextColor(TEXT_DIM);
            matchScore.setText("–");
            matchScore.setTextColor(TEXT_DIM);
            String when = formatWhen(m.time);
            if (!TextUtils.isEmpty(m.venue)) when += "  ·  " + m.venue;
            matchWhen.setText(when);
        }
    }

    private static String formatWhen(long t) {
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        String hour = new SimpleDateFormat("HH:mm", Locale.FRENCH).format(new Date(t));
        int dayDiff = dayIndex(c) - dayIndex(now);
        if (dayDiff == 0) return "Aujourd'hui à " + hour;
        if (dayDiff == 1) return "Demain à " + hour;
        String day = new SimpleDateFormat("EEEE d MMMM", Locale.FRENCH).format(new Date(t));
        return day.substring(0, 1).toUpperCase(Locale.FRENCH) + day.substring(1) + " à " + hour;
    }

    private static int dayIndex(Calendar c) {
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR);
    }

    private void loadLogo(final String url, final ImageView target) {
        if (url == null || url.isEmpty()) {
            main.post(() -> target.setImageDrawable(null));
            return;
        }
        Bitmap cached = logoCache.get(url);
        if (cached != null) {
            main.post(() -> target.setImageBitmap(cached));
            return;
        }
        io.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                InputStream in = conn.getInputStream();
                final Bitmap bmp = BitmapFactory.decodeStream(in);
                in.close();
                if (bmp != null) {
                    logoCache.put(url, bmp);
                    main.post(() -> target.setImageBitmap(bmp));
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // ================================================================
    // Réseau
    // ================================================================

    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() >= 400) throw new Exception("HTTP " + conn.getResponseCode());
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Touche Menu de la télécommande : ouvre les réglages du launcher.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSettingsMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
