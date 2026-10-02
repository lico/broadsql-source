package com.upandcoding.broadsql.controller.shell.swing.api;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JTable;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.dao.api.model.ApiAttribute;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;

/**
 * API Quality and UX Consolidation sprint, Phase 2: the two additions {@link JApiAttributeTablePanel}
 * gained to support live URL/query-parameter synchronization and value-snapshot dirty tracking -
 * {@link JApiAttributeTablePanel#setOnChange} and {@link JApiAttributeTablePanel#snapshotKey()}. No
 * component/pixel rendering exercised - construction and programmatic table-model mutation only, same
 * headless-structural convention {@code TestJApiAuthenticationPanelMasking} already establishes.
 */
class TestJApiAttributeTablePanel {

	@Test
	void onChangeFiresOnAProgrammaticSetAttributesCall() {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false);
		AtomicInteger fired = new AtomicInteger();
		panel.setOnChange(fired::incrementAndGet);

		panel.setAttributes(List.of(attribute("limit", "10")));

		Assertions.assertTrue(fired.get() > 0, "setAttributes mutates the table model, which must notify onChange - callers wanting to ignore programmatic loads must guard themselves");
	}

	@Test
	void snapshotKeyChangesWhenARowIsAdded() {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false);
		panel.setAttributes(List.of(attribute("limit", "10")));
		String before = panel.snapshotKey();

		panel.setAttributes(List.of(attribute("limit", "10"), attribute("expand", "full")));

		Assertions.assertNotEquals(before, panel.snapshotKey());
	}

	@Test
	void snapshotKeyIsStableForIdenticalContent() {
		JApiAttributeTablePanel panelA = new JApiAttributeTablePanel(false);
		panelA.setAttributes(List.of(attribute("limit", "10"), attribute("expand", "full")));
		JApiAttributeTablePanel panelB = new JApiAttributeTablePanel(false);
		panelB.setAttributes(List.of(attribute("limit", "10"), attribute("expand", "full")));

		Assertions.assertEquals(panelA.snapshotKey(), panelB.snapshotKey());
	}

	@Test
	void snapshotKeyChangesWhenEnabledStateToggles() {
		ApiAttribute limit = attribute("limit", "10");
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false);
		panel.setAttributes(List.of(limit));
		String enabledKey = panel.snapshotKey();

		limit.setEnabled(false);
		panel.setAttributes(List.of(limit));

		Assertions.assertNotEquals(enabledKey, panel.snapshotKey());
	}

	private static JTable tableOf(JApiAttributeTablePanel panel) throws Exception {
		Field field = JApiAttributeTablePanel.class.getDeclaredField("table");
		field.setAccessible(true);
		return (JTable) field.get(panel);
	}

	/**
	 * SPRINT XT02B acceptance correction ("CONFIG API endpoint parameter cell editing breaks after
	 * filtering"): root cause was {@code snapshotKey()} unconditionally calling {@code
	 * stopCellEditing()}, forcibly ending any active cell edit every time it ran - and it is polled
	 * purely passively every ~500ms by {@code JApiSettingsFrame}'s dirty-indicator {@code Timer}, so a
	 * user editing a Value cell had their edit forcibly closed within half a second, unconditionally
	 * (not only after filtering - filtering was just how the acceptance test happened to trigger it).
	 * Asserts the fix: calling {@code snapshotKey()} while a cell is being edited must never end that
	 * edit.
	 */
	@Test
	void snapshotKeyDoesNotEndAnActiveCellEdit() throws Exception {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false);
		panel.setAttributes(List.of(attribute("siteId", "1")));
		JTable table = tableOf(panel);

		table.editCellAt(0, 1);
		Assertions.assertTrue(table.isEditing(), "test setup: the value cell must actually be in edit mode");

		panel.snapshotKey();

		Assertions.assertTrue(table.isEditing(), "snapshotKey() (polled every ~500ms by the dirty-indicator Timer) must never stop an active cell editor");
	}

	/** The dirty snapshot must still reflect an unsaved in-progress edit's live value, not the last-committed one - snapshotKey() peeks at the editor rather than ignoring it. */
	@Test
	void snapshotKeyReflectsTheLiveInProgressEditorValue() throws Exception {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false);
		panel.setAttributes(List.of(attribute("siteId", "1")));
		JTable table = tableOf(panel);
		String beforeEdit = panel.snapshotKey();

		table.editCellAt(0, 1);
		((javax.swing.JTextField) table.getEditorComponent()).setText("163");

		Assertions.assertNotEquals(beforeEdit, panel.snapshotKey(), "an in-progress, uncommitted edit must already be reflected in the dirty snapshot");
		Assertions.assertTrue(table.isEditing(), "reading the snapshot must not have ended the edit");
	}

	@Test
	void getAttributesRoundTripsDuplicateNamesInOrder() {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false);
		panel.setAttributes(List.of(attribute("tag", "admin"), attribute("tag", "verified")));

		List<ApiAttribute> result = panel.getAttributes(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.QUERY_PARAMETER);

		Assertions.assertEquals(2, result.size());
		Assertions.assertEquals("admin", result.get(0).getValue());
		Assertions.assertEquals("verified", result.get(1).getValue());
	}

	// ------------------------------------------------------------------------------------------
	// Re-entrant cell-edit commit (StackOverflowError on toggling Required): the real path is
	// stopCellEditing -> JTable.editingStopped -> setValueAt -> TableModelEvent -> listener -> getAttributes.
	// ------------------------------------------------------------------------------------------

	private static final int NAME = 0;
	private static final int VALUE = 1;
	private static final int REQUIRED = 2;
	private static final int TYPE = 3;
	private static final int DEFAULT = 4;
	private static final int ENABLED = 6;

	/** Panel wired like JApiEndpointEditorPanel: a change listener that reads the table's attributes. */
	private JApiAttributeTablePanel wiredPanel(AtomicInteger urlRelevantChanges, AtomicInteger reads) {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false, true);
		panel.setAttributes(List.of(attribute("limit", "10")));
		panel.setOnChange(() -> {
			reads.incrementAndGet();
			panel.getAttributes(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.QUERY_PARAMETER);
		});
		panel.setOnNameValueEnabledChange(() -> {
			urlRelevantChanges.incrementAndGet();
			panel.getAttributes(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.QUERY_PARAMETER);
		});
		return panel;
	}

	/** Starts an edit of the cell, applies {@code edit} to the live editor component and commits it, all on the EDT. */
	private void editAndCommit(JApiAttributeTablePanel panel, int row, int column, java.util.function.Consumer<java.awt.Component> edit) throws Exception {
		JTable table = tableOf(panel);
		javax.swing.SwingUtilities.invokeAndWait(() -> {
			table.editCellAt(row, column);
			Assertions.assertTrue(table.isEditing());
			edit.accept(table.getEditorComponent());
			table.getCellEditor().stopCellEditing();
			Assertions.assertFalse(table.isEditing(), "the edit must have been committed");
		});
	}

	private ApiAttribute first(JApiAttributeTablePanel panel) {
		return panel.getAttributes(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.QUERY_PARAMETER).get(0);
	}

	@Test
	void togglingRequiredCommitsWithoutRecursionAndKeepsTheValue() throws Exception {
		AtomicInteger urlChanges = new AtomicInteger();
		AtomicInteger reads = new AtomicInteger();
		JApiAttributeTablePanel panel = wiredPanel(urlChanges, reads);
		urlChanges.set(0);
		reads.set(0);

		editAndCommit(panel, 0, REQUIRED, c -> ((javax.swing.JCheckBox) c).setSelected(true));
		Assertions.assertTrue(first(panel).isRequired());
		Assertions.assertEquals(1, reads.get(), "exactly one model event, no re-entrant commit cycle");

		editAndCommit(panel, 0, REQUIRED, c -> ((javax.swing.JCheckBox) c).setSelected(false));
		Assertions.assertFalse(first(panel).isRequired());
	}

	@Test
	void repeatedRequiredTogglingNeverRecurses() throws Exception {
		JApiAttributeTablePanel panel = wiredPanel(new AtomicInteger(), new AtomicInteger());
		boolean state = false;
		for (int i = 0; i < 25; i++) {
			state = !state;
			boolean target = state;
			editAndCommit(panel, 0, REQUIRED, c -> ((javax.swing.JCheckBox) c).setSelected(target));
			Assertions.assertEquals(target, first(panel).isRequired());
		}
	}

	@Test
	void metadataColumnEditsDoNotTriggerUrlRefresh() throws Exception {
		AtomicInteger urlChanges = new AtomicInteger();
		JApiAttributeTablePanel panel = wiredPanel(urlChanges, new AtomicInteger());
		urlChanges.set(0);

		editAndCommit(panel, 0, REQUIRED, c -> ((javax.swing.JCheckBox) c).setSelected(true));
		editAndCommit(panel, 0, TYPE, c -> ((javax.swing.JTextField) c).setText("integer"));
		editAndCommit(panel, 0, DEFAULT, c -> ((javax.swing.JTextField) c).setText("5"));

		Assertions.assertEquals(0, urlChanges.get(), "Required/Type/Default never alter the composed URL");
		ApiAttribute result = first(panel);
		Assertions.assertTrue(result.isRequired());
		Assertions.assertEquals("integer", result.getParamType());
		Assertions.assertEquals("5", result.getDefaultValue());
	}

	@Test
	void enabledNameAndValueEditsStillTriggerUrlRefresh() throws Exception {
		AtomicInteger urlChanges = new AtomicInteger();
		JApiAttributeTablePanel panel = wiredPanel(urlChanges, new AtomicInteger());
		urlChanges.set(0);

		editAndCommit(panel, 0, ENABLED, c -> ((javax.swing.JCheckBox) c).setSelected(false));
		Assertions.assertEquals(1, urlChanges.get());
		Assertions.assertFalse(first(panel).isEnabled());

		editAndCommit(panel, 0, NAME, c -> ((javax.swing.JTextField) c).setText("max"));
		Assertions.assertEquals(2, urlChanges.get());
		editAndCommit(panel, 0, VALUE, c -> ((javax.swing.JTextField) c).setText("99"));
		Assertions.assertEquals(3, urlChanges.get());
		Assertions.assertEquals("max", first(panel).getName());
		Assertions.assertEquals("99", first(panel).getValue());
	}

	@Test
	void pathParameterPanelEditsWork() throws Exception {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false, true);
		ApiAttribute id = new ApiAttribute(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.PATH_PARAMETER, "id", "1", false);
		panel.setAttributes(List.of(id));

		editAndCommit(panel, 0, VALUE, c -> ((javax.swing.JTextField) c).setText("42"));
		editAndCommit(panel, 0, REQUIRED, c -> ((javax.swing.JCheckBox) c).setSelected(true));

		ApiAttribute result = panel.getAttributes(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.PATH_PARAMETER).get(0);
		Assertions.assertEquals("42", result.getValue());
		Assertions.assertTrue(result.isRequired());
	}

	@Test
	void headersPanelWithoutMetadataAlsoEditsSafely() throws Exception {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(true);
		AtomicInteger reads = new AtomicInteger();
		panel.setAttributes(List.of(new ApiAttribute(ApiOwnerType.API, "DESK", ApiAttributeKind.HEADER, "X-Key", "a", false)));
		panel.setOnChange(() -> {
			reads.incrementAndGet();
			panel.getAttributes(ApiOwnerType.API, "DESK", ApiAttributeKind.HEADER);
		});

		editAndCommit(panel, 0, 2, c -> ((javax.swing.JCheckBox) c).setSelected(true)); // Secret
		editAndCommit(panel, 0, 3, c -> ((javax.swing.JCheckBox) c).setSelected(false)); // Enabled

		ApiAttribute result = panel.getAttributes(ApiOwnerType.API, "DESK", ApiAttributeKind.HEADER).get(0);
		Assertions.assertTrue(result.isSecret());
		Assertions.assertFalse(result.isEnabled());
		Assertions.assertEquals(2, reads.get());
	}

	@Test
	void getAttributesNeverEndsAnActiveEditButGetCommittedAttributesDoes() throws Exception {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false, true);
		panel.setAttributes(List.of(attribute("limit", "10")));
		JTable table = tableOf(panel);
		javax.swing.SwingUtilities.invokeAndWait(() -> {
			table.editCellAt(0, VALUE);
			((javax.swing.JTextField) table.getEditorComponent()).setText("77");

			List<ApiAttribute> pure = panel.getAttributes(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.QUERY_PARAMETER);
			Assertions.assertTrue(table.isEditing(), "a pure read must not end the edit");
			Assertions.assertEquals("10", pure.get(0).getValue(), "an uncommitted edit is not part of a pure read");

			List<ApiAttribute> committed = panel.getCommittedAttributes(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.QUERY_PARAMETER);
			Assertions.assertFalse(table.isEditing());
			Assertions.assertEquals("77", committed.get(0).getValue());
		});
	}

	private ApiAttribute attribute(String name, String value) {
		return new ApiAttribute(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.QUERY_PARAMETER, name, value, false);
	}

	// ------------------------------------------------------------------------------------------
	// SPRINT XT02A (URL-Native API Execution), section 9.2 - the opt-in parameter-metadata columns
	// used only by the query/path parameter tables, round-tripping the same fields ApiParameterBinder
	// resolves against at RUN time.
	// ------------------------------------------------------------------------------------------

	@Test
	void parameterMetadataRoundTripsThroughSetAndGetAttributes() {
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(false, true);
		ApiAttribute id = attribute("id", null);
		id.setRequired(true);
		id.setParamType("integer");
		id.setDefaultValue("10");
		id.setAllowedValues("1|2|3");

		panel.setAttributes(List.of(id));
		ApiAttribute result = panel.getAttributes(ApiOwnerType.ENDPOINT, "1", ApiAttributeKind.PATH_PARAMETER).get(0);

		Assertions.assertTrue(result.isRequired());
		Assertions.assertEquals("integer", result.getParamType());
		Assertions.assertEquals("10", result.getDefaultValue());
		Assertions.assertEquals("1|2|3", result.getAllowedValues());
	}

	@Test
	void variablesAndHeadersPanelsAreUnaffectedByParameterMetadataMode() {
		// The one-argument constructor (used by Variables/Headers) must keep behaving exactly as before -
		// no Required/Type/Default/Allowed Values columns, same round-trip as every existing test above.
		JApiAttributeTablePanel panel = new JApiAttributeTablePanel(true);
		ApiAttribute secretVar = new ApiAttribute(ApiOwnerType.API, "DESK", ApiAttributeKind.VARIABLE, "token", "abc", true);

		panel.setAttributes(List.of(secretVar));
		ApiAttribute result = panel.getAttributes(ApiOwnerType.API, "DESK", ApiAttributeKind.VARIABLE).get(0);

		Assertions.assertTrue(result.isSecret());
		Assertions.assertEquals("abc", result.getValue());
		Assertions.assertFalse(result.isRequired(), "parameter metadata defaults must not leak into a non-parameter panel");
	}
}
