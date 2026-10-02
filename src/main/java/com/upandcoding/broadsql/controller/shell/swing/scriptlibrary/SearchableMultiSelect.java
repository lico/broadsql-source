package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 * A compact, searchable multi-select field (the Metadata tab's Database Group and Environment selectors): the
 * selected values are chips, each with a remove button, followed by a filter field. Clicking the field, typing,
 * or Down opens a list of every offered value with a check box; the list is the {@link MultiSelectModel}'s rows.
 *
 * <p>Keyboard: typing filters, Up/Down move the highlight, Enter selects or unselects the highlighted value (or,
 * list closed, commits), Esc closes the list (with the list closed it is left to the window), Backspace in an empty filter removes the last chip, Tab leaves the
 * field. Focus stays in the filter field the whole time (the list never takes it), so the Editor's focus traversal
 * is unchanged.
 *
 * <p>Edits are reported as one commit when the list closes, when a chip is removed with the list closed, on Enter
 * with the list closed, and when the field loses focus: never on every click, so the Script's header is patched
 * once per round of choices.
 */
public final class SearchableMultiSelect extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final int VISIBLE_ROWS = 12;

	private final MultiSelectModel model;
	private final JPanel chipsPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 3, 2));
	private final JTextField input = new JTextField(6);
	private final DefaultListModel<MultiSelectModel.Row> listModel = new DefaultListModel<>();
	private final JList<MultiSelectModel.Row> list = new JList<>(listModel);
	private final JPopupMenu popup = new JPopupMenu();
	/** Refreshes the model's options and context just before the list opens (the CDF and the other selector may have changed). */
	private Consumer<MultiSelectModel> beforeOpen;
	private Runnable commitListener;
	/** The list is logically open (it is only displayed when this field is on screen). */
	private boolean open;
	private boolean updatingFilter;

	public SearchableMultiSelect(MultiSelectModel model) {
		super(new BorderLayout());
		this.model = model;
		buildUi();
	}

	private void buildUi() {
		setBorder(UIManager.getBorder("TextField.border"));
		setBackground(UIManager.getColor("TextField.background"));
		chipsPanel.setOpaque(false);
		add(chipsPanel, BorderLayout.CENTER);

		input.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		input.setOpaque(false);
		input.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				filterChanged();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				filterChanged();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				filterChanged();
			}
		});
		bind("DOWN", "highlightNext", () -> moveHighlight(1));
		bind("UP", "highlightPrevious", () -> moveHighlight(-1));
		bind("ENTER", "toggleOrCommit", this::enter);
		bind("ESCAPE", "closeList", this::closeList, () -> open);
		bind("BACK_SPACE", "removeLastOrDelete", this::backspace);
		input.addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				if (!e.isTemporary()) {
					closeList();
					fireCommit();
				}
			}
		});

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFocusable(false);
		list.setCellRenderer(new RowRenderer());
		list.setVisibleRowCount(VISIBLE_ROWS);
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				int index = list.locationToIndex(e.getPoint());
				if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) {
					activate(index);
				}
			}
		});
		JScrollPane scroll = new JScrollPane(list);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		popup.setFocusable(false);
		popup.setLayout(new BorderLayout());
		popup.add(scroll, BorderLayout.CENTER);
		popup.addPopupMenuListener(new PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
				// Closed by a click elsewhere, or by closeList().
				if (open) {
					open = false;
					fireCommit();
				}
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e) {
			}
		});

		MouseAdapter openOnClick = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				input.requestFocusInWindow();
				if (!open) {
					openList();
				}
			}
		};
		addMouseListener(openOnClick);
		chipsPanel.addMouseListener(openOnClick);
		input.addMouseListener(openOnClick);
		rebuildChips();
	}

	private void bind(String key, String name, Runnable action) {
		bind(key, name, action, () -> true);
	}

	/** A disabled binding is not consumed, so the key reaches the window's own bindings (Esc with the list closed). */
	private void bind(String key, String name, Runnable action, java.util.function.BooleanSupplier enabledWhen) {
		input.getInputMap().put(KeyStroke.getKeyStroke(key), name);
		input.getActionMap().put(name, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				action.run();
			}

			@Override
			public boolean isEnabled() {
				return enabledWhen.getAsBoolean();
			}
		});
	}

	/** Called with the model just before the list opens, to refresh its options and context. */
	public void setBeforeOpen(Consumer<MultiSelectModel> beforeOpen) {
		this.beforeOpen = beforeOpen;
	}

	/** Invoked when a round of edits ends (see this class's javadoc); the listener compares against its own baseline. */
	public void setCommitListener(Runnable commitListener) {
		this.commitListener = commitListener;
	}

	public MultiSelectModel model() {
		return model;
	}

	/** Shows the model's current selection (after a programmatic {@link MultiSelectModel#load}). */
	public void selectionReloaded() {
		updatingFilter = true;
		try {
			input.setText("");
		} finally {
			updatingFilter = false;
		}
		model.setFilter("");
		rebuildChips();
		if (open) {
			refreshRows();
		}
	}

	/** Moves keyboard focus to the filter field. */
	public void focusInput() {
		input.requestFocusInWindow();
	}

	@Override
	public void setToolTipText(String text) {
		super.setToolTipText(text);
		input.setToolTipText(text);
		chipsPanel.setToolTipText(text);
	}

	/** The focusable part, which the label names. */
	public JTextField inputField() {
		return input;
	}

	public boolean isListOpen() {
		return open;
	}

	/** The rows the list currently offers. */
	public List<MultiSelectModel.Row> offeredRows() {
		List<MultiSelectModel.Row> rows = new java.util.ArrayList<>();
		for (int i = 0; i < listModel.size(); i++) {
			rows.add(listModel.get(i));
		}
		return rows;
	}

	/** The highlighted row's index in {@link #offeredRows()}, or {@code -1}. */
	public int highlightedIndex() {
		return list.getSelectedIndex();
	}

	public void openList() {
		if (beforeOpen != null) {
			beforeOpen.accept(model);
		}
		open = true;
		refreshRows();
		if (isShowing()) {
			Dimension size = list.getPreferredScrollableViewportSize();
			popup.setPopupSize(Math.max(getWidth(), 160), Math.min(size.height, 320) + 4);
			popup.show(this, 0, getHeight());
		}
	}

	public void closeList() {
		boolean wasOpen = open;
		open = false;
		if (popup.isVisible()) {
			popup.setVisible(false);
		}
		if (wasOpen) {
			fireCommit();
		}
	}

	private void refreshRows() {
		List<MultiSelectModel.Row> rows = model.rows();
		int previous = list.getSelectedIndex();
		String previousText = previous >= 0 && previous < listModel.size() ? listModel.get(previous).text() : null;
		listModel.clear();
		for (MultiSelectModel.Row row : rows) {
			listModel.addElement(row);
		}
		int keep = -1;
		for (int i = 0; previousText != null && i < rows.size(); i++) {
			if (rows.get(i).selectable() && rows.get(i).text().equals(previousText)) {
				keep = i;
				break;
			}
		}
		if (keep < 0) {
			keep = MultiSelectModel.nextSelectable(rows, -1, 1);
			if (!model.filter().isEmpty()) {
				// Typing highlights the first value, not the ALL keyword, unless ALL is all that matches.
				int firstValue = firstOfKind(rows, MultiSelectModel.Kind.VALUE);
				keep = firstValue >= 0 ? firstValue : keep;
			}
		}
		highlight(keep);
	}

	private static int firstOfKind(List<MultiSelectModel.Row> rows, MultiSelectModel.Kind kind) {
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i).kind() == kind) {
				return i;
			}
		}
		return -1;
	}

	private void highlight(int index) {
		if (index < 0) {
			list.clearSelection();
			return;
		}
		list.setSelectedIndex(index);
		list.ensureIndexIsVisible(index);
	}

	private void filterChanged() {
		if (updatingFilter) {
			return;
		}
		model.setFilter(input.getText());
		if (!open) {
			openList();
		} else {
			refreshRows();
		}
	}

	private void moveHighlight(int direction) {
		if (!open) {
			openList();
			return;
		}
		List<MultiSelectModel.Row> rows = offeredRows();
		int next = MultiSelectModel.nextSelectable(rows, list.getSelectedIndex(), direction);
		highlight(next);
	}

	private void enter() {
		int index = list.getSelectedIndex();
		if (open && index >= 0) {
			activate(index);
		} else if (open) {
			closeList();
		} else {
			fireCommit();
		}
	}

	private void backspace() {
		if (!input.getText().isEmpty()) {
			int start = input.getSelectionStart();
			int end = input.getSelectionEnd();
			try {
				if (start != end) {
					input.getDocument().remove(start, end - start);
				} else if (start > 0) {
					input.getDocument().remove(start - 1, 1);
				}
			} catch (javax.swing.text.BadLocationException ignored) {
				// The caret is always within the document.
			}
			return;
		}
		if (model.removeLast()) {
			selectionChanged();
			if (!open) {
				fireCommit();
			}
		}
	}

	/** Toggles the row at {@code index}; adding a typed value or toggling clears the filter so the full list shows again. */
	private void activate(int index) {
		if (index < 0 || index >= listModel.size()) {
			return;
		}
		MultiSelectModel.Row row = listModel.get(index);
		if (!row.selectable()) {
			return;
		}
		model.activate(row);
		if (row.kind() == MultiSelectModel.Kind.ADD && !input.getText().isEmpty()) {
			updatingFilter = true;
			try {
				input.setText("");
			} finally {
				updatingFilter = false;
			}
			model.setFilter("");
		}
		selectionChanged();
	}

	private void selectionChanged() {
		rebuildChips();
		if (open) {
			refreshRows();
		}
	}

	private void rebuildChips() {
		chipsPanel.removeAll();
		List<String> chips = model.chips();
		for (String value : chips) {
			chipsPanel.add(chip(value));
		}
		// With no chip the filter field has the row to itself: room for a hint. Next to chips it stays small.
		input.putClientProperty("JTextField.placeholderText", chips.isEmpty() ? "None, click or type to choose" : null);
		input.setColumns(chips.isEmpty() ? 16 : 5);
		chipsPanel.add(input);
		chipsPanel.revalidate();
		chipsPanel.repaint();
		revalidate();
	}

	private JComponent chip(String value) {
		JPanel chip = new JPanel(new BorderLayout(2, 0));
		Color base = UIManager.getColor("Button.background");
		chip.setBackground(base != null ? base : new Color(0xE6E6E6));
		chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") != null
				? UIManager.getColor("Component.borderColor") : Color.GRAY), BorderFactory.createEmptyBorder(0, 4, 0, 0)));
		JLabel label = new JLabel(value);
		chip.add(label, BorderLayout.CENTER);
		JButton remove = new JButton("×");
		remove.setMargin(new Insets(0, 3, 0, 3));
		remove.setFocusable(false);
		remove.setBorderPainted(false);
		remove.setContentAreaFilled(false);
		remove.setToolTipText("Remove " + value);
		remove.getAccessibleContext().setAccessibleName("Remove " + value);
		remove.addActionListener(e -> {
			model.remove(value);
			selectionChanged();
			if (!open) {
				fireCommit();
			}
			input.requestFocusInWindow();
		});
		chip.add(remove, BorderLayout.EAST);
		chip.getAccessibleContext().setAccessibleName(value);
		return chip;
	}

	/** The chips currently shown, as text (test/inspection hook). */
	public List<String> chipTexts() {
		List<String> texts = new java.util.ArrayList<>();
		for (Component c : chipsPanel.getComponents()) {
			if (c != input && c instanceof JPanel chip) {
				texts.add(((JLabel) chip.getComponent(0)).getText());
			}
		}
		return texts;
	}

	/** Clicks the remove button of the chip {@code value} (test hook for mouse removal). */
	void clickRemove(String value) {
		for (Component c : chipsPanel.getComponents()) {
			if (c != input && c instanceof JPanel chip && ((JLabel) chip.getComponent(0)).getText().equals(value)) {
				((JButton) chip.getComponent(1)).doClick();
				return;
			}
		}
	}

	/** Toggles the row at {@code index} as a click on it would (test hook for mouse selection). */
	void clickRow(int index) {
		activate(index);
	}

	/** Runs the input's key binding for {@code keyStroke} ({@code DOWN}, {@code ENTER}, ...), as a key press would (test hook). */
	boolean pressKey(String keyStroke) {
		Object name = input.getInputMap().get(KeyStroke.getKeyStroke(keyStroke));
		javax.swing.Action action = input.getActionMap().get(name);
		if (action == null || !action.isEnabled()) {
			return false;
		}
		action.actionPerformed(new ActionEvent(input, ActionEvent.ACTION_PERFORMED, keyStroke));
		return true;
	}

	/** Types {@code text} into the filter field, replacing it (test hook). */
	void typeFilter(String text) {
		input.setText(text);
	}

	private void fireCommit() {
		if (commitListener != null) {
			commitListener.run();
		}
	}

	/** Check box per value, bold heading per section, as the list's cells. */
	private final class RowRenderer implements ListCellRenderer<MultiSelectModel.Row> {
		private final JCheckBox check = new JCheckBox();
		private final JLabel heading = new JLabel();

		RowRenderer() {
			heading.setBorder(BorderFactory.createEmptyBorder(4, 4, 1, 4));
			heading.setFont(heading.getFont().deriveFont(Font.BOLD));
			Color disabled = UIManager.getColor("Label.disabledForeground");
			if (disabled != null) {
				heading.setForeground(disabled);
			}
			check.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 4));
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends MultiSelectModel.Row> l, MultiSelectModel.Row row, int index, boolean isSelected,
				boolean cellHasFocus) {
			if (row.kind() == MultiSelectModel.Kind.HEADING) {
				heading.setText(row.text());
				return heading;
			}
			check.setSelected(row.selected());
			check.setText(row.kind() == MultiSelectModel.Kind.ADD ? "Add \"" + row.text() + "\""
					: row.text());
			check.setBackground(isSelected ? l.getSelectionBackground() : l.getBackground());
			check.setForeground(isSelected ? l.getSelectionForeground() : l.getForeground());
			check.setOpaque(true);
			return check;
		}
	}

	/** A {@link FlowLayout} whose preferred height grows with the rows its components wrap onto (chips in a narrow pane). */
	static final class WrapLayout extends FlowLayout {
		private static final long serialVersionUID = 1L;

		WrapLayout(int align, int hgap, int vgap) {
			super(align, hgap, vgap);
		}

		@Override
		public Dimension preferredLayoutSize(Container target) {
			return layoutSize(target, true);
		}

		@Override
		public Dimension minimumLayoutSize(Container target) {
			Dimension minimum = layoutSize(target, false);
			minimum.width = 40;
			return minimum;
		}

		private Dimension layoutSize(Container target, boolean preferred) {
			synchronized (target.getTreeLock()) {
				Container sized = target;
				while (sized.getSize().width == 0 && sized.getParent() != null) {
					sized = sized.getParent();
				}
				int targetWidth = sized.getSize().width;
				if (targetWidth == 0) {
					targetWidth = Integer.MAX_VALUE;
				}
				Insets insets = target.getInsets();
				int maxWidth = targetWidth - (insets.left + insets.right + getHgap() * 2);
				Dimension dim = new Dimension(0, 0);
				int rowWidth = 0;
				int rowHeight = 0;
				for (Component m : target.getComponents()) {
					if (!m.isVisible()) {
						continue;
					}
					Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
					if (rowWidth + d.width > maxWidth && rowWidth > 0) {
						dim.width = Math.max(dim.width, rowWidth);
						dim.height += rowHeight + getVgap();
						rowWidth = 0;
						rowHeight = 0;
					}
					rowWidth += d.width + (rowWidth > 0 ? getHgap() : 0);
					rowHeight = Math.max(rowHeight, d.height);
				}
				dim.width = Math.max(dim.width, rowWidth);
				dim.height += rowHeight;
				dim.width += insets.left + insets.right + getHgap() * 2;
				dim.height += insets.top + insets.bottom + getVgap() * 2;
				// Inside a scroll pane, leave room so the last row never triggers a horizontal scroll bar.
				Container scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
				if (scrollPane != null && target.isValid()) {
					dim.width -= getHgap() + 1;
				}
				return dim;
			}
		}
	}
}
