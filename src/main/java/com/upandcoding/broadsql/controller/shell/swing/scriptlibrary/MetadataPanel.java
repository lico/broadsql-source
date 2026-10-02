package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.JTextComponent;

import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.MetadataDirectiveEditor;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;

/**
 * Metadata assistance (SPRINT 0917-01, spec section 18): one field per known {@link EntryMetadata}
 * property, a tooltip per field ({@link MetadataFieldHelp}) so a user never has to consult external
 * documentation to remember the supported keys/syntax. This panel is simply another editor for the
 * same one document (spec section 3/11, corrective acceptance pass) - there is no separate "Apply to
 * Script" step: {@link #load} populates the fields from a freshly parsed {@link ScriptMetadataHeader},
 * and editing a field commits it on focus-loss or Enter (never on every keystroke, to avoid caret/focus
 * churn - spec section 3.2) via {@link #setCommitListener}. This panel never edits the
 * {@code RSyntaxTextArea} directly; the caller ({@code ScriptLibraryFrame}) owns that.
 *
 * <p>SPRINT 3009A correction pass: a commit is a {@link Change}, the edited fields only, which the caller applies
 * to the editor's <em>current</em> text with {@link MetadataDirectiveEditor}: only the edited directive's
 * characters change. Before, the panel rebuilt the whole header from the model it had loaded, which moved the
 * edited line and overwrote anything typed into the header since the Script was loaded (comments included).
 * Clearing a field removes its directive: {@code @instance}/{@code @environment}'s displayed {@code NONE} sentinel
 * means "not declared", never a literal value (a literal {@code NONE} would parse as a real, single id).
 *
 * <p>Database Group and Environment are {@link SearchableMultiSelect} selectors, not free text: their values come
 * from the CDF ({@link #setChoicesSupplier}), the same ids Save accepts, so a misspelled id can no longer be
 * entered. They still read and write the same {@code @instance}/{@code @environment} directives with the same
 * text ({@code ALL}, comma-joined ids, or no directive for {@code NONE}).
 */
public final class MetadataPanel extends JPanel implements Scrollable {

	/** Description is one value (one header line): a few visible lines to read it comfortably, but typed or pasted line breaks become spaces, and Enter commits. */
	private final JTextArea descriptionField = new JTextArea(4, 10);
	/** Database Group(s) ({@code @instance}): chosen from the known Groups, never typed free (unless no CDF is loaded). */
	private final SearchableMultiSelect groupSelect = new SearchableMultiSelect(new MultiSelectModel(EntryMetadata.INSTANCE_ALL, "Database Groups"));
	/** Environment(s) ({@code @environment}): chosen from the known Environments, as {@link #groupSelect}. */
	private final SearchableMultiSelect environmentSelect = new SearchableMultiSelect(new MultiSelectModel(EntryMetadata.ENVIRONMENT_ALL, "Environments"));
	/** Where the selectors' values and their relations come from, asked again each time a list opens. */
	private java.util.function.Supplier<MetadataChoices> choicesSupplier = MetadataChoices::unknown;
	private final JTextField tagsField = new JTextField();
	/** Status is a controlled list ({@link EntryMetadata#VALID_STATUSES}, plus a blank "not specified"), never free text. */
	private final JComboBox<String> statusCombo = new JComboBox<>(new DefaultComboBoxModel<>());
	/** SPRINT 0110A, read only: the arguments each call must pass, from the Script's {@code -- @params:} lines (edited in the text). */
	private final JTextField paramsField = new JTextField();
	/** Read only: the Script's path in the Scripts Library ({@code reports/QR13.sql}); its tooltip gives the full filesystem path. */
	private final JTextField pathField = new JTextField();
	/** Read only: the file's last modification time on disk. */
	private final JTextField modifiedField = new JTextField();
	private final JButton copyPathButton = new JButton(EditorIcons.get(EditorIcons.Kind.COPY));
	private Consumer<ScriptPathText.Kind> pathCopyHandler;
	private int nextRow;

