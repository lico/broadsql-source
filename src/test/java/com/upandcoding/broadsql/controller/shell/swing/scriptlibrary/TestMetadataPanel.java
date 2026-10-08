package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.Component;
import java.awt.Container;

import javax.swing.text.JTextComponent;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;

/** {@link javax.swing.JPanel}-based, headlessly constructible - see {@code TestScriptLibraryBrowserPanel}'s own javadoc for the precedent. */
class TestMetadataPanel {

	@Test
	void loadPopulatesEveryFieldFromTheParsedHeader() {
		MetadataPanel panel = new MetadataPanel();
		ScriptMetadataHeader header = ScriptMetadataHeader.parse(
				"-- @description: Monthly revenue\n-- @environment: PROD\n-- @tags: finance,monthly\n-- @alias: rev\n-- @status: stable\nselect 1;");

		panel.load(header);

		Assertions.assertEquals("Monthly revenue", textFieldAt(panel, 0));
		Assertions.assertEquals("NONE", panel.fieldText("instance"), "no @instance tag - must show the NONE sentinel");
		Assertions.assertEquals(java.util.List.of(), ((SearchableMultiSelect) panel.field("instance")).chipTexts());
		Assertions.assertEquals("PROD", panel.fieldText("environment"));
		Assertions.assertEquals(java.util.List.of("PROD"), ((SearchableMultiSelect) panel.field("environment")).chipTexts());
		Assertions.assertEquals("finance,monthly", textFieldAt(panel, 1));
		Assertions.assertEquals("stable", statusCombo(panel).getSelectedItem());
	}

	/** Status is a controlled list, not a free-text field: exactly the authoritative vocabulary plus "not specified". */
	@Test
	void statusIsAControlledListOfTheAuthoritativeValues() {
		MetadataPanel panel = new MetadataPanel();
		panel.load(ScriptMetadataHeader.parse("select 1;"));

		Assertions.assertEquals(2, allTextFields(panel).size(), "only Description and Tags remain free text: Database Group and Environment are selectors, Status a list, Path/Last modified read only");
		Assertions.assertEquals(java.util.List.of("", "draft", "stable", "deprecated"), panel.statusOptions());
		Assertions.assertEquals(com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata.VALID_STATUSES, panel.statusOptions().subList(1, 4));
	}

	/** New assets are seeded with ScriptLibraryService.newAssetSeedContent(): the panel must show draft selected. */
	@Test
	void aNewAssetsSeedShowsDraftSelected() {
		MetadataPanel panel = new MetadataPanel();

		panel.load(ScriptMetadataHeader.parse(com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService.newAssetSeedContent()));

		Assertions.assertEquals("draft", statusCombo(panel).getSelectedItem());
	}

	/** An unknown legacy status is displayed safely (kept as an extra, selected item) and is never silently replaced. */
	@Test
	void aLegacyUnknownStatusIsDisplayedAndRoundTripsUnchanged() {
		MetadataPanel panel = new MetadataPanel();
		ScriptMetadataHeader header = ScriptMetadataHeader.parse("-- @status: obsolete-thing\nselect 1;");
		java.util.List<MetadataPanel.Change> committed = new java.util.ArrayList<>();
		panel.setCommitListener(committed::add);

		panel.load(header);

		Assertions.assertEquals("obsolete-thing", statusCombo(panel).getSelectedItem());
		Assertions.assertTrue(panel.statusOptions().contains("obsolete-thing"));
		Assertions.assertTrue(committed.isEmpty(), "loading must never count as an edit");
		Assertions.assertTrue(panel.pendingChange().isEmpty(), "an untouched legacy value is never rewritten");
	}

	@Test
	void loadingAStableFileDoesNotMarkItEdited() {
		MetadataPanel panel = new MetadataPanel();
		java.util.List<MetadataPanel.Change> committed = new java.util.ArrayList<>();
		panel.setCommitListener(committed::add);

		panel.load(ScriptMetadataHeader.parse("-- @status: stable\nselect 1;"));
		panel.commitPendingEdits();

		Assertions.assertTrue(committed.isEmpty(), "programmatic selection during load must not commit");
	}

	@Test
	void focusFieldTargetsTheMatchingMetadataField() {
		MetadataPanel panel = new MetadataPanel();
		panel.load(ScriptMetadataHeader.parse("select 1;"));

		Assertions.assertDoesNotThrow(() -> {
			for (String key : new String[] { "description", "instance", "environment", "tags", "alias", "status", "no-such-key" }) {
				panel.focusField(key);
			}
		});
	}

