package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.model.ApiEndpoint;
import com.upandcoding.broadsql.dao.api.model.ApiEndpointGroup;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * {@code CONFIG API}'s "Endpoints" tab - docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno
 * YAML Round-trip.md, sections 20/21/47: a hierarchical {@link JTree} on the left (folders and endpoints,
 * every endpoint labeled with its HTTP method per section 21/47 - "Do not hide write endpoints merely
 * because execution is read-only"), an editor on the right switching between
 * {@link JApiFolderEditorPanel} and {@link JApiEndpointEditorPanel} depending on the selection.
 *
 * <p>"Move" (section 21) is deliberately not a separate tree action - both editor panels' own "Parent
 * folder"/"Folder" combo already lets the user reparent an item and Save, which section 21 explicitly
 * accepts as sufficient for this first implementation ("simple parent-folder selection is sufficient...
 * Correctness is more important than drag-and-drop polish").
 *
 * <p><b>API Quality and UX Consolidation sprint, Phase 2 additions</b>:
 * <ul>
 * <li>A live search field above the tree ({@link #searchField}) filters by id (exact), name, alias,
 * folder path, verb, or URL (case-insensitive substring) - matching endpoints plus their ancestor
 * folders are shown, everything else is hidden. Purely a display rebuild: never persists, never marks
 * anything dirty, never touches the editor unless the user actually clicks a (still-visible) node -
 * see {@link #rebuildingTree}.</li>
 * <li>"Save Endpoint" is grouped here, next to "New Endpoint"/"Delete", rather than buried inside
 * {@link JApiEndpointEditorPanel}'s own form (section 13) - it simply forwards to whichever
 * {@link ApiObjectEditor} is currently shown.</li>
 * <li>Tree selection changes, "New Folder", and "New Endpoint" all now go through
 * {@link #confirmDiscardCurrentIfDirty}, so a real unsaved edit is protected (Save/Discard/Cancel)
 * before the editor is replaced - see {@link UnsavedChangesDialog}.</li>
 * </ul>
 */
public class JApiEndpointTreePanel extends JPanel {

	private ApiDefinitionsVault vault;
	private String apiId;
	private Integer apiVersionId;

	private JTextField searchField;
	private JTree tree;
	private DefaultTreeModel treeModel;
	private CardLayout editorCardLayout;
	private JPanel editorCardsPanel;
	private JApiFolderEditorPanel folderEditor;
	private JApiEndpointEditorPanel endpointEditor;

	private static final String CARD_BLANK = "blank";
	private static final String CARD_FOLDER = "folder";
	private static final String CARD_ENDPOINT = "endpoint";
	private String currentCard = CARD_BLANK;

	// Search/filter and unsaved-changes-guard state - see class javadoc.
	private boolean rebuildingTree;
	private boolean revertingSelection;
	private TreePath lastConfirmedSelection;

	public JApiEndpointTreePanel() {
		buildUi();
	}

	private void buildUi() {
		setLayout(new BorderLayout());

		searchField = new JTextField();
		searchField.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				rebuildTreeForFilter();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				rebuildTreeForFilter();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				rebuildTreeForFilter();
			}
		});
		JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
		searchPanel.add(new javax.swing.JLabel("Search:"), BorderLayout.WEST);
		searchPanel.add(searchField, BorderLayout.CENTER);
		searchPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 4, 4, 4));

		DefaultMutableTreeNode root = new DefaultMutableTreeNode("Endpoints");
		treeModel = new DefaultTreeModel(root);
		tree = new JTree(treeModel);
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.addTreeSelectionListener(e -> handleTreeSelectionChanged());
		JPanel treeSide = new JPanel(new BorderLayout());
		treeSide.add(searchPanel, BorderLayout.NORTH);
		JScrollPane treeScroll = new JScrollPane(tree);
		treeScroll.setMinimumSize(new Dimension(220, 100));
		treeSide.add(treeScroll, BorderLayout.CENTER);

		editorCardLayout = new CardLayout();
		editorCardsPanel = new JPanel(editorCardLayout);
		editorCardsPanel.add(new JPanel(), CARD_BLANK);
		folderEditor = new JApiFolderEditorPanel();
		folderEditor.setOnSaved(this::reload);
		editorCardsPanel.add(folderEditor, CARD_FOLDER);
		endpointEditor = new JApiEndpointEditorPanel();
		endpointEditor.setOnSaved(this::reload);
		editorCardsPanel.add(endpointEditor, CARD_ENDPOINT);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeSide, editorCardsPanel);
		split.setDividerLocation(260);
		add(split, BorderLayout.CENTER);

		// SPRINT XT02B, section 2: no per-object "Save Endpoint" button any more - field edits in
		// either editor are persisted only through the single global API > Save / Ctrl+S (see
		// JApiSettingsFrame's ApiConfigSaveCoordinator), or via the Save/Discard/Cancel navigation
		// guard when leaving a dirty folder/endpoint.
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JButton newFolderButton = new JButton("New Folder");
		newFolderButton.addActionListener(e -> newFolder());
		JButton newEndpointButton = new JButton("New Endpoint");
		newEndpointButton.addActionListener(e -> newEndpoint());
		JButton renameButton = new JButton("Rename");
		renameButton.addActionListener(e -> rename());
		JButton deleteButton = new JButton("Delete");
		deleteButton.addActionListener(e -> delete());
		buttons.add(newFolderButton);
		buttons.add(newEndpointButton);
		buttons.add(renameButton);
		buttons.add(deleteButton);
		add(buttons, BorderLayout.SOUTH);
	}

	public void setContext(ApiDefinitionsVault vault, String apiId, int apiVersionId) {
		this.vault = vault;
		this.apiId = apiId;
		this.apiVersionId = apiVersionId;
		searchField.setText("");
		reload();
	}

	/** Full reload after a save or a context switch (a different API/environment) - always resets to no selection, exactly as before this phase. */
	public void reload() {
		rebuildingTree = true;
		try {
			rebuildTreeNodes();
		} finally {
			rebuildingTree = false;
		}
		lastConfirmedSelection = null;
		showCard(CARD_BLANK);
	}

	/**
	 * A search-field keystroke rebuilt the tree - purely a display change (section 30/the spec's
	 * navigation-safety requirement): never goes through {@link #confirmDiscardCurrentIfDirty}, never
	 * touches the currently shown editor. If the previously selected item is still present after
	 * filtering, it is silently reselected (guarded - the editor already shows its data, nothing to
	 * reload); otherwise the tree simply shows no selection while the editor is left exactly as it was.
	 */
	private void rebuildTreeForFilter() {
		rebuildingTree = true;
		try {
			TreeItem previouslySelected = selectedItem();
			rebuildTreeNodes();
			if (previouslySelected != null) {
				TreePath restored = findPath(previouslySelected);
				if (restored != null) {
					tree.setSelectionPath(restored);
					lastConfirmedSelection = restored;
					return;
				}
			}
			// Not found (filtered out, or nothing was selected) - leave the tree unselected and the
			// editor untouched, per the search's navigation-safety requirement.
			lastConfirmedSelection = null;
		} finally {
			rebuildingTree = false;
		}
	}

	private void rebuildTreeNodes() {
		if (vault == null) {
			return;
		}
		try {
			String query = searchField.getText() == null ? "" : searchField.getText().trim();
			DefaultMutableTreeNode root = new DefaultMutableTreeNode("Endpoints");
			if (query.isEmpty()) {
				buildChildren(root, null, null, "");
			} else {
				Set<Integer> matchingGroupIds = matchingAncestorGroupIds(query);
				buildChildren(root, null, new Filter(query.toLowerCase(), matchingGroupIds), "");
			}
			treeModel.setRoot(root);
			for (int i = 0; i < tree.getRowCount(); i++) {
				tree.expandRow(i);
			}
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "Could not load endpoints: " + ex.getLocalizedMessage());
		}
	}

	/** Every folder id that either directly matches {@code query} by name, or is an ancestor of a folder/endpoint that will be shown - computed once per rebuild so {@link #buildChildren} can decide folder visibility in a single pass. */
	private Set<Integer> matchingAncestorGroupIds(String query) throws BroadSQLException {
		String needle = query.toLowerCase();
		List<ApiEndpointGroup> groups = vault.getGroupsForVersion(apiVersionId);
		Map<Integer, ApiEndpointGroup> byId = new HashMap<>();
		for (ApiEndpointGroup group : groups) {
			byId.put(group.getId(), group);
		}
		Map<Integer, String> folderPathCache = new HashMap<>();
		Set<Integer> keep = new HashSet<>();

		for (ApiEndpointGroup group : groups) {
			String path = folderPath(group.getId(), byId, folderPathCache);
			if (path.toLowerCase().contains(needle)) {
				markAncestorsKept(group.getId(), byId, keep);
			}
		}
		for (ApiEndpoint endpoint : vault.getEndpointsForVersion(apiVersionId)) {
			if (!isActive(endpoint.getStatusId())) {
				continue;
			}
			String folder = endpoint.getGroupId() == null ? "" : folderPath(endpoint.getGroupId(), byId, folderPathCache);
			if (endpointMatches(endpoint, folder, needle)) {
				if (endpoint.getGroupId() != null) {
					markAncestorsKept(endpoint.getGroupId(), byId, keep);
				}
			}
		}
		return keep;
	}

	private void markAncestorsKept(Integer groupId, Map<Integer, ApiEndpointGroup> byId, Set<Integer> keep) {
		Integer current = groupId;
		while (current != null && keep.add(current)) {
			ApiEndpointGroup group = byId.get(current);
			current = group == null ? null : group.getParentGroupId();
		}
	}

	private boolean endpointMatches(ApiEndpoint endpoint, String folderPath, String needleLower) {
		if (String.valueOf(endpoint.getId()).equals(needleLower)) {
			return true;
		}
		return containsIgnoreCase(endpoint.getName(), needleLower)
				|| containsIgnoreCase(endpoint.getAlias(), needleLower)
				|| containsIgnoreCase(endpoint.getMethod(), needleLower)
				|| containsIgnoreCase(endpoint.getEndpointPath(), needleLower)
				|| containsIgnoreCase(folderPath, needleLower);
	}

	private boolean containsIgnoreCase(String haystack, String needleLower) {
		return haystack != null && haystack.toLowerCase().contains(needleLower);
	}

	private String folderPath(Integer groupId, Map<Integer, ApiEndpointGroup> byId, Map<Integer, String> cache) {
		if (groupId == null) {
			return "";
		}
		String cached = cache.get(groupId);
		if (cached != null) {
			return cached;
		}
		ApiEndpointGroup group = byId.get(groupId);
		if (group == null) {
			return "";
		}
		String parentPath = group.getParentGroupId() == null ? "" : folderPath(group.getParentGroupId(), byId, cache);
		String path = parentPath.isEmpty() ? group.getName() : parentPath + " / " + group.getName();
		cache.put(groupId, path);
		return path;
	}

	private void buildChildren(DefaultMutableTreeNode parentNode, Integer parentGroupId, Filter filter, String parentPath) throws BroadSQLException {
		for (ApiEndpointGroup group : vault.getGroupsForVersion(apiVersionId)) {
			if (!isActive(group.getStatusId()) || !Objects.equals(group.getParentGroupId(), parentGroupId)) {
				continue;
			}
			if (filter != null && !filter.matchingGroupIds.contains(group.getId())) {
				continue;
			}
			DefaultMutableTreeNode node = new DefaultMutableTreeNode(TreeItem.folder(group.getId(), group.getName()));
			parentNode.add(node);
			String childPath = parentPath.isEmpty() ? group.getName() : parentPath + " / " + group.getName();
			buildChildren(node, group.getId(), filter, childPath);
		}
		List<ApiEndpoint> endpoints = parentGroupId == null
				? vault.getEndpointsForVersion(apiVersionId).stream().filter(e -> e.getGroupId() == null).toList()
				: vault.getEndpointsForGroup(parentGroupId);
		for (ApiEndpoint endpoint : endpoints) {
			if (!isActive(endpoint.getStatusId())) {
				continue;
			}
			if (filter != null && !endpointMatches(endpoint, parentPath, filter.needleLower)) {
				continue;
			}
			String label = endpoint.getMethod() + "  " + endpoint.getName() + (endpoint.getAlias() != null ? "  [" + endpoint.getAlias() + "]" : "");
			parentNode.add(new DefaultMutableTreeNode(TreeItem.endpoint(endpoint.getId(), label)));
		}
	}

	private void handleTreeSelectionChanged() {
		if (rebuildingTree || revertingSelection) {
			return;
		}
		if (!confirmDiscardCurrentIfDirty(currentEditorLabel())) {
			revertingSelection = true;
			try {
				tree.setSelectionPath(lastConfirmedSelection);
			} finally {
				revertingSelection = false;
			}
			return;
		}
		lastConfirmedSelection = tree.getSelectionPath();
		loadSelection();
	}

	private void loadSelection() {
		TreeItem item = selectedItem();
		try {
			if (item == null) {
				showCard(CARD_BLANK);
			} else if (item.isFolder()) {
				folderEditor.load(vault, apiVersionId, item.id, null);
				showCard(CARD_FOLDER);
			} else {
				endpointEditor.load(vault, apiId, apiVersionId, item.id, null);
				showCard(CARD_ENDPOINT);
			}
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void showCard(String card) {
		currentCard = card;
		editorCardLayout.show(editorCardsPanel, card);
	}

	/** {@code null} when nothing is dirty (or nothing is shown) - proceed. {@code SAVE}/{@code DISCARD} also proceed (a failed Save reports its own error and blocks navigation); {@code CANCEL} means the caller must abort. */
	private boolean confirmDiscardCurrentIfDirty(String objectLabel) {
		ApiObjectEditor current = currentEditor();
		if (current == null || !current.isDirty()) {
			return true;
		}
		UnsavedChangesDialog.Decision decision = UnsavedChangesDialog.ask(this, objectLabel);
		return switch (decision) {
			case SAVE -> current.trySave();
			case DISCARD -> true;
			case CANCEL -> false;
		};
	}

	/** Whether the currently shown folder/endpoint editor (if any) has unsaved edits - for {@code JApiSettingsFrame}'s own close/API-switch guards, which must consider this tab too. */
	public boolean isCurrentEditorDirty() {
		ApiObjectEditor current = currentEditor();
		return current != null && current.isDirty();
	}

	/** Saves whichever folder/endpoint editor is currently shown, for {@code JApiSettingsFrame}'s own unsaved-changes guard - {@code true} if nothing is shown (nothing to save) or the save succeeded. */
	public boolean trySaveCurrentEditor() {
		ApiObjectEditor current = currentEditor();
		return current == null || current.trySave();
	}

	private ApiObjectEditor currentEditor() {
		if (CARD_FOLDER.equals(currentCard)) {
			return folderEditor;
		}
		if (CARD_ENDPOINT.equals(currentCard)) {
			return endpointEditor;
		}
		return null;
	}

	private String currentEditorLabel() {
		return CARD_FOLDER.equals(currentCard) ? "folder" : "endpoint";
	}

	private TreeItem selectedItem() {
		if (tree.getSelectionPath() == null) {
			return null;
		}
		Object node = tree.getSelectionPath().getLastPathComponent();
		if (node instanceof DefaultMutableTreeNode treeNode && treeNode.getUserObject() instanceof TreeItem item) {
			return item;
		}
		return null;
	}

	/** Finds the tree path of the node matching {@code item} (same type and id) in the current (possibly just-rebuilt) tree model - used to silently reselect a still-visible item after a filter rebuild. */
	private TreePath findPath(TreeItem item) {
		DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
		return findPath(root, item);
	}

	private TreePath findPath(DefaultMutableTreeNode node, TreeItem item) {
		if (node.getUserObject() instanceof TreeItem candidate && candidate.isFolder() == item.isFolder() && Objects.equals(candidate.id, item.id)) {
			return new TreePath(node.getPath());
		}
		for (int i = 0; i < node.getChildCount(); i++) {
			TreePath found = findPath((DefaultMutableTreeNode) node.getChildAt(i), item);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/** The folder ID a new child (folder or endpoint) should be created under, given the current selection - the selected folder itself, or an endpoint's own parent, or top-level (null) otherwise. */
	private Integer currentParentFolderId() throws BroadSQLException {
		TreeItem item = selectedItem();
		if (item == null) {
			return null;
		}
		if (item.isFolder()) {
			return item.id;
		}
		ApiEndpoint endpoint = vault.findEndpointById(item.id);
		return endpoint == null ? null : endpoint.getGroupId();
	}

	/** SPRINT XT02B, section 5: exposed for the Endpoint menu's "New" item (folder) - same action the button already triggered. */
	public void newFolder() {
		if (!confirmDiscardCurrentIfDirty(currentEditorLabel())) {
			return;
		}
		try {
			folderEditor.load(vault, apiVersionId, null, currentParentFolderId());
			showCard(CARD_FOLDER);
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	/** SPRINT XT02B, section 5: exposed for the Endpoint menu's "New" item (endpoint) - same action the button already triggered. */
	public void newEndpoint() {
		if (!confirmDiscardCurrentIfDirty(currentEditorLabel())) {
			return;
		}
		try {
			endpointEditor.load(vault, apiId, apiVersionId, null, currentParentFolderId());
			showCard(CARD_ENDPOINT);
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	/**
	 * SPRINT XT02B, section 4/5: Rename no longer persists immediately - it loads the target item into
	 * its editor (if it is not already the one shown) and stages the new name into the Name field
	 * ({@link JApiFolderEditorPanel#stageRenameTo}/{@link JApiEndpointEditorPanel#stageRenameTo}),
	 * marking it dirty exactly as if the user had opened the editor and typed a new name by hand.
	 * Persistence then happens through the normal Ctrl+S/Save path, or the Save/Discard/Cancel prompt
	 * if the user navigates away first - never immediately on this dialog's OK, preserving useful
	 * existing functionality (a fast rename) rather than removing it just because its old persistence
	 * timing predates the single-Save model.
	 */
	private void rename() {
		TreeItem item = selectedItem();
		if (item == null) {
			return;
		}
		try {
			if (item.isFolder()) {
				ApiEndpointGroup group = vault.findGroupById(item.id);
				String newName = JOptionPane.showInputDialog(this, "New folder name:", group.getName());
				if (newName == null || newName.isBlank()) {
					return;
				}
				// The tree selection already drove loadSelection() when this item was selected, so the
				// editor shown should already match it - reload defensively only if that is somehow not
				// the case (e.g. right after a reload() reset the view to CARD_BLANK).
				if (!CARD_FOLDER.equals(currentCard)) {
					loadSelection();
				}
				folderEditor.stageRenameTo(newName.trim());
			} else {
				ApiEndpoint endpoint = vault.findEndpointById(item.id);
				String newName = JOptionPane.showInputDialog(this, "New endpoint name:", endpoint.getName());
				if (newName == null || newName.isBlank()) {
					return;
				}
				if (!CARD_ENDPOINT.equals(currentCard)) {
					loadSelection();
				}
				endpointEditor.stageRenameTo(newName.trim());
			}
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	/** SPRINT XT02B, section 5: exposed for the Endpoint menu's "Delete" item - same action the button already triggered, now also guarded (a structural action can otherwise silently discard an in-progress field edit in the other card). */
	public void delete() {
		if (!confirmDiscardCurrentIfDirty(currentEditorLabel())) {
			return;
		}
		TreeItem item = selectedItem();
		if (item == null) {
			return;
		}
		int confirm = JOptionPane.showConfirmDialog(this, "Delete this " + (item.isFolder() ? "folder" : "endpoint") + "?\nThis cannot be undone.",
				"Delete", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			if (item.isFolder()) {
				// Checks for blocking children before mutating anything - see
				// ApiDefinitionsVault#deleteEndpointGroupPermanently's javadoc (SPRINT XT02 verification
				// finding 5): a refused deletion must leave the folder exactly as it was, not deactivated.
				vault.deleteEndpointGroupPermanently(item.id);
			} else {
				vault.deactivateEndpoint(item.id);
				vault.hardDeleteEndpoint(item.id);
			}
			reload();
		} catch (BroadSQLException ex) {
			// Expected path for a non-empty folder - ApiDefinitionsVault#hardDeleteEndpointGroup names
			// exactly what is blocking it. Not a bug, so no error logging here.
			JOptionPane.showMessageDialog(this, ex.getLocalizedMessage());
		}
	}

	private boolean isActive(String statusId) {
		return DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(statusId);
	}

	private static final class Filter {
		final String needleLower;
		final Set<Integer> matchingGroupIds;

		Filter(String needleLower, Set<Integer> matchingGroupIds) {
			this.needleLower = needleLower;
			this.matchingGroupIds = matchingGroupIds;
		}
	}

	private static final class TreeItem {
		final boolean folder;
		final Integer id;
		final String label;

		private TreeItem(boolean folder, Integer id, String label) {
			this.folder = folder;
			this.id = id;
			this.label = label;
		}

		static TreeItem folder(Integer id, String label) {
			return new TreeItem(true, id, label);
		}

		static TreeItem endpoint(Integer id, String label) {
			return new TreeItem(false, id, label);
		}

		boolean isFolder() {
			return folder;
		}

		@Override
		public String toString() {
			return label;
		}
	}
}
