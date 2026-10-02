package com.upandcoding.broadsql.controller.shell.swing;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The one shared section header every Settings tab (Connections, Database Groups, Environments) uses:
 * a bold section title, the Active/Inactive/All "Show:" view switch, and an optional search field -
 * all inside one full-width, tinted strip (SPRINT 0911D GUI polish, corrective pass requirement 12:
 * "extract a reusable helper/component for the common section header... will help prevent Connections,
 * Groups and Environments drifting apart again").
 *
 * <p>Structural fix, not just extraction: before this class existed, {@code JDatabaseGroupsPanel} and
 * {@code JEnvironmentsPanel} each built their own "Show:" row and placed it in the {@code NORTH} of the
 * narrow left-hand list panel - inside the {@code JSplitPane}'s left side, not spanning the tab - so
 * "All" could be clipped whenever the split divider left that side narrower than the row's preferred
 * width (requirement 4/5's reported bug). Every caller of this class now adds it to its own top-level
 * panel's {@code BorderLayout.NORTH} - outside and above the {@code JSplitPane} entirely - so it always
 * spans the tab's full width and "Active"/"Inactive"/"All" can never be clipped by the split divider.
 *
 * <p>Deliberately plain Swing composition (a {@code JPanel} with a tinted background from
 * {@link BroadSqlLookAndFeel#styleFilterBar}, not custom painting).
 */
final class SettingsFilterBar extends JPanel {

	private final JLabel titleLabel;
	private final JRadioButton activeRadio;
	private final JRadioButton inactiveRadio;
	private final JRadioButton allRadio;
	private final JTextField searchField;

	private Consumer<SettingsViewMode> onViewChange;
	private Consumer<String> onSearchChange;

	/**
	 * @param title         initial section title (e.g. "Active connections", "Database Groups") - see {@link #setTitle}
	 * @param includeSearch whether to show a "Search:" field on the right (Connections only, so far)
	 */
	SettingsFilterBar(String title, boolean includeSearch) {
		setLayout(new BorderLayout());
		BroadSqlLookAndFeel.styleFilterBar(this);
		setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
				BorderFactory.createEmptyBorder(8, 12, 8, 12)));

		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
		left.setOpaque(false);
		titleLabel = BroadSqlLookAndFeel.sectionTitleLabel(title);
		left.add(titleLabel);
		left.add(new JLabel("Show:"));

		activeRadio = new JRadioButton("Active", true);
		inactiveRadio = new JRadioButton("Inactive", false);
		allRadio = new JRadioButton("All", false);
		activeRadio.setOpaque(false);
		inactiveRadio.setOpaque(false);
		allRadio.setOpaque(false);
		ButtonGroup group = new ButtonGroup();
		group.add(activeRadio);
		group.add(inactiveRadio);
		group.add(allRadio);
		activeRadio.addActionListener(e -> fireViewChange(SettingsViewMode.ACTIVE));
		inactiveRadio.addActionListener(e -> fireViewChange(SettingsViewMode.INACTIVE));
		allRadio.addActionListener(e -> fireViewChange(SettingsViewMode.ALL));
		left.add(activeRadio);
		left.add(inactiveRadio);
		left.add(allRadio);
		add(left, BorderLayout.WEST);

		if (includeSearch) {
			JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
			right.setOpaque(false);
			right.add(new JLabel("Search:"));
			searchField = new JTextField(18);
			searchField.getDocument().addDocumentListener(new DocumentListener() {
				@Override
				public void insertUpdate(DocumentEvent e) {
					fireSearchChange();
				}

				@Override
				public void removeUpdate(DocumentEvent e) {
					fireSearchChange();
				}

				@Override
				public void changedUpdate(DocumentEvent e) {
					fireSearchChange();
				}
			});
			right.add(searchField);
			add(right, BorderLayout.EAST);
		} else {
			searchField = null;
		}
	}

	void setOnViewChange(Consumer<SettingsViewMode> onViewChange) {
		this.onViewChange = onViewChange;
	}

	void setOnSearchChange(Consumer<String> onSearchChange) {
		this.onSearchChange = onSearchChange;
	}

	void setTitle(String text) {
		titleLabel.setText(text);
	}

	/**
	 * Syncs the radio selection to {@code mode} without notifying {@link #onViewChange} - plain
	 * {@code AbstractButton.setSelected(boolean)} never fires an {@code ActionListener} on its own (only
	 * a user click or {@code doClick()} does), so this is safe to call from a caller reacting to its own
	 * view-mode change (e.g. after {@code triggerSetViewMode} from the shared File/Edit/View menu)
	 * without causing a feedback loop.
	 */
	void setViewMode(SettingsViewMode mode) {
		activeRadio.setSelected(mode == SettingsViewMode.ACTIVE);
		inactiveRadio.setSelected(mode == SettingsViewMode.INACTIVE);
		allRadio.setSelected(mode == SettingsViewMode.ALL);
	}

	private void fireViewChange(SettingsViewMode mode) {
		if (onViewChange != null) {
			onViewChange.accept(mode);
		}
	}

	private void fireSearchChange() {
		if (onSearchChange != null) {
			onSearchChange.accept(searchField.getText());
		}
	}
}
