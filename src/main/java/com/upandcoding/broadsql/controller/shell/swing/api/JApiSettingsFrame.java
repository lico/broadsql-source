package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.swing.AppIcon;
import com.upandcoding.broadsql.controller.shell.swing.BroadSqlLookAndFeel;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthChoice;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiAuthFormModel;
import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiGeneralFormModel;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiAttributeKind;
import com.upandcoding.broadsql.dao.api.model.ApiAuthConfig;
import com.upandcoding.broadsql.dao.api.model.ApiDefinition;
import com.upandcoding.broadsql.dao.api.model.ApiImportSource;
import com.upandcoding.broadsql.dao.api.model.ApiOwnerType;
import com.upandcoding.broadsql.dao.api.model.ApiVersion;

/**
 * {@code CONFIG API}'s main window - docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno YAML
 * Round-trip.md, section 4: master/detail, APIs on the left, the selected API's General/Environments/
 * Authentication/Variables &amp; Headers/Endpoints tabs on the right, New API/Import/Export at the bottom
 * of the list, Apply/Close for the window as a whole.
 *
 * <p>A deliberate sibling top-level frame, not a fourth tab on {@code JSettingsFrame} - see the sub-sprint
 * plan's own "GUI architecture" reasoning (this domain is at least as large as Connections+Groups+
 * Environments combined, and API entities have no Deactivate/Reactivate/Delete-permanently menu model to
 * fit into). Installs the same {@link BroadSqlLookAndFeel} FlatLaf theme independently, since
 * {@code CONFIG API} can be the very first GUI command a session opens.
 *
 * <p><b>Save model, revised in the API Quality and UX Consolidation sprint (Phase 2)</b>: Environments
 * and Endpoints each persist immediately through their own tab's Save/Duplicate/Delete actions (the
 * same per-record-save convention {@code JEnvironmentsPanel}/{@code JDatabaseGroupsPanel} already use
 * elsewhere in this codebase) - a folder/endpoint tree can be arbitrarily large, and saving each node as
 * it is edited keeps this consistent with one row at a time, exactly how the persistence layer itself
 * works. This window's own "Save API" button (formerly a screen-wide "Apply" - removed, per the
 * sprint's explicit requirement) governs the lighter General/Authentication/Variables &amp; Headers
 * tabs, which have no per-row save action of their own since they are all {@code ApiOwnerType.API}-
 * scoped facets of the one API entity, not separate persisted objects. Real dirty tracking
 * ({@link #apiFormIsDirty()}, a value-snapshot comparison captured right after {@link #loadSelectedApi()}
 * populates those three tabs - never a listener-driven flag) replaces the previous {@link #attemptClose()},
 * which showed its "Apply pending changes?" prompt <i>unconditionally</i>, on every close, dirty or not -
 * see {@code docs/TECHNICAL_CHANGE.md}, 2026-09-14, "the false-dirty close bug". {@link #attemptClose()}
 * and the API-list selection-change guard now both go through {@link #confirmDiscardCurrentIfDirty()},
 * offering the same {@link UnsavedChangesDialog} Save/Discard/Cancel prompt {@link JApiEndpointTreePanel}
 * uses for its own tab, and considering that tab's currently-shown editor too
 * ({@link JApiEndpointTreePanel#isCurrentEditorDirty()}).
 */
public class JApiSettingsFrame extends JFrame {

	private static final Logger log = LoggerFactory.getLogger(JApiSettingsFrame.class);

	private ApiDefinitionsVault vault;

	private final DefaultListModel<String> apiListModel = new DefaultListModel<>();
	private final Map<String, ApiDefinition> apisById = new LinkedHashMap<>();
	private JList<String> apiList;

	private JApiGeneralPanel generalPanel;
	private JApiEnvironmentsPanel environmentsPanel;
	private JApiAuthenticationPanel apiAuthPanel;
	private JApiVariablesHeadersPanel variablesHeadersPanel;
	private JApiEndpointTreePanel endpointsPanel;

	private String loadedApiId;
	private String[] apiFormSnapshot;
	private boolean loadingApiSelection;
	private String lastConfirmedApiId;

	// SPRINT XT02B, section 2/5: the single global Save (API > Save / Ctrl+S), replacing the four
	// scattered per-object Save buttons/dialogs this window used to have.
	private static final String FRAME_TITLE = "API Configuration";
	private ApiConfigSaveCoordinator saveCoordinator;
	private JTabbedPane tabs;
	private int lastConfirmedTabIndex;
	private boolean revertingTabSelection;
	private JMenuItem apiSaveMenuItem;
	private DirtyIndicatorPoller dirtyIndicatorPoller;