	/** True while {@link #load} repopulates the fields: programmatic selection changes must never count as user edits. */
	private boolean loading;
	/** Whether a Script's metadata has been loaded: nothing is committed before. */
	private boolean loaded;
	/** The field values as of the last {@link #load}/commit: only a field whose value differs from this is ever written back, so merely loading a Script never rewrites (and so never dirties) its header. */
	private java.util.Map<String, String> baselineValues = new java.util.HashMap<>();
	private Consumer<Change> commitListener;

	/**
	 * The metadata fields the user changed, in form order: key to new value, {@code null} for "remove the
	 * directive". Applied to the Script's current text with {@link #applyTo}.
	 */
	public record Change(java.util.Map<String, String> fields) {

		/** {@code text} with only the changed directives patched ({@link MetadataDirectiveEditor#apply}). */
		public String applyTo(String text) {
			return MetadataDirectiveEditor.apply(text, fields);
		}

		public boolean isEmpty() {
			return fields.isEmpty();
		}
	}

	public MetadataPanel() {
		buildUi();
	}

	/**
	 * One column, each label above its field, sized for the Editor's narrow right-hand pane. The editable fields
	 * come first, unchanged in behavior; the read-only file information (path, last modified) follows.
	 */
	private void buildUi() {
		setLayout(new GridBagLayout());
		setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

		descriptionField.setLineWrap(true);
		descriptionField.setWrapStyleWord(true);
		descriptionField.setFont(tagsField.getFont());
		((AbstractDocument) descriptionField.getDocument()).setDocumentFilter(new SingleLineFilter());
		descriptionField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "commit");
		descriptionField.getActionMap().put("commit", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				commitIfChanged();
			}
		});
		// Tab moves to the next field, as in a text field, instead of inserting a tab character.
		descriptionField.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
		descriptionField.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
		JScrollPane descriptionScroll = new JScrollPane(descriptionField, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		descriptionScroll.setMinimumSize(new Dimension(40, descriptionScroll.getPreferredSize().height));

		addRow("Description", descriptionScroll, descriptionField, MetadataFieldHelp.DESCRIPTION);
		addRow("Instance (Database Group)", groupSelect, groupSelect.inputField(), MetadataFieldHelp.INSTANCE);
		groupSelect.setToolTipText(MetadataFieldHelp.INSTANCE);
		addRow("Environment", environmentSelect, environmentSelect.inputField(), MetadataFieldHelp.ENVIRONMENT);
		environmentSelect.setToolTipText(MetadataFieldHelp.ENVIRONMENT);
		groupSelect.setBeforeOpen(model -> {
			MetadataChoices choices = choices();
			model.setOptions(choices.groups());
			model.setRelevant(choices.groupsCompatibleWith(environmentSelect.model().selected()), "With a connection in the selected Environments");
		});
		environmentSelect.setBeforeOpen(model -> {
			MetadataChoices choices = choices();
			model.setOptions(choices.environments());
			model.setRelevant(choices.environmentsCompatibleWith(groupSelect.model().selected()), "Used by the selected Database Groups");
		});
		groupSelect.setCommitListener(this::commitIfChanged);
		environmentSelect.setCommitListener(this::commitIfChanged);
		addRow("Tags", tagsField, tagsField, MetadataFieldHelp.TAGS);
		addRow("Status", statusCombo, statusCombo, MetadataFieldHelp.STATUS);
		fillStatusOptions(null);
		addRow("Parameters", paramsField, paramsField, "The arguments each call must pass, declared by -- @params: lines in the Script. Read only: edit the @params line in the text.");
		paramsField.setText("none declared");

		for (JTextField readOnly : List.of(pathField, modifiedField, paramsField)) {
			readOnly.setEditable(false);
			readOnly.setColumns(1);
		}
		copyPathButton.setToolTipText("Copy the library path (right-click for more)");
		copyPathButton.getAccessibleContext().setAccessibleName("Copy Path");
		copyPathButton.putClientProperty("JButton.buttonType", "toolBarButton");
		copyPathButton.setFocusable(false);
		copyPathButton.addActionListener(e -> copyPath(ScriptPathText.Kind.LIBRARY_PATH));
		JPopupMenu copyMenu = new JPopupMenu();
		for (ScriptPathText.Kind kind : ScriptPathText.Kind.values()) {
			JMenuItem item = new JMenuItem(kind.label);
			item.addActionListener(e -> copyPath(kind));
			copyMenu.add(item);
		}
		pathField.setComponentPopupMenu(copyMenu);
		copyPathButton.setComponentPopupMenu(copyMenu);
		JPanel pathRow = new JPanel(new BorderLayout(2, 0));
		pathRow.setOpaque(false);
		pathRow.add(pathField, BorderLayout.CENTER);
		pathRow.add(copyPathButton, BorderLayout.EAST);
		addSeparatorRow();
		addRow("Path", pathRow, pathField, "The Script's path in the Scripts Library. Read only: use Rename, or drag it in the Scripts tree, to change it.");
		addRow("Last modified", modifiedField, modifiedField, "When the file was last written on disk. Read only.");
		showFile(null, null, null);

		// Pushes the rows to the top of a tall pane.
		GridBagConstraints filler = new GridBagConstraints();
		filler.gridx = 0;
		filler.gridy = nextRow++;
		filler.weighty = 1;
		add(Box.createGlue(), filler);

		FocusAdapter commitOnFocusLost = new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				commitIfChanged();
			}
		};
		descriptionField.addFocusListener(commitOnFocusLost);
		tagsField.addFocusListener(commitOnFocusLost);
		tagsField.addActionListener(e -> commitIfChanged());
		statusCombo.addFocusListener(commitOnFocusLost);
		statusCombo.addActionListener(e -> commitIfChanged());
	}

	/** Label on its own line, then the field, full width. {@code target} is what the label names and what carries the tooltip. */
	private void addRow(String label, JComponent field, JComponent target, String tooltip) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.WEST;
		c.gridy = nextRow++;
		c.insets = new Insets(nextRow == 1 ? 0 : 8, 0, 3, 0);
		JLabel l = new JLabel(label);
		l.setToolTipText(tooltip);
		l.setLabelFor(target);
		add(l, c);
		c.gridy = nextRow++;
		c.insets = new Insets(0, 0, 0, 0);
		target.setToolTipText(tooltip);
		target.getAccessibleContext().setAccessibleName(label);
		add(field, c);
	}

	private void addSeparatorRow() {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = nextRow++;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(12, 0, 0, 0);
		add(new JSeparator(), c);
	}

	/**
	 * Shows the read-only file information of the Script the panel is bound to: its Scripts Library path, its full
	 * path (tooltip) and its last modification time on disk. All {@code null} when no Script is open.
	 */
	public void showFile(String libraryPath, String fullPath, Long lastModifiedMillis) {
		pathField.setText(libraryPath == null ? "" : libraryPath);
		pathField.setCaretPosition(0);
		pathField.setToolTipText(fullPath == null ? "The Script's path in the Scripts Library." : fullPath);
		modifiedField.setText(formatModified(lastModifiedMillis));
		copyPathButton.setEnabled(libraryPath != null);
	}

	/** The Last modified text: the Editor's date/time format, empty when unknown. */
	static String formatModified(Long lastModifiedMillis) {
		if (lastModifiedMillis == null || lastModifiedMillis <= 0) {
			return "";
		}
		return HistoryDialog.TIMESTAMP_FORMAT.format(java.time.Instant.ofEpochMilli(lastModifiedMillis));
	}

	/** Invoked with the kind of path to copy when the copy button or its menu is used; the caller writes the clipboard. */
	public void setPathCopyHandler(Consumer<ScriptPathText.Kind> pathCopyHandler) {
		this.pathCopyHandler = pathCopyHandler;
	}

	private void copyPath(ScriptPathText.Kind kind) {
		if (pathCopyHandler != null && !pathField.getText().isEmpty()) {
			pathCopyHandler.accept(kind);
		}
	}

	/** Test/inspection hook: the text shown for {@code key} ({@code description}, ..., {@code path}, {@code modified}); a selector's value as it is written ({@code ALL}, {@code NONE}, {@code A,B}). */
	String fieldText(String key) {
		return switch (key) {
			case "status" -> selectedStatus();
			case "instance" -> groupSelect.model().displayValue();
			case "environment" -> environmentSelect.model().displayValue();
			default -> ((JTextComponent) field(key)).getText();
		};
	}

	/**
	 * Where the Database Group and Environment selectors take their values and relations from (the CDF); asked each
	 * time a list opens, so Groups or Environments created meanwhile are offered. Without it, nothing is known and a
	 * typed value is accepted, as Save then does not check either.
	 */
	public void setChoicesSupplier(java.util.function.Supplier<MetadataChoices> choicesSupplier) {
		this.choicesSupplier = choicesSupplier == null ? MetadataChoices::unknown : choicesSupplier;
		applyChoices();
	}

	private MetadataChoices choices() {
		MetadataChoices choices = choicesSupplier.get();
		return choices == null ? MetadataChoices.unknown() : choices;
	}

	/** Gives both selectors their current options (their relations are refreshed when a list opens). */
	private void applyChoices() {
		MetadataChoices choices = choices();
		groupSelect.model().setOptions(choices.groups());
		environmentSelect.model().setOptions(choices.environments());
	}

	/** Test hook: the editing component for {@code key}. */
	JComponent field(String key) {
		return switch (key) {
			case "description" -> descriptionField;
			case "instance" -> groupSelect;
			case "environment" -> environmentSelect;
			case "tags" -> tagsField;
			case "status" -> statusCombo;
			case "path" -> pathField;
			case "modified" -> modifiedField;
			case "params" -> paramsField;
			default -> throw new IllegalArgumentException(key);
		};
	}

	/** Replaces any line break typed or pasted into Description with a space: Description is one header line. */
	private static final class SingleLineFilter extends DocumentFilter {
		@Override
		public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr) throws BadLocationException {
			super.insertString(fb, offset, flatten(text), attr);
		}

		@Override
		public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
			super.replace(fb, offset, length, flatten(text), attrs);
		}

		private static String flatten(String text) {
			return text == null ? null : text.replaceAll("\\s*\\R\\s*", " ");
		}
	}

	// Scrollable: the panel follows the pane's width (fields wrap/shrink, never a horizontal scroll bar) and scrolls vertically only when the pane is short.

	@Override
	public Dimension getPreferredScrollableViewportSize() {
		return getPreferredSize();
	}

	@Override
	public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
		return 16;
	}

	@Override
	public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
		return Math.max(16, visibleRect.height - 16);
	}

	@Override
	public boolean getScrollableTracksViewportWidth() {
		return true;
	}

	@Override
	public boolean getScrollableTracksViewportHeight() {
		return getParent() != null && getParent().getHeight() > getPreferredSize().height;
	}

	/** Invoked with the edited fields whenever a field commits (focus-lost/Enter) - the caller patches the editor buffer, which marks the tab dirty. */
	public void setCommitListener(Consumer<Change> commitListener) {
		this.commitListener = commitListener;
	}

	/** Populates every field from {@code header}'s current known values; they become the baseline nothing is written back for. */
	public void load(ScriptMetadataHeader header) {
		loaded = true;
		loading = true;
		try {
			EntryMetadata metadata = header.metadata();
			descriptionField.setText(nullToEmpty(metadata.getDescription()));
			applyChoices();
			groupSelect.model().load(metadata.getInstances(), metadata.isAllInstances());
			groupSelect.selectionReloaded();
			environmentSelect.model().load(metadata.getEnvironments(), metadata.isAllEnvironments());
			environmentSelect.selectionReloaded();
			tagsField.setText(metadata.getTagsDisplayValue());
			fillStatusOptions(metadata.getStatus());
			paramsField.setText(metadata.getParamsDisplayValue());
		} finally {
			loading = false;
		}
		baselineValues = currentFieldValues();
	}

	private java.util.Map<String, String> currentFieldValues() {
		java.util.Map<String, String> values = new java.util.HashMap<>();
		values.put("description", descriptionField.getText().trim());
		values.put("instance", groupSelect.model().displayValue());
		values.put("environment", environmentSelect.model().displayValue());
		values.put("tags", tagsField.getText().trim());
		values.put("status", selectedStatus().trim());
		return values;
	}

	/**
	 * Commits whichever field currently holds an uncommitted edit (idempotent - a no-op if nothing
	 * changed since the last commit/{@link #load}). The caller invokes this before switching the
	 * Metadata tab away from the currently-bound document and before {@code Save} (spec section 3.2),
	 * so a pending edit is never silently lost even if it never received a focus-lost/Enter of its own.
	 */
	public void commitPendingEdits() {
		commitIfChanged();
	}

	private void commitIfChanged() {
		if (!loaded || loading) {
			return;
		}
		Change change = pendingChange();
		if (change.isEmpty()) {
			return;
		}
		baselineValues = currentFieldValues();
		if (commitListener != null) {
			commitListener.accept(change);
		}
	}

	/**
	 * The fields whose value differs from the baseline (the last {@link #load} or commit), with the value to
	 * write: only a field the user actually changed is ever patched, so an unedited Script never looks modified.
	 * Does not change this panel's state.
	 */
	public Change pendingChange() {
		java.util.Map<String, String> now = currentFieldValues();
		java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
		for (String key : List.of("description", "instance", "environment", "tags", "status")) {
			if (changed(now, key)) {
				boolean scope = key.equals("instance") || key.equals("environment");
				fields.put(key, directiveValue(now.get(key), scope));
			}
		}
		return new Change(fields);
	}

	private boolean changed(java.util.Map<String, String> now, String key) {
		return !now.get(key).equals(baselineValues.getOrDefault(key, ""));
	}

	/**
	 * The Status list: blank ("not specified") plus the authoritative {@link EntryMetadata#VALID_STATUSES}.
	 * A value already in the file that is not exactly one of them (a legacy/unknown value, or a case
	 * variant) is shown as one extra, selected item so it is displayed safely and round-trips unchanged;
	 * Save then reports it as invalid rather than this panel silently replacing it.
	 */
	@SuppressWarnings("unchecked")
	private void fillStatusOptions(String currentValue) {
		DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) statusCombo.getModel();
		model.removeAllElements();
		model.addElement("");
		for (String valid : EntryMetadata.VALID_STATUSES) {
			model.addElement(valid);
		}
		String value = currentValue == null ? "" : currentValue.trim();
		if (model.getIndexOf(value) < 0) {
			model.addElement(value);
		}
		model.setSelectedItem(value);
	}

	private String selectedStatus() {
		Object selected = statusCombo.getSelectedItem();
		return selected == null ? "" : selected.toString();
	}

	/** The status choices currently offered, in order (test/inspection hook). */
	public List<String> statusOptions() {
		List<String> options = new java.util.ArrayList<>();
		for (int i = 0; i < statusCombo.getItemCount(); i++) {
			options.add(statusCombo.getItemAt(i));
		}
		return options;
	}

	/**
	 * Moves keyboard focus to the field editing metadata {@code key} ({@code instance}, {@code environment},
	 * {@code status}, ...) - used when Save is refused because of that field's value. Unknown
	 * keys are ignored.
	 */
	public void focusField(String key) {
		java.awt.Component target = switch (key) {
			case "description" -> descriptionField;
			case "instance" -> groupSelect.inputField();
			case "environment" -> environmentSelect.inputField();
			case "tags" -> tagsField;
			case "status" -> statusCombo;
			default -> null;
		};
		if (target == null) {
			return;
		}
		target.requestFocusInWindow();
		if (target instanceof JTextComponent textField && target != groupSelect.inputField() && target != environmentSelect.inputField()) {
			textField.selectAll();
		}
	}

	/** The value to write for a field, {@code null} to remove its directive: blank, or for {@code @instance}/{@code @environment} the displayed {@code NONE} sentinel (see this class's own javadoc). */
	private static String directiveValue(String value, boolean scope) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty() || (scope && "NONE".equalsIgnoreCase(trimmed))) {
			return null;
		}
		return trimmed;
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
