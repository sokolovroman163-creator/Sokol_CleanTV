package ru.sokol.cleantv;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Resolution-independent artwork, drawn locally, with no network or large bitmap. */
final class Art extends View {
  private final boolean background;
  private final Paint p = new Paint(3);

  Art(Context c, boolean background) {
    super(c);
    this.background = background;
    if (!background) setContentDescription("Телевизор с синим волнистым фоном");
    else setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float w = getWidth(), h = getHeight();
    if (background) {
      c.drawColor(Color.rgb(237, 242, 252));
      p.setShader(
          new RadialGradient(
              w * .95f,
              h * .18f,
              w * .95f,
              new int[] {0xA070EFFF, 0x409CB8FF, 0x00FFFFFF},
              null,
              Shader.TileMode.CLAMP));
      c.drawRect(0, 0, w, h, p);
      p.setShader(
          new RadialGradient(
              0, h * .68f, w, new int[] {0x6091B0FF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
      c.drawRect(0, 0, w, h, p);
      p.setShader(null);
      return;
    }
    float sx = w / 360f, sy = h / 196f;
    c.save();
    c.scale(sx, sy);
    p.setShader(
        new RadialGradient(
            180, 174, 150, new int[] {0x80517CE3, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    c.drawOval(20, 152, 340, 192, p);
    p.setShader(null);
    p.setColor(0xFF25364F);
    p.setStrokeWidth(6);
    p.setStrokeCap(Paint.Cap.ROUND);
    c.drawLine(61, 160, 49, 178, p);
    c.drawLine(299, 160, 311, 178, p);
    RectF outer = new RectF(28, 14, 332, 165);
    p.setColor(0xFF344056);
    c.drawRoundRect(outer, 3, 3, p);
    RectF screen = new RectF(31, 17, 329, 160);
    p.setShader(
        new LinearGradient(
            36,
            30,
            330,
            158,
            new int[] {0xFF07152F, 0xFF06276B, 0xFF0053C4},
            null,
            Shader.TileMode.CLAMP));
    c.drawRect(screen, p);
    c.save();
    c.clipRect(screen);
    Path path = new Path();
    path.moveTo(20, 130);
    path.cubicTo(110, 37, 142, 197, 241, 76);
    path.cubicTo(276, 28, 300, 43, 350, 24);
    path.lineTo(350, 200);
    path.lineTo(10, 200);
    path.close();
    p.setShader(
        new LinearGradient(
            50,
            85,
            220,
            170,
            new int[] {0xFF003BBA, 0xFF10D3FF, 0xFF164EDE, 0xFF071F62},
            null,
            Shader.TileMode.CLAMP));
    c.drawPath(path, p);
    Path wave = new Path();
    wave.moveTo(24, 127);
    wave.cubicTo(110, 41, 145, 192, 241, 76);
    wave.cubicTo(276, 29, 306, 41, 338, 28);
    p.setShader(null);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1.2f);
    p.setColor(0xFF8CF7FF);
    c.drawPath(wave, p);
    p.setStyle(Paint.Style.FILL);
    c.restore();
    p.setColor(0xFF1CAFFF);
    c.drawRoundRect(170, 162, 190, 164, 1, 1, p);
    c.restore();
  }
}