	public void setApiDefinitionsVault(ApiDefinitionsVault vault) {
		this.vault = vault;
	}

	public void initApp() throws BroadSQLException {
		try {
			BroadSqlLookAndFeel.installOnce();
			initComponents();
			reloadApiList();
		} catch (Exception ex) {
			throw new BroadSQLException(ex);
		}
	}

	private void initComponents() {
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		setTitle(FRAME_TITLE);
		Image icon = AppIcon.load();
		if (icon != null) {
			setIconImage(icon);
		}
		buildMenuBar();
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				attemptClose();
			}
		});

		apiList = new JList<>(apiListModel);
		apiList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		apiList.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && !loadingApiSelection) {
				handleApiSelectionChanged();
			}
		});
		JScrollPane listScroll = new JScrollPane(apiList);
		listScroll.setMinimumSize(new Dimension(160, 100));

		// SPRINT XT02B, section 2: no per-object "Save API" button any more - field edits on the
		// General/Authentication/Variables & Headers tabs are persisted only through the single global
		// API > Save / Ctrl+S (see ApiConfigSaveCoordinator), or via the Save/Discard/Cancel navigation
		// guard when leaving a dirty API. Structural actions (New/Rename/Delete/Import/Export) are
		// unchanged - they keep persisting immediately, exactly as they do today.
		JPanel listButtons = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
		JButton newApiButton = new JButton("+ New API");
		newApiButton.addActionListener(e -> newApi());
		JButton renameApiButton = new JButton("Rename API");
		renameApiButton.addActionListener(e -> renameApi());
		JButton deleteApiButton = new JButton("Delete API");
		deleteApiButton.addActionListener(e -> deleteApi());
		JButton importButton = new JButton("Import Bruno YAML");
		importButton.addActionListener(e -> importBruno());
		JButton exportButton = new JButton("Export Bruno YAML");
		exportButton.addActionListener(e -> exportBruno());
		listButtons.add(newApiButton);
		listButtons.add(renameApiButton);
		listButtons.add(deleteApiButton);
		listButtons.add(importButton);
		listButtons.add(exportButton);

		JPanel leftPanel = new JPanel(new BorderLayout());
		leftPanel.add(listScroll, BorderLayout.CENTER);
		leftPanel.add(listButtons, BorderLayout.SOUTH);

		tabs = new JTabbedPane();
		generalPanel = new JApiGeneralPanel();
		environmentsPanel = new JApiEnvironmentsPanel();
		apiAuthPanel = new JApiAuthenticationPanel(false); // API level has no parent to inherit from
		variablesHeadersPanel = new JApiVariablesHeadersPanel();
		endpointsPanel = new JApiEndpointTreePanel();
		tabs.addTab("General", generalPanel);
		tabs.addTab("Environments", environmentsPanel);
		tabs.addTab("Authentication", apiAuthPanel);
		tabs.addTab("Variables & Headers", variablesHeadersPanel);
		tabs.addTab("Endpoints", endpointsPanel);

		saveCoordinator = new ApiConfigSaveCoordinator(this::apiFormIsDirty, this::trySaveApi, environmentsPanel, endpointsPanel);

		// SPRINT XT02B, section 2/5: moving between the three staged-edit domains (API form:
		// General/Authentication/Variables & Headers as one unit; Environments; Endpoints) is itself a
		// guarded transition, not just moving within one of them - at most one of the three can be dirty
		// at any given moment, by construction of this guard.
		lastConfirmedTabIndex = tabs.getSelectedIndex();
		tabs.addChangeListener(e -> {
			if (revertingTabSelection) {
				return;
			}
			int newIndex = tabs.getSelectedIndex();
			if (tabDomain(newIndex) == tabDomain(lastConfirmedTabIndex)) {
				lastConfirmedTabIndex = newIndex;
				return;
			}
			if (!confirmDiscardCurrentIfDirty()) {
				revertingTabSelection = true;
				tabs.setSelectedIndex(lastConfirmedTabIndex);
				revertingTabSelection = false;
				return;
			}
			lastConfirmedTabIndex = newIndex;
		});

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, tabs);
		split.setDividerLocation(200);

		JPanel bottomButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton closeButton = new JButton("Close");
		closeButton.addActionListener(e -> attemptClose());
		bottomButtons.add(closeButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(split, BorderLayout.CENTER);
		getContentPane().add(bottomButtons, BorderLayout.SOUTH);
		setSize(980, 640);
		setLocationRelativeTo(null);

		// SPRINT XT02B, section 2.6: dirty-checking is snapshot-diff based (no change-event to hook), so
		// a lightweight poll drives both the title's "*" indicator and the Save menu item's enabled state.
		// Kept in a field and stopped in dispose(): left running, it keeps AWT (and so the JVM) alive after
		// EXIT even though this window is gone - see DirtyIndicatorPoller.
		dirtyIndicatorPoller = new DirtyIndicatorPoller(500, () -> {
			boolean dirty = saveCoordinator.isAnyDirty();
			setTitle(FRAME_TITLE + (dirty ? " *" : ""));
			apiSaveMenuItem.setEnabled(dirty);
		});
		dirtyIndicatorPoller.start();
	}

	/**
	 * Every way this window closes (the X button, API > Close, a programmatic call) ends in
	 * {@code dispose()}, so this is the one place the dirty-indicator poll is stopped. Without it the
	 * repeating timer outlives the window and prevents the JVM from exiting (SPRINT 0917-01 follow-up,
	 * EXIT hang after CONFIG API).
	 */
	@Override
	public void dispose() {
		if (dirtyIndicatorPoller != null) {
			dirtyIndicatorPoller.stop();
		}
		super.dispose();
	}

	/**
	 * SPRINT XT02B, section 2/5: three menus replacing the old single {@code File} menu, per the locked
	 * final menu contents (see the sprint plan's Context section) - API (New/Import/Delete/Save[Ctrl+S]/
	 * Close), Environment (New/Duplicate/Delete), Endpoint (New Folder/New Endpoint/Delete). The
	 * Environment/Endpoint items switch to the relevant tab first ({@link #selectTab(int)}, which runs the
	 * domain-switch guard exactly as a manual tab click would) before delegating to the now-public
	 * {@link JApiEnvironmentsPanel}/{@link JApiEndpointTreePanel} action methods - if the guard is
	 * cancelled, the action is not invoked.
	 */
	private void buildMenuBar() {
		JMenuBar menuBar = new JMenuBar();

		JMenu apiMenu = new JMenu("API");
		JMenuItem newApiItem = new JMenuItem("New");
		newApiItem.addActionListener(e -> newApi());
		JMenuItem importApiItem = new JMenuItem("Import...");
		importApiItem.addActionListener(e -> importBruno());
		JMenuItem deleteApiItem = new JMenuItem("Delete");
		deleteApiItem.addActionListener(e -> deleteApi());
		apiSaveMenuItem = new JMenuItem("Save");
		apiSaveMenuItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK));
		apiSaveMenuItem.addActionListener(e -> saveCoordinator.saveCurrentEdits());
		JMenuItem closeItem = new JMenuItem("Close");
		closeItem.addActionListener(e -> attemptClose());
		apiMenu.add(newApiItem);
		apiMenu.add(importApiItem);
		apiMenu.add(deleteApiItem);
		apiMenu.addSeparator();
		apiMenu.add(apiSaveMenuItem);
		apiMenu.add(closeItem);

		JMenu environmentMenu = new JMenu("Environment");
		JMenuItem newEnvironmentItem = new JMenuItem("New");
		newEnvironmentItem.addActionListener(e -> {
			if (selectTab(1)) {
				environmentsPanel.newEnvironmentButtonClicked();
			}
		});
		JMenuItem duplicateEnvironmentItem = new JMenuItem("Duplicate");
		duplicateEnvironmentItem.addActionListener(e -> {
			if (selectTab(1)) {
				environmentsPanel.duplicateSelected();
			}
		});
		JMenuItem deleteEnvironmentItem = new JMenuItem("Delete");
		deleteEnvironmentItem.addActionListener(e -> {
			if (selectTab(1)) {
				environmentsPanel.deleteSelected();
			}
		});
		environmentMenu.add(newEnvironmentItem);
		environmentMenu.add(duplicateEnvironmentItem);
		environmentMenu.add(deleteEnvironmentItem);

		JMenu endpointMenu = new JMenu("Endpoint");
		JMenuItem newFolderItem = new JMenuItem("New Folder");
		newFolderItem.addActionListener(e -> {
			if (selectTab(4)) {
				endpointsPanel.newFolder();
			}
		});
		JMenuItem newEndpointItem = new JMenuItem("New Endpoint");
		newEndpointItem.addActionListener(e -> {
			if (selectTab(4)) {
				endpointsPanel.newEndpoint();
			}
		});
		JMenuItem deleteEndpointItem = new JMenuItem("Delete");
		deleteEndpointItem.addActionListener(e -> {
			if (selectTab(4)) {
				endpointsPanel.delete();
			}
		});
		endpointMenu.add(newFolderItem);
		endpointMenu.add(newEndpointItem);
		endpointMenu.add(deleteEndpointItem);

		menuBar.add(apiMenu);
		menuBar.add(environmentMenu);
		menuBar.add(endpointMenu);
		setJMenuBar(menuBar);
	}

	/**
	 * Switches the visible tab to {@code index}, running the domain-switch guard exactly as a manual tab
	 * click would (via the {@code ChangeListener} installed in {@link #initComponents()}).
	 *
	 * @return {@code true} if the tab is now showing {@code index} (already there, or the switch was
	 *         confirmed); {@code false} if a dirty domain was being left and the user cancelled, in which
	 *         case the selection has been reverted and the caller must not proceed with its action.
	 */
	private boolean selectTab(int index) {
		if (tabs.getSelectedIndex() == index) {
			return true;
		}
		tabs.setSelectedIndex(index);
		return tabs.getSelectedIndex() == index;
	}

	/** Groups tab indices into the three staged-edit domains guarded by {@link #confirmDiscardCurrentIfDirty()}: the API form (General/Authentication/Variables &amp; Headers), Environments, and Endpoints. */
	private static int tabDomain(int tabIndex) {
		return switch (tabIndex) {
			case 1 -> 1; // Environments
			case 4 -> 2; // Endpoints
			default -> 0; // General / Authentication / Variables & Headers
		};
	}

	private void reloadApiList() {
		if (vault == null) {
			return;
		}
		String previouslySelected = loadedApiId;
		apiListModel.clear();
		apisById.clear();
		for (ApiDefinition api : vault.getApis()) {
			apisById.put(api.getId(), api);
			apiListModel.addElement(api.getId() + (api.getName() != null ? " (" + api.getName() + ")" : ""));
		}
		// Guarded (API Quality and UX Consolidation sprint, Phase 2): a full reload always follows a
		// save/new/import/delete that has already resolved any dirty state, so the programmatic
		// selection here must not re-trigger handleApiSelectionChanged()'s own guard.
		loadingApiSelection = true;
		try {
			if (previouslySelected != null && apisById.containsKey(previouslySelected)) {
				selectApiInList(previouslySelected);
			} else if (!apiListModel.isEmpty()) {
				apiList.setSelectedIndex(0);
			}
		} finally {
			loadingApiSelection = false;
		}
		loadSelectedApi();
		lastConfirmedApiId = loadedApiId;
	}

	private void selectApiInList(String apiId) {
		for (int i = 0; i < apiListModel.size(); i++) {
			if (apiListModel.get(i).startsWith(apiId)) {
				apiList.setSelectedIndex(i);
				return;
			}
		}
	}

	private void handleApiSelectionChanged() {
		if (!confirmDiscardCurrentIfDirty()) {
			loadingApiSelection = true;
			try {
				if (lastConfirmedApiId != null) {
					selectApiInList(lastConfirmedApiId);
				} else {
					apiList.clearSelection();
				}
			} finally {
				loadingApiSelection = false;
			}
			return;
		}
		loadSelectedApi();
		lastConfirmedApiId = loadedApiId;
	}

	/**
	 * The shared unsaved-changes guard for every action listed in the sprint plan's guarded-transition
	 * table - window close/{@code API > Close}, the API-list selection change, and every tab-domain switch
	 * ({@link #initComponents()}'s {@code ChangeListener} via {@link #selectTab(int)}). Delegates to
	 * {@link #saveCoordinator}, which is the single place that knows about all three staged objects (the
	 * API form, the currently-selected Environment, the currently-selected Endpoint/Folder) - this method
	 * no longer inspects any of them directly, closing the gap where the Environments tab's dirty state
	 * was previously never checked here at all. {@code SAVE} persists whichever one is actually dirty;
	 * {@code DISCARD} proceeds without persisting; {@code CANCEL} means the caller must abort/revert.
	 */
	private boolean confirmDiscardCurrentIfDirty() {
		if (!saveCoordinator.isAnyDirty()) {
			return true;
		}
		UnsavedChangesDialog.Decision decision = UnsavedChangesDialog.ask(this, currentlyDirtyDomainLabel());
		return switch (decision) {
			case SAVE -> saveCoordinator.saveCurrentEdits();
			case DISCARD -> true;
			case CANCEL -> false;
		};
	}

	/** At most one of the three staged objects is ever dirty at a time, by construction of this guard - see {@link #tabDomain(int)}. */
	private String currentlyDirtyDomainLabel() {
		if (apiFormIsDirty()) {
			return "API";
		}
		if (environmentsPanel.isCurrentEditorDirty()) {
			return "environment";
		}
		return "endpoint";
	}

	private boolean apiFormIsDirty() {
		return apiFormSnapshot != null && !Arrays.equals(apiFormSnapshot, currentApiFormSnapshotValues());
	}

	private void captureApiFormSnapshot() {
		apiFormSnapshot = currentApiFormSnapshotValues();
	}

	private String[] currentApiFormSnapshotValues() {
		return new String[] { generalPanel.snapshotKey(), apiAuthPanel.getModel().snapshotKey(), variablesHeadersPanel.snapshotKey() };
	}

	private void loadSelectedApi() {
		int index = apiList.getSelectedIndex();
		if (index < 0) {
			loadedApiId = null;
			apiFormSnapshot = null;
			return;
		}
		String apiId = apisById.keySet().stream().skip(index).findFirst().orElse(null);
		if (apiId == null) {
			return;
		}
		loadedApiId = apiId;
		try {
			ApiDefinition api = vault.getApi(apiId);
			generalPanel.setModel(ApiGeneralFormModel.fromApi(api), false);
			ApiImportSource importSource = vault.getImportSource(apiId);
			generalPanel.setImportSource(importSource);
			ApiVersion version = vault.getDefaultVersion(apiId);
			int endpointCount = version == null ? 0 : vault.getEndpointsForVersion(version.getId()).size();
			int folderCount = version == null ? 0 : vault.getGroupsForVersion(version.getId()).size();
			int environmentCount = vault.getEnvironmentsForApi(apiId).size();
			generalPanel.setSummary(endpointCount, folderCount, environmentCount);

			ApiAuthConfig auth = vault.getAuth(ApiOwnerType.API, apiId);
			apiAuthPanel.setModel(ApiAuthFormModel.fromAuth(auth, auth == null ? List.of() : vault.getAttributes(ApiOwnerType.AUTH, String.valueOf(auth.getId()), ApiAttributeKind.PROPERTY)));

			variablesHeadersPanel.setContext(vault, apiId);
			environmentsPanel.setContext(vault, apiId);
			if (version != null) {
				endpointsPanel.setContext(vault, apiId, version.getId());
			}
			captureApiFormSnapshot();
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void newApi() {
		if (!confirmDiscardCurrentIfDirty()) {
			return;
		}
		JTextField idField = new JTextField(15);
		JTextField nameField = new JTextField(25);
		JPanel form = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.gridx = 0;
		c.gridy = 0;
		form.add(new JLabel("API ID"), c);
		c.gridx = 1;
		form.add(idField, c);
		c.gridx = 0;
		c.gridy = 1;
		form.add(new JLabel("Name"), c);
		c.gridx = 1;
		form.add(nameField, c);

		int result = JOptionPane.showConfirmDialog(this, form, "New API", JOptionPane.OK_CANCEL_OPTION);
		if (result != JOptionPane.OK_OPTION) {
			return;
		}
		ApiGeneralFormModel model = ApiGeneralFormModel.newApi();
		model.setId(idField.getText());
		model.setName(nameField.getText());
		List<String> errors = model.validate(true);
		if (!errors.isEmpty()) {
			JOptionPane.showMessageDialog(this, String.join("\n", errors));
			return;
		}
		try {
			ApiDefinition api = new ApiDefinition();
			model.applyTo(api);
			vault.createApiWithDefaultVersion(api);
			loadedApiId = api.getId();
			reloadApiList();
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void renameApi() {
		if (loadedApiId == null) {
			return;
		}
		ApiDefinition api = apisById.get(loadedApiId);
		String newName = JOptionPane.showInputDialog(this, "New API name:", api.getName());
		if (newName == null || newName.isBlank()) {
			return;
		}
		try {
			api.setName(newName.trim());
			vault.saveApi(api);
			reloadApiList();
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void deleteApi() {
		if (loadedApiId == null) {
			return;
		}
		if (!confirmDiscardCurrentIfDirty()) {
			return;
		}
		try {
			ApiVersion version = vault.getDefaultVersion(loadedApiId);
			int endpointCount = version == null ? 0 : vault.getEndpointsForVersion(version.getId()).size();
			int folderCount = version == null ? 0 : vault.getGroupsForVersion(version.getId()).size();
			int environmentCount = vault.getEnvironmentsForApi(loadedApiId).size();
			String message = "Delete API \"" + apisById.get(loadedApiId).getName() + "\"?\n\nThis will remove:\n  "
					+ environmentCount + " environment(s)\n  " + folderCount + " folder(s)\n  " + endpointCount + " endpoint(s)\n\n"
					+ "This operation cannot be undone.";
			int confirm = JOptionPane.showConfirmDialog(this, message, "Delete API", JOptionPane.YES_NO_OPTION);
			if (confirm != JOptionPane.YES_OPTION) {
				return;
			}
			vault.deactivateApi(loadedApiId);
			vault.hardDeleteApi(loadedApiId);
			loadedApiId = null;
			reloadApiList();
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void importBruno() {
		if (!confirmDiscardCurrentIfDirty()) {
			return;
		}
		JBrunoImportDialog dialog = new JBrunoImportDialog((JFrame) null, vault);
		dialog.setModalityType(JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
		if (dialog.getImportedApiId() != null) {
			loadedApiId = dialog.getImportedApiId();
			reloadApiList();
		}
	}

	private void exportBruno() {
		if (loadedApiId == null) {
			JOptionPane.showMessageDialog(this, "Select an API to export first.");
			return;
		}
		JBrunoExportDialog dialog = new JBrunoExportDialog(null, vault, loadedApiId);
		dialog.setModalityType(JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	/**
	 * Validates and persists the tabs with no per-row save action of their own - General,
	 * Authentication, Variables &amp; Headers (Environments/Endpoints already persist immediately
	 * through their own actions) - without refreshing the API list. The API-form arm of
	 * {@link #saveCoordinator}'s {@code saveCurrentEdits()}, and also used directly by
	 * {@link #confirmDiscardCurrentIfDirty()}'s {@code SAVE} branch (via the coordinator), which
	 * deliberately must not trigger a full {@link #reloadApiList()}: that would reselect the just-saved
	 * API and re-fire the selection listener re-entrantly, fighting the navigation the user actually
	 * asked for - the same reasoning {@link JApiEnvironmentsPanel} documents for its own {@code trySave()}.
	 *
	 * @return {@code true} if persisted (validation passed); {@code false} otherwise (an error dialog
	 *         has already been shown).
	 */
	private boolean trySaveApi() {
		if (loadedApiId == null) {
			return false;
		}
		try {
			ApiDefinition api = vault.getApi(loadedApiId);
			ApiGeneralFormModel model = generalPanel.getModel(ApiGeneralFormModel.fromApi(api));
			List<String> errors = model.validate(false);
			ApiAuthFormModel authModel = apiAuthPanel.getModel();
			errors.addAll(authModel.validate());
			if (!errors.isEmpty()) {
				JOptionPane.showMessageDialog(this, String.join("\n", errors));
				return false;
			}
			model.applyTo(api);
			vault.saveApi(api);

			if (authModel.getChoice() == ApiAuthChoice.UNSUPPORTED) {
				// Read-only imported data - never re-saved as-is.
			} else {
				vault.saveAuth(new ApiAuthConfig(ApiOwnerType.API, loadedApiId, authModel.toAuthType()));
				ApiAuthConfig saved = vault.getAuth(ApiOwnerType.API, loadedApiId);
				vault.replaceAttributes(ApiOwnerType.AUTH, String.valueOf(saved.getId()), ApiAttributeKind.PROPERTY, authModel.toProperties(saved.getId()));
			}
			variablesHeadersPanel.apply();

			// SPRINT XT02B, section 2.3/2.4: no success popup on an ordinary successful save - dirty
			// state simply clears (via captureApiFormSnapshot() below) and the frame title's/Save menu
			// item's indicator reflect it. Errors above still show a dialog, unchanged.
			captureApiFormSnapshot();
			return true;
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
			return false;
		}
	}

	/**
	 * Shared by the window-close button/listener and {@code File > Exit} - see the class javadoc's
	 * "Save model" section for the false-dirty-prompt bug this replaced and how
	 * {@link #confirmDiscardCurrentIfDirty()} now gates it correctly. Opening and closing this window
	 * untouched produces zero prompts.
	 */
	private void attemptClose() {
		if (!confirmDiscardCurrentIfDirty()) {
			return;
		}
		dispose();
	}
}
