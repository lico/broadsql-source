package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * The BroadSQL Editor's one icon family: toolbar actions, the Scripts tree (closed folder, open folder, Script)
 * and the few small inline actions (search, copy). Every icon is drawn as vectors on the same 16 px grid with the
 * same thin rounded outline, so they are crisp at any screen scaling and consistent with each other; no image
 * files and no icon library (BroadSQL ships core FlatLaf only, whose own icon set does not cover these actions).
 *
 * <p>Color policy: outlines use the Look and Feel's normal text color, and its disabled text color when the
 * component showing the icon is disabled. Only Run (green) and Delete (red) are colored, plus the folder fill,
 * which follows the usual desktop file browser convention.
 */
final class EditorIcons {

	/** The grid every icon is drawn on. */
	static final int SIZE = 16;

	static final Color RUN_GREEN = new Color(0x2E8B3E);
	static final Color DELETE_RED = new Color(0xC8372D);
	private static final Color FOLDER_FILL = new Color(0xF2C55C);
	private static final Color FOLDER_EDGE = new Color(0xC9982F);

	private EditorIcons() {
	}

	/** The icons, by what they show. */
	enum Kind {
		NEW_SCRIPT, NEW_FOLDER, SAVE, FORMAT, RUN, HISTORY, RENAME, DUPLICATE, DELETE,
		FOLDER_CLOSED, FOLDER_OPEN, SCRIPT, SEARCH, COPY, SEND_TO_CLI
	}

	static Icon get(Kind kind) {
		return new VectorIcon(kind);
	}

	/** A 16 px icon painted from {@link Kind}; {@link #kind()} lets a test tell icons apart. */
	static final class VectorIcon implements Icon {

		private final Kind kind;

		VectorIcon(Kind kind) {
			this.kind = kind;
		}

		Kind kind() {
			return kind;
		}

		@Override
		public int getIconWidth() {
			return SIZE;
		}

		@Override
		public int getIconHeight() {
			return SIZE;
		}

		@Override
		public void paintIcon(Component c, Graphics graphics, int x, int y) {
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.translate(x, y);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
				g.setStroke(new BasicStroke(1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				boolean enabled = c == null || c.isEnabled();
				Color ink = enabled ? color("Label.foreground", new Color(0x3C3C3C)) : color("Label.disabledForeground", Color.GRAY);
				paint(g, kind, ink, enabled);
			} finally {
				g.dispose();
			}
		}

		@Override
		public String toString() {
			return "EditorIcon[" + kind + "]";
		}
	}

	private static Color color(String key, Color fallback) {
		Color c = UIManager.getColor(key);
		return c != null ? c : fallback;
	}

