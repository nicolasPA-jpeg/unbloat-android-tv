package tv.zenhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Effet « verre liquide » pour Android 11 (pas de flou en direct avant Android 12) :
 * on garde une copie tres floue de la photo de fond, et chaque panneau en affiche la partie
 * qui se trouve derriere lui, avec une teinte, un reflet en haut et un liseré lumineux.
 */
final class Glass {
    private Glass() { }

    /** Copie floutee de la photo en cours, partagee par tous les panneaux. Fondu enchainé entre deux photos. */
    static final class Backdrop {
        int sw = 1280, sh = 720;          // taille de l'ecran en pixels
        int topPx = 0, botPx = 0;         // hauteur des degrades sombres de l'accueil (pour les reproduire dans le flou)
        Bitmap cur, prev;
        float mix = 1f;                   // 0 = ancienne photo, 1 = nouvelle photo
        ValueAnimator anim;
        final List<WeakReference<Panel>> panels = new ArrayList<WeakReference<Panel>>();

        void setScreen(int w, int h, int top, int bot) {
            sw = Math.max(1, w); sh = Math.max(1, h); topPx = top; botPx = bot;
        }

        void update(Bitmap wall, boolean instant, long fadeMs) {
            Bitmap nb;
            try { nb = makeBlur(wall, sw, sh, topPx, botPx); } catch (Throwable t) { return; }
            if (anim != null) anim.cancel();
            if (cur == null || instant) { cur = nb; prev = null; mix = 1f; notifyPanels(); return; }
            prev = cur; cur = nb; mix = 0f;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(fadeMs);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator a) { mix = (Float) a.getAnimatedValue(); notifyPanels(); }
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { prev = null; mix = 1f; notifyPanels(); }
            });
            anim.start();
        }

        void notifyPanels() {
            for (Iterator<WeakReference<Panel>> it = panels.iterator(); it.hasNext(); ) {
                Panel p = it.next().get();
                if (p == null) it.remove(); else p.invalidateSelf();
            }
        }
    }

    // =====================================================================
    // Flou : reduction, cadrage « centre-crop » comme la photo affichee, deux passes de flou moyen, agrandissement lisse.
    static Bitmap makeBlur(Bitmap src, int sw, int sh, int topPx, int botPx) {
        final int bw = 128;
        final int bh = Math.max(8, Math.round(bw * (float) sh / sw));
        // 1. reductions successives par 2 (evite les crenelures d'un seul grand saut)
        Bitmap s = src;
        while (s.getWidth() > 4 * bw) {
            Bitmap n = Bitmap.createScaledBitmap(s, s.getWidth() / 2, s.getHeight() / 2, true);
            if (s != src) s.recycle();
            s = n;
        }
        // 2. cadrage centre-crop dans bw x bh
        Bitmap small = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        float k = Math.max((float) bw / s.getWidth(), (float) bh / s.getHeight());
        Matrix m = new Matrix();
        m.postScale(k, k);
        m.postTranslate((bw - s.getWidth() * k) / 2f, (bh - s.getHeight() * k) / 2f);
        c.drawBitmap(s, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        if (s != src) s.recycle();
        // 3. flou
        boxBlur(small, 3);
        boxBlur(small, 3);
        // 4. agrandissement lisse + degrades sombres de l'accueil (haut et bas), comme derriere les panneaux
        Bitmap big = Bitmap.createBitmap(bw * 3, bh * 3, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(big);
        bc.drawBitmap(small, null, new Rect(0, 0, big.getWidth(), big.getHeight()), new Paint(Paint.FILTER_BITMAP_FLAG));
        small.recycle();
        Paint g = new Paint();
        float H = big.getHeight(), W = big.getWidth();
        if (topPx > 0) {
            float th = H * topPx / sh;
            g.setShader(new LinearGradient(0, 0, 0, th, 0x8C000000, 0x00000000, Shader.TileMode.CLAMP));
            bc.drawRect(0, 0, W, th, g);
        }
        if (botPx > 0) {
            float bhh = H * botPx / sh;
            g.setShader(new LinearGradient(0, H, 0, H - bhh, 0x99000000, 0x00000000, Shader.TileMode.CLAMP));
            bc.drawRect(0, H - bhh, W, H, g);
        }
        return big;
    }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static void boxBlur(Bitmap bm, int r) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        bm.getPixels(a, 0, w, 0, 0, w, h);
        blurPass(a, b, w, h, r, true);
        blurPass(b, a, w, h, r, false);
        bm.setPixels(a, 0, w, 0, 0, w, h);
    }

    /** Flou moyen d'un rayon r sur chaque ligne (horiz) ou colonne ; les bords sont repetes. */
    static void blurPass(int[] src, int[] dst, int w, int h, int r, boolean horiz) {
        int lines = horiz ? h : w, len = horiz ? w : h, step = horiz ? 1 : w, div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = horiz ? l * w : l;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[base + clamp(i, 0, len - 1) * step];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = src[base + clamp(i + r + 1, 0, len - 1) * step];
                int sub = src[base + clamp(i - r, 0, len - 1) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    // =====================================================================
    /** Fond de vue « verre » : photo floutee derriere + teinte + reflet + liseré. */
    static final class Panel extends Drawable {
        final View view;
        final Backdrop bd;
        final float radius, stroke;
        final int tint;
        final Paint photo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF(), inner = new RectF();
        final Matrix m = new Matrix();
        final int[] loc = new int[2];

        Panel(View view, Backdrop bd, float radiusPx, int tint, float density) {
            this.view = view; this.bd = bd; this.radius = radiusPx; this.tint = tint;
            this.stroke = Math.max(1f, density * 1.2f);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.4f);                       // le verre ravive un peu les couleurs derriere lui
            photo.setColorFilter(new ColorMatrixColorFilter(cm));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke);
            bd.panels.add(new WeakReference<Panel>(this));
        }

        void drawBlur(Canvas c, Bitmap bm, int alpha) {
            BitmapShader sh = new BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            m.reset();
            m.setScale((float) bd.sw / bm.getWidth(), (float) bd.sh / bm.getHeight());   // la copie couvre tout l'ecran
            m.postTranslate(-loc[0], -loc[1]);                                          // ... et on la recale sur ce panneau
            sh.setLocalMatrix(m);
            photo.setShader(sh);
            photo.setAlpha(alpha);
            c.drawRoundRect(rect, radius, radius, photo);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            rect.set(b);
            view.getLocationInWindow(loc);
            Bitmap cur = bd.cur, prev = bd.prev;
            if (cur != null) {
                if (prev != null && bd.mix < 1f) { drawBlur(c, prev, 255); drawBlur(c, cur, Math.round(bd.mix * 255)); }
                else drawBlur(c, cur, 255);
            }
            // teinte
            fill.setColor(tint);
            c.drawRoundRect(rect, radius, radius, fill);
            // reflet : lumiere qui descend du bord haut
            float h = rect.height();
            shine.setShader(new LinearGradient(0, rect.top, 0, rect.top + h * 0.6f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(rect, radius, radius, shine);
            // liseré : clair en haut a gauche, discret en bas a droite
            inner.set(rect);
            inner.inset(stroke / 2f, stroke / 2f);
            edge.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, 0xB3FFFFFF, 0x2EFFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(inner, radius - stroke / 2f, radius - stroke / 2f, edge);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