	private static javax.swing.JComboBox<?> statusCombo(Container container) {
		for (Component c : container.getComponents()) {
			if (c instanceof javax.swing.JComboBox<?> combo) {
				return combo;
			}
			if (c instanceof Container inner) {
				javax.swing.JComboBox<?> found = statusCombo(inner);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	@Test
	void theChangeReflectsAnEditedFieldAndPreservesUnknownLines() {
		MetadataPanel panel = new MetadataPanel();
		String original = "-- @description: old\n-- @future-key: kept\nselect 1;";
		panel.load(ScriptMetadataHeader.parse(original));
		setTextFieldAt(panel, 0, "new description");

		ScriptMetadataHeader updated = ScriptMetadataHeader.parse(panel.pendingChange().applyTo(original));

		Assertions.assertEquals("new description", updated.metadata().getDescription());
		Assertions.assertTrue(updated.rawLines().stream().anyMatch(l -> l.contains("@future-key: kept")), updated.rawLines().toString());
	}

	@Test
	void clearingInstanceRoundTripsBackToTheNoneSentinelNotALiteralValue() {
		MetadataPanel panel = new MetadataPanel();
		String original = "-- @instance: MYSAP\nselect 1;";
		panel.load(ScriptMetadataHeader.parse(original));
		java.util.List<MetadataPanel.Change> committed = new java.util.ArrayList<>();
		panel.setCommitListener(committed::add);
		((SearchableMultiSelect) panel.field("instance")).clickRemove("MYSAP");

		Assertions.assertEquals(1, committed.size(), "removing a chip commits at once");
		ScriptMetadataHeader updated = ScriptMetadataHeader.parse(committed.get(0).applyTo(original));

		Assertions.assertEquals("NONE", updated.metadata().instanceDisplayValue());
		Assertions.assertFalse(updated.rawLines().stream().anyMatch(l -> l.toUpperCase().contains("@INSTANCE")), updated.rawLines().toString());
	}

	/**
	 * SPRINT 0917-01 corrective acceptance pass, defect 8/requirement 3: "Apply to Script" is removed
	 * entirely - editing a field commits directly into the buffer on Enter (this test) or focus-loss
	 * (see below), with no separate button/step.
	 */
	@Test
	void commitListenerIsInvokedWhenAFieldCommitsViaEnter() {
		MetadataPanel panel = new MetadataPanel();
		panel.load(ScriptMetadataHeader.parse("select 1;"));
		java.util.List<MetadataPanel.Change> committed = new java.util.ArrayList<>();
		panel.setCommitListener(committed::add);

		// SPRINT 3009A: Description is a multi-line area whose Enter key commits (its "commit" key binding) instead of breaking the line.
		JTextComponent descriptionField = allTextFields(panel).get(0);
		descriptionField.setText("new description");
		javax.swing.Action enter = descriptionField.getActionMap().get(descriptionField.getInputMap().get(javax.swing.KeyStroke.getKeyStroke("ENTER")));
		enter.actionPerformed(new java.awt.event.ActionEvent(descriptionField, java.awt.event.ActionEvent.ACTION_PERFORMED, ""));

		Assertions.assertEquals(1, committed.size());
		Assertions.assertEquals("new description", ScriptMetadataHeader.parse(committed.get(0).applyTo("select 1;")).metadata().getDescription());
	}

	@Test
	void commitListenerIsInvokedOnFocusLost() {
		MetadataPanel panel = new MetadataPanel();
		panel.load(ScriptMetadataHeader.parse("select 1;"));
		java.util.List<MetadataPanel.Change> committed = new java.util.ArrayList<>();
		panel.setCommitListener(committed::add);

		JTextComponent tagsField = allTextFields(panel).get(1);
		tagsField.setText("draft");
		for (var listener : tagsField.getFocusListeners()) {
			listener.focusLost(new java.awt.event.FocusEvent(tagsField, java.awt.event.FocusEvent.FOCUS_LOST));
		}

		Assertions.assertEquals(1, committed.size());
		Assertions.assertEquals("draft", ScriptMetadataHeader.parse(committed.get(0).applyTo("select 1;")).metadata().getTagsDisplayValue());
	}

	@Test
	void selectingAStatusFromTheListCommitsIt() {
		MetadataPanel panel = new MetadataPanel();
		panel.load(ScriptMetadataHeader.parse("select 1;"));
		java.util.List<MetadataPanel.Change> committed = new java.util.ArrayList<>();
		panel.setCommitListener(committed::add);

		@SuppressWarnings("unchecked")
		javax.swing.JComboBox<String> combo = (javax.swing.JComboBox<String>) statusCombo(panel);
		combo.setSelectedItem("stable");

		Assertions.assertEquals(1, committed.size());
		Assertions.assertEquals("stable", ScriptMetadataHeader.parse(committed.get(0).applyTo("select 1;")).metadata().getStatus());
	}

	/** Spec section 3.2: typing alone must never commit - only an explicit flush (tab switch/Save) does, via {@link MetadataPanel#commitPendingEdits()}. */
	@Test
	void commitPendingEditsFlushesAnUncommittedFieldWithoutRequiringFocusLoss() {
		MetadataPanel panel = new MetadataPanel();
		panel.load(ScriptMetadataHeader.parse("select 1;"));
		java.util.List<MetadataPanel.Change> committed = new java.util.ArrayList<>();
		panel.setCommitListener(committed::add);

		setTextFieldAt(panel, 0, "typed but not yet committed");
		Assertions.assertTrue(committed.isEmpty(), "typing alone must not commit - spec section 3.2");

		panel.commitPendingEdits();

		Assertions.assertEquals(1, committed.size());
		Assertions.assertEquals("typed but not yet committed", ScriptMetadataHeader.parse(committed.get(0).applyTo("select 1;")).metadata().getDescription());
	}

	private static String textFieldAt(Container container, int index) {
		JTextComponent field = allTextFields(container).get(index);
		return field.getText();
	}

	private static void setTextFieldAt(Container container, int index, String text) {
		allTextFields(container).get(index).setText(text);
	}

	/** The editable free-text fields, in form order: Description, Tags (the selectors' filter fields are not free text). */
	private static java.util.List<JTextComponent> allTextFields(Container container) {
		java.util.List<JTextComponent> fields = new java.util.ArrayList<>();
		collectTextFields(container, fields);
		return fields;
	}

	private static void collectTextFields(Container container, java.util.List<JTextComponent> out) {
		for (Component c : container.getComponents()) {
			if (c instanceof SearchableMultiSelect) {
				continue;
			}
			if (c instanceof JTextComponent field) {
				if (field.isEditable() && !(c.getParent() instanceof javax.swing.JComboBox)) {
					out.add(field);
				}
			} else if (c instanceof Container inner) {
				collectTextFields(inner, out);
			}
		}
	}
}
