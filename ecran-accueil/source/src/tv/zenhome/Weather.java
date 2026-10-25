package tv.zenhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Meteo via Open-Meteo (gratuit, sans cle) : recherche de la ville, previsions, et icones dessinees a la main
 * (aucune police d'emoji requise sur la TV).
 */
final class Weather {
    private Weather() { }

    static final int SUN = 0, MOON = 1, PARTLY = 2, CLOUD = 3, FOG = 4, RAIN = 5, SNOW = 6, STORM = 7;

    /** Meteo actuelle + jusqu'a 4 jours (aujourd'hui compris). */
    static final class Forecast {
        int tempNow, codeNow;
        boolean isDay = true;
        int days;
        final String[] date = new String[4];
        final int[] code = new int[4], min = new int[4], max = new int[4];
    }

    // =====================================================================
    // Reseau (a appeler hors du thread principal)
    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "ZenHome/2.6");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** {nom, latitude, longitude} de la ville, ou null si elle est introuvable. */
    static String[] geocode(String name, String lang) throws Exception {
        String u = "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name, "UTF-8");
        JSONArray r = new JSONObject(get(u)).optJSONArray("results");
        if (r == null || r.length() == 0) return null;
        JSONObject p = r.getJSONObject(0);
        return new String[]{p.optString("name", name), String.valueOf(p.getDouble("latitude")), String.valueOf(p.getDouble("longitude"))};
    }

    /** Reponse brute d'Open-Meteo (verifiee lisible) : on la garde telle quelle pour l'afficher meme hors ligne. */
    static String fetchForecast(String lat, String lon) throws Exception {
        String u = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=4";
        String json = get(u);
        if (parse(json) == null) throw new IOException("reponse illisible");
        return json;
    }

    static Forecast parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject cur = o.getJSONObject("current");
            JSONObject d = o.getJSONObject("daily");
            Forecast f = new Forecast();
            f.tempNow = (int) Math.round(cur.getDouble("temperature_2m"));
            f.codeNow = cur.getInt("weather_code");
            f.isDay = cur.optInt("is_day", 1) == 1;
            JSONArray t = d.getJSONArray("time");
            JSONArray c = d.getJSONArray("weather_code");
            JSONArray mx = d.getJSONArray("temperature_2m_max");
            JSONArray mn = d.getJSONArray("temperature_2m_min");
            f.days = Math.min(4, t.length());
            for (int i = 0; i < f.days; i++) {
                f.date[i] = t.getString(i);
                f.code[i] = c.getInt(i);
                f.max[i] = (int) Math.round(mx.getDouble(i));
                f.min[i] = (int) Math.round(mn.getDouble(i));
            }
            return f.days > 0 ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    // =====================================================================
    // Codes meteo WMO -> icone et texte
    static int kind(int code, boolean day) {
        if (code == 0 || code == 1) return day ? SUN : MOON;
        if (code == 2) return day ? PARTLY : CLOUD;
        if (code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 95) return STORM;
        return CLOUD;
    }

    static String label(int code, boolean fr) {
        if (code == 0) return fr ? "Ciel dégagé" : "Clear sky";
        if (code == 1) return fr ? "Plutôt dégagé" : "Mostly clear";
        if (code == 2) return fr ? "Partiellement nuageux" : "Partly cloudy";
        if (code == 3) return fr ? "Couvert" : "Overcast";
        if (code == 45 || code == 48) return fr ? "Brouillard" : "Fog";
        if (code >= 51 && code <= 57) return fr ? "Bruine" : "Drizzle";
        if (code >= 61 && code <= 67) return fr ? "Pluie" : "Rain";
        if (code >= 71 && code <= 77) return fr ? "Neige" : "Snow";
        if (code >= 80 && code <= 82) return fr ? "Averses" : "Showers";
        if (code == 85 || code == 86) return fr ? "Averses de neige" : "Snow showers";
        if (code >= 95) return fr ? "Orage" : "Thunderstorm";
        return fr ? "Nuageux" : "Cloudy";
    }

    static String dayName(String iso, Locale loc) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            String s = new SimpleDateFormat("EEE", loc).format(d).replace(".", "");
            return s.substring(0, 1).toUpperCase(loc) + s.substring(1);
        } catch (Exception e) {
            return "";
        }
    }

    // =====================================================================
    /** Icone meteo dessinee dans un carre de 1 x 1 (mis a l'echelle de la zone). */
    static final class Icon extends Drawable {
        static final int WHITE = 0xFFF3F6FA, GREY = 0xFFE3E9F0, DARK = 0xFFBFC8D3, YELLOW = 0xFFFFD25E, BLUE = 0xFF6EC1FF;
        final int kind;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Icon(int kind) { this.kind = kind; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0) return;
            c.save();
            c.translate(b.exactCenterX() - s / 2f, b.exactCenterY() - s / 2f);
            c.scale(s, s);
            switch (kind) {
                case SUN: sun(c, 0.5f, 0.5f, 0.2f); break;
                case MOON: moon(c); break;
                case PARTLY: sun(c, 0.36f, 0.36f, 0.15f); cloud(c, 0.14f, 0.34f, 0.76f, WHITE); break;
                case CLOUD: cloud(c, 0.08f, 0.22f, 0.84f, WHITE); break;
                case FOG: fog(c); break;
                case RAIN: cloud(c, 0.1f, 0.1f, 0.8f, GREY); rain(c); break;
                case SNOW: cloud(c, 0.1f, 0.1f, 0.8f, GREY); snow(c); break;
                default: cloud(c, 0.1f, 0.1f, 0.8f, DARK); bolt(c); break;
            }
            c.restore();
        }

        void sun(Canvas c, float cx, float cy, float rad) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(rad * 0.32f);
            p.setColor(YELLOW);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                c.drawLine(cx + cs * rad * 1.45f, cy + sn * rad * 1.45f, cx + cs * rad * 1.95f, cy + sn * rad * 1.95f, p);
            }
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx, cy, rad, p);
        }

        void moon(Canvas c) {
            Path a = new Path();
            a.addCircle(0.5f, 0.5f, 0.27f, Path.Direction.CW);
            Path hole = new Path();
            hole.addCircle(0.62f, 0.41f, 0.24f, Path.Direction.CW);
            a.op(hole, Path.Op.DIFFERENCE);
            p.setStyle(Paint.Style.FILL);
            p.setColor(WHITE);
            c.drawPath(a, p);
        }

        /** Nuage de largeur w dont le coin haut-gauche est (x, y) ; hauteur environ 0,64 w. */
        void cloud(Canvas c, float x, float y, float w, int color) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(x + 0.28f * w, y + 0.42f * w, 0.20f * w, p);
            c.drawCircle(x + 0.52f * w, y + 0.32f * w, 0.28f * w, p);
            c.drawCircle(x + 0.76f * w, y + 0.46f * w, 0.18f * w, p);
            r.set(x + 0.08f * w, y + 0.42f * w, x + 0.92f * w, y + 0.64f * w);
            c.drawRoundRect(r, 0.11f * w, 0.11f * w, p);
        }

        void fog(Canvas c) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(0.07f);
            p.setColor(GREY);
            c.drawLine(0.2f, 0.36f, 0.8f, 0.36f, p);
            c.drawLine(0.12f, 0.5f, 0.72f, 0.5f, p);
            c.drawLine(0.24f, 0.64f, 0.84f, 0.64f, p);
        }

        void rain(Canvas c) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(0.055f);
            p.setColor(BLUE);
            c.drawLine(0.34f, 0.68f, 0.28f, 0.84f, p);
            c.drawLine(0.52f, 0.68f, 0.46f, 0.86f, p);
            c.drawLine(0.70f, 0.68f, 0.64f, 0.84f, p);
        }

        void snow(Canvas c) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.drawCircle(0.34f, 0.74f, 0.045f, p);
            c.drawCircle(0.50f, 0.84f, 0.045f, p);
            c.drawCircle(0.66f, 0.74f, 0.045f, p);
        }

        void bolt(Canvas c) {
            Path b = new Path();
            b.moveTo(0.56f, 0.58f);
            b.lineTo(0.40f, 0.80f);
            b.lineTo(0.50f, 0.80f);
            b.lineTo(0.44f, 0.97f);
            b.lineTo(0.66f, 0.72f);
            b.lineTo(0.55f, 0.72f);
            b.lineTo(0.64f, 0.58f);
            b.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(YELLOW);
            c.drawPath(b, p);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
