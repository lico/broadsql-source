package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.DiffLine;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.MetadataDiffResult;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.MetadataFieldDiff;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.PathDiffResult;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.TextDiffResult;
import com.upandcoding.broadsql.controller.shell.swing.BroadSqlLookAndFeel;

/**
 * The read-only side-by-side diff view (SPRINT 0917-01, spec section 10 - Compare "is not optional"
 * and must make what changed "obvious at first glance," never a plain unified text diff). Two
 * read-only {@link JTextPane}s (not {@code RSyntaxTextArea} - that component's own syntax
 * {@code TokenMaker} highlighting would fight with diff coloring on the same document, per the plan),
 * one row per {@link DiffLine} so both sides stay vertically aligned line-for-line, scrolled together.
 * A metadata-diff section and a path-change banner are always shown when relevant, never folded into
 * the text diff (spec sections 10.5/10.6) - "the user must not miss metadata-only revisions."
 *
 * <p>Colors: added/removed use a conventional light green/red tint (immediately legible, the same
 * convention any diff tool uses); a modified line uses an amber tint derived by the same
 * blend-toward-white technique {@code BroadSqlLookAndFeel} itself uses for its own tints, applied to
 * {@link BroadSqlLookAndFeel#ACCENT} - so the "something changed here, look closer" color family stays
 * anchored to BroadSQL's own accent rather than an unrelated diff-tool palette.
 */
public final class ComparePanel extends JPanel {

	private static final Color ADD_BACKGROUND = new Color(0xE3F5E1);
	private static final Color ADD_HIGHLIGHT = new Color(0xB8E6B0);
	private static final Color REMOVE_BACKGROUND = new Color(0xFBE4E4);
	private static final Color REMOVE_HIGHLIGHT = new Color(0xF5B8B8);
	private static final Color CHANGE_BACKGROUND = tintAccent(0.12f);
	private static final Color CHANGE_HIGHLIGHT = tintAccent(0.35f);

	private final JTextPane oldPane = new JTextPane();
	private final JTextPane newPane = new JTextPane();
	private final JLabel summaryLabel = new JLabel(" ");
	private final JPanel metadataPanel = new JPanel();
	private final JLabel pathBanner = new JLabel(" ");

	private TextDiffResult currentDiff;
	private int currentChangeCursor = -1;

	public ComparePanel(String oldLabel, String newLabel) {
		buildUi(oldLabel, newLabel);
	}

	private static Color tintAccent(float amount) {
		Color accent = BroadSqlLookAndFeel.ACCENT;
		int r = Math.round(255 * (1 - amount) + accent.getRed() * amount);
		int g = Math.round(255 * (1 - amount) + accent.getGreen() * amount);
		int b = Math.round(255 * (1 - amount) + accent.getBlue() * amount);
		return new Color(r, g, b);
	}

