import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Generates the two icons this plugin needs.
 *
 * Two, not one: {@code android:icon} in the manifest is what Android shows
 * in app lists, and {@code ToolbarItem.Builder} takes what ATAK draws in its
 * own toolbar strip. The launcher is a glyph on a dark rounded plate; the
 * toolbar is the glyph alone on transparency, because ATAK's toolbar is dark
 * and renders a near-opaque image as a solid block.
 *
 * The glyph is a server rack with status lights and a key, which says
 * "administering a server" rather than just "a server".
 *
 *   javac IconGen.java &amp;&amp; java IconGen ../app/src/main/res/drawable
 */
public final class IconGen {

    private static final int SIZE = 256;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : ".");
        if (!out.isDirectory() && !out.mkdirs())
            throw new IllegalStateException("cannot create " + out);

        ImageIO.write(render(true), "png",
                new File(out, "ic_launcher.png"));
        ImageIO.write(render(false), "png",
                new File(out, "ic_toolbar.png"));
        System.out.println("wrote ic_launcher.png and ic_toolbar.png to "
                + out.getAbsolutePath());
    }

    private static BufferedImage render(boolean plate) {
        BufferedImage img = new BufferedImage(SIZE, SIZE,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);

        if (plate) {
            g.setColor(new Color(0x1E, 0x27, 0x2E));
            g.fill(new RoundRectangle2D.Double(0, 0, SIZE, SIZE, 56, 56));
        }

        Color body = plate ? new Color(0xEC, 0xEF, 0xF1) : Color.WHITE;
        Color light = new Color(0x4C, 0xAF, 0x50);

        // Three rack units, stacked.
        double w = 132, h = 40, x = 42, gap = 14;
        double y = 44;
        g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 3; i++) {
            g.setColor(body);
            g.draw(new RoundRectangle2D.Double(x, y, w, h, 12, 12));
            // A status light on each, the thing you look at on a rack.
            g.setColor(i == 2 ? body : light);
            g.fill(new Ellipse2D.Double(x + 18, y + h / 2 - 7, 14, 14));
            // Drive slots.
            g.setColor(body);
            g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            for (int s = 0; s < 3; s++) {
                double sx = x + 56 + s * 22;
                g.drawLine((int) sx, (int) (y + 13), (int) sx,
                        (int) (y + h - 13));
            }
            g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            y += h + gap;
        }

        // The key: this is an ADMIN tool, not a server monitor.
        g.setColor(body);
        double kx = 176, ky = 150, r = 30;
        g.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.draw(new Ellipse2D.Double(kx, ky, r, r));
        Path2D shaft = new Path2D.Double();
        shaft.moveTo(kx + r * 0.92, ky + r * 0.92);
        shaft.lineTo(kx + r * 1.85, ky + r * 1.85);
        g.draw(shaft);
        g.drawLine((int) (kx + r * 1.50), (int) (kx + r * 1.72),
                (int) (kx + r * 1.78), (int) (kx + r * 1.44));

        g.dispose();
        return img;
    }
}
