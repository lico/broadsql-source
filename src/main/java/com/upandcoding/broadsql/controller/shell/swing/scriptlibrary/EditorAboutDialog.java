package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Frame;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;

/**
 * The BroadSQL Editor's About dialog: the version, the copyright and a link to the documentation home
 * page, nothing else (it is an About box, not Help). The text and target come from
 * {@link SpringPropertiesConfig} (the version from the JAR manifest, stamped by the build;
 * the site URL from {@link SpringPropertiesConfig#APP_SITE}) so nothing is hardcoded here, and are exposed
 * as static methods so they can be tested without a display.
 */
public final class EditorAboutDialog {

	static final String COPYRIGHT = "© UpAndCoding.com";
	static final String LINK_TEXT = "Documentation";

	private EditorAboutDialog() {
	}

	/** {@code BroadSQL <version>}. */
	public static String versionLine() {
		return "BroadSQL " + SpringPropertiesConfig.APP_VERSION_NUMBER;
	}

	public static String copyrightLine() {
		return COPYRIGHT;
	}

	/** The documentation home page, not a specific documentation page. */
	public static String documentationUrl() {
		return SpringPropertiesConfig.APP_DOCS_URL;
	}

	public static void show(Frame owner) {
		JDialog dialog = new JDialog(owner, "About BroadSQL Editor", true);
		JPanel content = new JPanel();
		content.setLayout(new java.awt.GridLayout(0, 1, 0, 6));
		content.setBorder(BorderFactory.createEmptyBorder(16, 24, 8, 24));
		content.add(new JLabel(versionLine()));
		content.add(new JLabel(copyrightLine()));
		content.add(linkLabel(dialog));

		JButton ok = new JButton("OK");
		ok.addActionListener(e -> dialog.dispose());
		JPanel buttons = new JPanel();
		buttons.add(ok);

		dialog.getContentPane().setLayout(new BorderLayout());
		dialog.getContentPane().add(content, BorderLayout.CENTER);
		dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.pack();
		dialog.setResizable(false);
		dialog.setLocationRelativeTo(owner);
		dialog.setVisible(true);
	}

	private static JLabel linkLabel(JDialog dialog) {
		String color = colorHex(UIManager.getColor("Component.linkColor"));
		JLabel link = new JLabel("<html><a href=\"\" style=\"color:" + color + "\">" + LINK_TEXT + "</a></html>", SwingConstants.LEFT);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setToolTipText(documentationUrl());
		link.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				openDocumentation(dialog);
			}
		});
		return link;
	}

	/** Opens {@link #documentationUrl()} in the default browser; if browsing is unavailable or fails, shows the URL instead of failing. */
	private static void openDocumentation(JDialog dialog) {
		try {
			if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				throw new UnsupportedOperationException("Browsing is not supported on this system");
			}
			Desktop.getDesktop().browse(URI.create(documentationUrl()));
		} catch (Exception ex) {
			JOptionPane.showMessageDialog(dialog, "Could not open a browser. The documentation is at:\n" + documentationUrl(), "Documentation",
					JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private static String colorHex(java.awt.Color color) {
		java.awt.Color c = color != null ? color : new java.awt.Color(0x2A6FDB);
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}
}