	private void buildUi(String oldLabel, String newLabel) {
		setLayout(new BorderLayout());

		JPanel north = new JPanel(new BorderLayout());
		summaryLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 2, 6));
		pathBanner.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 6));
		pathBanner.setForeground(BroadSqlLookAndFeel.ACCENT.darker());
		pathBanner.setVisible(false);
		metadataPanel.setLayout(new GridLayout(0, 1));
		metadataPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 4, 6));
		JPanel headerStack = new JPanel();
		headerStack.setLayout(new javax.swing.BoxLayout(headerStack, javax.swing.BoxLayout.Y_AXIS));
		headerStack.add(summaryLabel);
		headerStack.add(pathBanner);
		headerStack.add(metadataPanel);
		north.add(headerStack, BorderLayout.NORTH);
		add(north, BorderLayout.NORTH);

		oldPane.setEditable(false);
		newPane.setEditable(false);
		oldPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		newPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

		JScrollPane oldScroll = new JScrollPane(oldPane);
		JScrollPane newScroll = new JScrollPane(newPane);
		oldScroll.setColumnHeaderView(new JLabel(" " + oldLabel));
		newScroll.setColumnHeaderView(new JLabel(" " + newLabel));
		syncScrollBars(oldScroll.getVerticalScrollBar(), newScroll.getVerticalScrollBar());

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, oldScroll, newScroll);
		split.setResizeWeight(0.5);
		add(split, BorderLayout.CENTER);
	}

	/** Keeps both panes' vertical scroll position identical - the whole point of a side-by-side, line-aligned diff. */
	private void syncScrollBars(JScrollBar left, JScrollBar right) {
		left.addAdjustmentListener(e -> {
			if (right.getValue() != e.getValue()) {
				right.setValue(e.getValue());
			}
		});
		right.addAdjustmentListener(e -> {
			if (left.getValue() != e.getValue()) {
				left.setValue(e.getValue());
			}
		});
	}

	/** Renders {@code textDiff}/{@code metadataDiff}/{@code pathDiff} - safe to call again to show a different comparison in the same panel. */
	public void showDiff(TextDiffResult textDiff, MetadataDiffResult metadataDiff, PathDiffResult pathDiff) {
		this.currentDiff = textDiff;
		this.currentChangeCursor = -1;

		summaryLabel.setText(String.format("%d addition(s), %d deletion(s), %d modified line(s)",
				textDiff.additions(), textDiff.deletions(), textDiff.modifications()));

		pathBanner.setVisible(pathDiff.changed());
		if (pathDiff.changed()) {
			pathBanner.setText("Path changed: " + pathDiff.oldPath() + " -> " + pathDiff.newPath());
		}

		metadataPanel.removeAll();
		if (metadataDiff.hasChanges()) {
			for (MetadataFieldDiff field : metadataDiff.fields()) {
				JLabel label = new JLabel(field.fieldName() + ": " + nullToDash(field.oldValue()) + "  ->  " + nullToDash(field.newValue()));
				label.setOpaque(true);
				label.setBackground(CHANGE_BACKGROUND);
				metadataPanel.add(label);
			}
		}
		metadataPanel.revalidate();

		renderPane(oldPane, textDiff, true);
		renderPane(newPane, textDiff, false);
	}

	private static String nullToDash(String value) {
		return value == null || value.isEmpty() ? "(none)" : value;
	}

	private void renderPane(JTextPane pane, TextDiffResult diff, boolean oldSide) {
		pane.setText("");
		StyledDocument doc = pane.getStyledDocument();
		try {
			for (DiffLine line : diff.lines()) {
				String text = oldSide ? line.oldText() : line.newText();
				SimpleAttributeSet rowAttrs = rowBackground(line.tag(), oldSide);
				if (text == null) {
					// The other side has no corresponding line here (pure insert/delete) - a blank row
					// keeps both panes aligned row-for-row.
					doc.insertString(doc.getLength(), " \n", rowAttrs);
					continue;
				}
				java.util.List<int[]> highlights = oldSide ? line.oldHighlightRanges() : line.newHighlightRanges();
				if (highlights.isEmpty()) {
					doc.insertString(doc.getLength(), text + "\n", rowAttrs);
				} else {
					int cursor = 0;
					for (int[] range : highlights) {
						if (range[0] > cursor) {
							doc.insertString(doc.getLength(), text.substring(cursor, range[0]), rowAttrs);
						}
						SimpleAttributeSet highlightAttrs = new SimpleAttributeSet(rowAttrs);
						StyleConstants.setBackground(highlightAttrs, oldSide ? REMOVE_HIGHLIGHT_OR(line) : ADD_HIGHLIGHT_OR(line));
						doc.insertString(doc.getLength(), text.substring(range[0], range[1]), highlightAttrs);
						cursor = range[1];
					}
					if (cursor < text.length()) {
						doc.insertString(doc.getLength(), text.substring(cursor), rowAttrs);
					}
					doc.insertString(doc.getLength(), "\n", rowAttrs);
				}
			}
		} catch (BadLocationException e) {
			throw new IllegalStateException(e);
		}
	}

	private Color REMOVE_HIGHLIGHT_OR(DiffLine line) {
		return line.tag() == DiffLine.Tag.DELETE ? REMOVE_HIGHLIGHT : CHANGE_HIGHLIGHT;
	}

	private Color ADD_HIGHLIGHT_OR(DiffLine line) {
		return line.tag() == DiffLine.Tag.INSERT ? ADD_HIGHLIGHT : CHANGE_HIGHLIGHT;
	}

	private SimpleAttributeSet rowBackground(DiffLine.Tag tag, boolean oldSide) {
		SimpleAttributeSet attrs = new SimpleAttributeSet();
		Color background = switch (tag) {
			case INSERT -> oldSide ? null : ADD_BACKGROUND;
			case DELETE -> oldSide ? REMOVE_BACKGROUND : null;
			case CHANGE -> CHANGE_BACKGROUND;
			case EQUAL -> null;
		};
		if (background != null) {
			StyleConstants.setBackground(attrs, background);
		}
		return attrs;
	}

	/** Jumps to the next changed row (spec section 10.3) - wraps to the first change past the end. No-op if there are no changes. */
	public void nextChange() {
		if (currentDiff == null || currentDiff.changedRowIndices().isEmpty()) {
			return;
		}
		var indices = currentDiff.changedRowIndices();
		int next = 0;
		for (int i = 0; i < indices.size(); i++) {
			if (indices.get(i) > currentChangeCursor) {
				next = i;
				break;
			}
		}
		currentChangeCursor = indices.get(next);
		scrollToLine(currentChangeCursor);
	}

	/** Jumps to the previous changed row - wraps to the last change before the beginning. No-op if there are no changes. */
	public void previousChange() {
		if (currentDiff == null || currentDiff.changedRowIndices().isEmpty()) {
			return;
		}
		var indices = currentDiff.changedRowIndices();
		int previous = indices.size() - 1;
		for (int i = indices.size() - 1; i >= 0; i--) {
			if (indices.get(i) < currentChangeCursor) {
				previous = i;
				break;
			}
		}
		currentChangeCursor = indices.get(previous);
		scrollToLine(currentChangeCursor);
	}

	private void scrollToLine(int lineIndex) {
		SwingUtilities.invokeLater(() -> {
			try {
				int offset = newPane.getDocument().getDefaultRootElement().getElement(lineIndex).getStartOffset();
				newPane.setCaretPosition(offset);
				java.awt.Rectangle rect = newPane.modelToView2D(offset).getBounds();
				newPane.scrollRectToVisible(rect);
				oldPane.scrollRectToVisible(rect);
			} catch (Exception ignored) {
				// Best-effort navigation - a stale line index (content changed since the diff was computed) simply does nothing.
			}
		});
	}
}