	private static void paint(Graphics2D g, Kind kind, Color ink, boolean enabled) {
		g.setColor(ink);
		switch (kind) {
			case NEW_SCRIPT -> {
				document(g);
				plus(g, ink, 11.5f, 11.5f);
			}
			case NEW_FOLDER -> {
				g.draw(folderOutline());
				plus(g, ink, 11.5f, 11.5f);
			}
			case SAVE -> {
				Path2D disk = new Path2D.Float();
				disk.moveTo(2.5, 2.5);
				disk.lineTo(11, 2.5);
				disk.lineTo(13.5, 5);
				disk.lineTo(13.5, 13.5);
				disk.lineTo(2.5, 13.5);
				disk.closePath();
				g.draw(disk);
				g.draw(new Line2D.Float(5, 2.5f, 5, 5.5f));
				g.draw(new Line2D.Float(5, 5.5f, 10, 5.5f));
				g.draw(new Line2D.Float(10, 5.5f, 10, 2.5f));
				g.draw(new RoundRectangle2D.Float(4.5f, 8.5f, 7, 5, 1, 1));
			}
			case FORMAT -> {
				g.draw(new Line2D.Float(2.5f, 3.5f, 13.5f, 3.5f));
				g.draw(new Line2D.Float(5.5f, 6.5f, 13.5f, 6.5f));
				g.draw(new Line2D.Float(5.5f, 9.5f, 13.5f, 9.5f));
				g.draw(new Line2D.Float(2.5f, 12.5f, 13.5f, 12.5f));
			}
			case RUN -> {
				g.setColor(enabled ? RUN_GREEN : ink);
				Path2D play = new Path2D.Float();
				play.moveTo(4.5, 2.8);
				play.lineTo(13, 8);
				play.lineTo(4.5, 13.2);
				play.closePath();
				g.fill(play);
				g.draw(play);
			}
			case HISTORY -> {
				g.draw(new Arc2D.Float(2.5f, 2.5f, 11, 11, 150, -300, Arc2D.OPEN));
				Path2D head = new Path2D.Float();
				head.moveTo(1.8, 3.8);
				head.lineTo(2.7, 5.9);
				head.lineTo(4.9, 5.2);
				g.draw(head);
				g.draw(new Line2D.Float(8, 5, 8, 8.2f));
				g.draw(new Line2D.Float(8, 8.2f, 10.2f, 9.6f));
			}
			case RENAME -> {
				Path2D pencil = new Path2D.Float();
				pencil.moveTo(10.5, 2.5);
				pencil.lineTo(13.5, 5.5);
				pencil.lineTo(5.5, 13.5);
				pencil.lineTo(2.5, 13.5);
				pencil.lineTo(2.5, 10.5);
				pencil.closePath();
				g.draw(pencil);
				g.draw(new Line2D.Float(8.8f, 4.2f, 11.8f, 7.2f));
			}
			case DUPLICATE, COPY -> {
				g.draw(new RoundRectangle2D.Float(5.5f, 5.5f, 8, 8, 2, 2));
				Path2D back = new Path2D.Float();
				back.moveTo(10.5, 3.5);
				back.lineTo(10.5, 3);
				back.quadTo(10.5, 2.5, 10, 2.5);
				back.lineTo(3, 2.5);
				back.quadTo(2.5, 2.5, 2.5, 3);
				back.lineTo(2.5, 10);
				back.quadTo(2.5, 10.5, 3, 10.5);
				back.lineTo(3.5, 10.5);
				g.draw(back);
			}
			case DELETE -> {
				g.setColor(enabled ? DELETE_RED : ink);
				g.draw(new Line2D.Float(2.5f, 4.5f, 13.5f, 4.5f));
				Path2D lid = new Path2D.Float();
				lid.moveTo(6, 4.5);
				lid.lineTo(6, 2.5);
				lid.lineTo(10, 2.5);
				lid.lineTo(10, 4.5);
				g.draw(lid);
				Path2D can = new Path2D.Float();
				can.moveTo(4, 4.5);
				can.lineTo(4.8, 13.5);
				can.lineTo(11.2, 13.5);
				can.lineTo(12, 4.5);
				g.draw(can);
				g.draw(new Line2D.Float(6.8f, 7, 6.8f, 11));
				g.draw(new Line2D.Float(9.2f, 7, 9.2f, 11));
			}
			case FOLDER_CLOSED -> {
				Path2D folder = folderOutline();
				g.setColor(FOLDER_FILL);
				g.fill(folder);
				g.setColor(FOLDER_EDGE);
				g.draw(folder);
			}
			case FOLDER_OPEN -> {
				Path2D back = new Path2D.Float();
				back.moveTo(1.5, 12.5);
				back.lineTo(1.5, 3.5);
				back.lineTo(6, 3.5);
				back.lineTo(7.5, 5);
				back.lineTo(12.5, 5);
				back.lineTo(12.5, 7);
				g.setColor(FOLDER_EDGE);
				g.draw(back);
				Path2D front = new Path2D.Float();
				front.moveTo(1.5, 12.5);
				front.lineTo(4, 7);
				front.lineTo(15, 7);
				front.lineTo(12.5, 12.5);
				front.closePath();
				g.setColor(FOLDER_FILL);
				g.fill(front);
				g.setColor(FOLDER_EDGE);
				g.draw(front);
			}
			case SCRIPT -> {
				document(g);
				g.draw(new Line2D.Float(5.5f, 8f, 10.5f, 8f));
				g.draw(new Line2D.Float(5.5f, 10.5f, 10.5f, 10.5f));
			}
			case SEARCH -> {
				g.draw(new Ellipse2D.Float(2.5f, 2.5f, 8, 8));
				g.draw(new Line2D.Float(9.5f, 9.5f, 13.5f, 13.5f));
			}
			case SEND_TO_CLI -> {
				g.draw(new RoundRectangle2D.Float(1.5f, 2.5f, 13, 11, 2, 2));
				Path2D chevron = new Path2D.Float();
				chevron.moveTo(4.5, 6);
				chevron.lineTo(6.8, 8);
				chevron.lineTo(4.5, 10);
				g.draw(chevron);
				g.draw(new Line2D.Float(8.5f, 10.5f, 11.5f, 10.5f));
			}
		}
	}

	/** A page with a folded top right corner. */
	private static void document(Graphics2D g) {
		Path2D page = new Path2D.Float();
		page.moveTo(3.5, 1.5);
		page.lineTo(9.5, 1.5);
		page.lineTo(12.5, 4.5);
		page.lineTo(12.5, 14.5);
		page.lineTo(3.5, 14.5);
		page.closePath();
		g.draw(page);
		Path2D fold = new Path2D.Float();
		fold.moveTo(9.5, 1.5);
		fold.lineTo(9.5, 4.5);
		fold.lineTo(12.5, 4.5);
		g.draw(fold);
	}

	private static Path2D folderOutline() {
		Path2D folder = new Path2D.Float();
		folder.moveTo(1.5, 3.5);
		folder.lineTo(6, 3.5);
		folder.lineTo(7.5, 5);
		folder.lineTo(14.5, 5);
		folder.lineTo(14.5, 12.5);
		folder.lineTo(1.5, 12.5);
		folder.closePath();
		return folder;
	}

	/** A small "+" badge centered on (cx, cy), cut out of whatever is underneath so it stays legible. */
	private static void plus(Graphics2D g, Color ink, float cx, float cy) {
		Color background = color("Panel.background", Color.WHITE);
		g.setColor(background);
		g.fill(new Ellipse2D.Float(cx - 4, cy - 4, 8, 8));
		g.setColor(ink);
		g.draw(new Line2D.Float(cx - 2.5f, cy, cx + 2.5f, cy));
		g.draw(new Line2D.Float(cx, cy - 2.5f, cx, cy + 2.5f));
	}
}
