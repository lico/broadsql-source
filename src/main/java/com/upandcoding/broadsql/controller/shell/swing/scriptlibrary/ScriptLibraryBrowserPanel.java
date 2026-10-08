package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;
import com.upandcoding.broadsql.controller.shell.swing.BroadSqlLookAndFeel;

/**
 * The BroadSQL Editor's left-hand "Scripts" pane (SPRINT 0917-01, reshaped by SPRINT 1909S and SPRINT 3009A): the
 * Scripts Library as a folder/file tree whose top level is the library root's own content (the root itself is
 * not shown), with a search field above it. Every text file in the library is a Script, whatever its extension;
 * the reserved {@code archives} folder is never shown.
 *
 * <p>Within every folder, folders come first, then Scripts, each alphabetically without regard to case (ties
 * broken by exact spelling, so the order never depends on filesystem enumeration). Folders show a closed or open
 * folder icon, Scripts a document icon ({@link EditorIcons}). A Script opens on double click or Enter; right-click
 * selects the item under the pointer and offers its actions; dragging the selected Scripts and folders (several
 * with Ctrl or Shift click, JTree's usual discontiguous selection) onto a folder, or onto empty space (the library
 * root), asks to move them there.
 *
 * <p>New Script and New Folder always target {@link #targetFolderForNew()}, the one rule every entry point uses.
 * The selection is never a trigger: selecting an item opens nothing, so the Editor can mirror its active tab in the
 * tree ({@link #revealAsset}, {@link #clearSelection}) without any feedback loop.
 *
 * <p>Read-only towards the filesystem/history - every mutating action (New/New Folder/Rename/Duplicate/Delete/
 * move) is reported to a {@link ScriptLibraryBrowserListener} rather than performed here, so
 * {@code ScriptLibraryService} stays the single place that touches the library/vault. Folder expansion and the
 * selection are kept across {@link #reload()}.
 */
public final class ScriptLibraryBrowserPanel extends JPanel {

	private static final String TITLE = "Scripts";
	/** The hidden root's user object. */
	private static final String ROOT_LABEL = "Scripts Library";

	private final ScriptLibraryService service;
	private ScriptLibraryBrowserListener listener;

	private JTextField searchField;
	private JTree tree;
	private DefaultTreeModel treeModel;
	private boolean firstLoad = true;

	public ScriptLibraryBrowserPanel(ScriptLibraryService service) {
		this.service = service;
		buildUi();
	}

	public void setListener(ScriptLibraryBrowserListener listener) {
		this.listener = listener;
	}

	private void buildUi() {
		setLayout(new BorderLayout());

		JLabel title = BroadSqlLookAndFeel.sectionTitleLabel(TITLE);
		searchField = new JTextField();
		searchField.putClientProperty("JTextField.placeholderText", "Search");
		searchField.putClientProperty("JTextField.leadingIcon", EditorIcons.get(EditorIcons.Kind.SEARCH));
		searchField.putClientProperty("JTextField.showClearButton", true);
		searchField.setToolTipText("Filter the Scripts by path or description");
		searchField.getAccessibleContext().setAccessibleName("Search Scripts");
		searchField.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				reload();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				reload();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				reload();
			}
		});
		JPanel header = new JPanel(new BorderLayout(0, 6));
		header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		header.add(title, BorderLayout.NORTH);
		header.add(searchField, BorderLayout.SOUTH);

		DefaultMutableTreeNode root = new DefaultMutableTreeNode(ROOT_LABEL);
		treeModel = new DefaultTreeModel(root);
		tree = new JTree(treeModel);
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
		tree.setCellRenderer(new ItemRenderer());
		tree.getAccessibleContext().setAccessibleName("Scripts Library");
		tree.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && tree.getPathForLocation(e.getX(), e.getY()) != null) {
					openSelected();
				}
			}

			@Override
			public void mousePressed(MouseEvent e) {
				maybeShowContextMenu(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				maybeShowContextMenu(e);
			}
		});
		tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "broadsql-open");
		tree.getActionMap().put("broadsql-open", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				activateSelected();
			}
		});
		if (!java.awt.GraphicsEnvironment.isHeadless()) {
			tree.setDragEnabled(true); // throws HeadlessException without a display (headless tests)
		}
		tree.setDropMode(DropMode.ON);
		tree.setTransferHandler(new MoveTransferHandler());

		JScrollPane treeScroll = new JScrollPane(tree);
		treeScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, EditorActions.separatorColor()));
		treeScroll.setMinimumSize(new Dimension(120, 100));

		add(header, BorderLayout.NORTH);
		add(treeScroll, BorderLayout.CENTER);

		reload();
	}

	/** Clears the selection (the hidden library root is "selected" when nothing is) and gives the tree keyboard focus. Does not open anything. */
	public void selectRoot() {
		tree.clearSelection();
		tree.requestFocusInWindow();
	}

	/** Selects the folder node {@code folderPath} if it is shown (test/inspection and "reveal" hook). */
	public void selectFolder(String folderPath) {
		select(findNode(folderPath, true));
	}

	/** Selects the Script {@code relativePath} if it is shown, expanding its folders (after a move, the moved Script stays selected). */
	public void selectAsset(String relativePath) {
		select(findNode(relativePath, false));
	}

	/**
	 * Mirrors the Editor's active tab: selects exactly the Script {@code relativePath}, expanding its folders and
	 * scrolling it into view. Opens nothing. A Script not shown (filtered out by Search) leaves the selection empty
	 * rather than on an unrelated item.
	 */
	public void revealAsset(String relativePath) {
		DefaultMutableTreeNode node = findNode(relativePath, false);
		if (node == null) {
			tree.clearSelection();
			return;
		}
		select(node);
	}

	/**
	 * The tree as a reflection of the Editor's active tab (SPRINT 3009A): a saved Script is selected and revealed
	 * ({@link #revealAsset}); a new unsaved Script clears the selection, so no unrelated Script looks active; with no
	 * tab the tree is left as it is. Opens nothing.
	 */
	public void mirror(ScriptEditorTab activeTab) {
		if (activeTab == null) {
			return;
		}
		if (activeTab.asset().isUnsaved()) {
			clearSelection();
		} else {
			revealAsset(activeTab.asset().relativePath());
		}
	}

	/** Clears the selection: the active tab is a new Script with no place in the library yet. */
	public void clearSelection() {
		tree.clearSelection();
	}

	/** Selects every shown item among {@code paths} (Scripts or folders), the first one scrolled into view: the items just moved. */
	public void selectPaths(List<String> paths) {
		List<TreePath> found = new ArrayList<>();
		for (String path : paths) {
			DefaultMutableTreeNode node = findNode(path, true);
			if (node == null) {
				node = findNode(path, false);
			}
			if (node != null) {
				found.add(new TreePath(node.getPath()));
			}
		}
		tree.setSelectionPaths(found.toArray(new TreePath[0]));
		if (!found.isEmpty()) {
			tree.scrollPathToVisible(found.get(0));
		}
	}

	/** The library paths of the selected items, in tree order (test/inspection hook). */
	public List<String> selectedPaths() {
		List<String> paths = new ArrayList<>();
		for (MovedItem item : selectedItems()) {
			paths.add(item.path());
		}
		return paths;
	}

	private void select(DefaultMutableTreeNode node) {
		if (node != null) {
			TreePath path = new TreePath(node.getPath());
			tree.setSelectionPath(path);
			tree.scrollPathToVisible(path);
		}
	}

	/** The relative path of the selected Script leaf, or {@code null} (test/inspection hook). */
	public String selectedAssetPath() {
		return selectedItem() instanceof AssetItem asset ? asset.relativePath : null;
	}

	/**
	 * The folder a New should create in: the selected folder, or the folder containing the selected
	 * Script; {@code null} for the library root (including when nothing is selected).
	 */
	public String selectedFolder() {
		return targetFolderForNew();
	}

	/**
	 * The one destination rule of New Script and New Folder, whichever way they are invoked (toolbar, File menu,
	 * context menu): a selected folder is the destination; a selected Script's own folder is (a new item goes beside
	 * it, never "under" a file); with nothing selected, the library root ({@code null}). With several items
	 * selected, the lead item (the last one clicked) decides.
	 */
	public String targetFolderForNew() {
		return folderOf(selectedItem());
	}

	private static String folderOf(Object item) {
		if (item instanceof FolderItem folder) {
			return folder.path;
		}
		if (item instanceof AssetItem asset) {
			return parentOf(asset.relativePath);
		}
		return null;
	}

	/** The folder holding {@code path}; {@code null} for the library root. */
	static String parentOf(String path) {
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(0, slash) : null;
	}

	/** Opens the selected Script (File &gt; Open, Enter, double click); {@code false} if no Script is selected. */
	public boolean openSelected() {
		if (selectedItem() instanceof AssetItem asset && listener != null) {
			listener.onAssetOpenRequested(asset.relativePath);
			return true;
		}
		return false;
	}

	/** Enter: opens a selected Script; on a folder, expands or collapses it like a desktop file browser. */
	private void activateSelected() {
		if (openSelected()) {
			return;
		}
		TreePath selection = tree.getSelectionPath();
		if (selection != null && selectedItem() instanceof FolderItem) {
			if (tree.isExpanded(selection)) {
				tree.collapsePath(selection);
			} else {
				tree.expandPath(selection);
			}
		}
	}

	/** The lead selected item (the last one clicked when several are selected), or {@code null}. */
	private Object selectedItem() {
		TreePath lead = tree.getLeadSelectionPath();
		TreePath selection = lead != null && tree.isPathSelected(lead) ? lead : tree.getSelectionPath();
		return selection == null ? null : itemOf(selection);
	}

	/** Every selected Script and folder, in tree order. */
	private List<MovedItem> selectedItems() {
		List<MovedItem> items = new ArrayList<>();
		int[] rows = tree.getSelectionRows();
		TreePath[] paths = tree.getSelectionPaths();
		if (paths == null) {
			return items;
		}
		List<TreePath> ordered = new ArrayList<>(List.of(paths));
		if (rows != null && rows.length == paths.length) {
			ordered.sort(Comparator.comparingInt(tree::getRowForPath));
		}
		for (TreePath path : ordered) {
			Object item = itemOf(path);
			if (item instanceof AssetItem a) {
				items.add(new MovedItem(a.relativePath, false));
			} else if (item instanceof FolderItem f) {
				items.add(new MovedItem(f.path, true));
			}
		}
		return items;
	}

	private static Object itemOf(TreePath path) {
		return path.getLastPathComponent() instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
	}

	// ------------------------------------------------------------------
	// Context menu
	// ------------------------------------------------------------------

	/**
	 * Right-click acts on the item under the pointer: that row is selected first (never a previously selected,
	 * unrelated item), and a click on empty space clears the selection and offers the library root's actions.
	 */
	private void maybeShowContextMenu(MouseEvent e) {
		if (!e.isPopupTrigger()) {
			return;
		}
		TreePath path = tree.getPathForLocation(e.getX(), e.getY());
		if (path == null) {
			int row = tree.getClosestRowForLocation(e.getX(), e.getY());
			java.awt.Rectangle bounds = row >= 0 ? tree.getRowBounds(row) : null;
			path = bounds != null && e.getY() >= bounds.y && e.getY() < bounds.y + bounds.height ? tree.getPathForRow(row) : null;
		}
		if (path != null) {
			if (!tree.isPathSelected(path)) {
				tree.setSelectionPath(path);
			}
			tree.setLeadSelectionPath(path);
		} else {
			tree.clearSelection();
		}
		tree.requestFocusInWindow();
		contextMenuFor(path == null ? null : itemOf(path)).show(tree, e.getX(), e.getY());
	}

	/** The context menu for a Script, a folder, or ({@code null}) the library root. */
	JPopupMenu contextMenuFor(Object item) {
		JPopupMenu menu = new JPopupMenu();
		String folder = folderOf(item);
		if (item instanceof AssetItem asset) {
			menu.add(menuItem("Open", () -> listener.onAssetOpenRequested(asset.relativePath)));
			menu.addSeparator();
		}
		menu.add(menuItem("New Script", () -> listener.onNewRequested(folder)));
		menu.add(menuItem("New Folder...", () -> listener.onNewFolderRequested(folder)));
		if (item instanceof AssetItem asset) {
			menu.addSeparator();
			menu.add(menuItem("Rename...", () -> listener.onRenameRequested(asset.relativePath)));
			menu.add(menuItem("Duplicate...", () -> listener.onDuplicateRequested(asset.relativePath)));
			menu.add(menuItem("Delete...", () -> listener.onDeleteRequested(asset.relativePath)));
			menu.addSeparator();
			for (ScriptPathText.Kind kind : ScriptPathText.Kind.values()) {
				menu.add(menuItem(kind.label, () -> listener.onCopyPathRequested(asset.relativePath, kind)));
			}
			menu.add(menuItem("Send to CLI", () -> listener.onSendToCliRequested(asset.relativePath)));
		} else if (item instanceof FolderItem f) {
			menu.addSeparator();
			menu.add(menuItem("Rename...", () -> listener.onRenameFolderRequested(f.path)));
			menu.add(menuItem("Delete...", () -> listener.onDeleteFolderRequested(f.path)));
			menu.addSeparator();
			menu.add(menuItem(ScriptPathText.Kind.LIBRARY_PATH.label, () -> listener.onCopyPathRequested(f.path, ScriptPathText.Kind.LIBRARY_PATH)));
			menu.add(menuItem(ScriptPathText.Kind.FULL_PATH.label, () -> listener.onCopyPathRequested(f.path, ScriptPathText.Kind.FULL_PATH)));
		}
		menu.addSeparator();
		menu.add(menuItem("Refresh", () -> listener.onRefreshRequested()));
		return menu;
	}

	/** Test hook: the context menu labels for the shown item {@code path} ({@code null}: empty space), {@code null} for a separator. */
	List<String> contextMenuLabels(String path, boolean folder) {
		Object item = null;
		if (path != null) {
			DefaultMutableTreeNode node = findNode(path, folder);
			Objects.requireNonNull(node, path);
			item = node.getUserObject();
		}
		List<String> labels = new ArrayList<>();
		for (Component c : contextMenuFor(item).getComponents()) {
			labels.add(c instanceof JMenuItem mi ? mi.getText() : null);
		}
		return labels;
	}

	private JMenuItem menuItem(String label, Runnable action) {
		JMenuItem item = new JMenuItem(label);
		item.addActionListener(e -> {
			if (listener != null) {
				action.run();
			}
		});
		return item;
	}

	// ------------------------------------------------------------------
	// Drag and drop
	// ------------------------------------------------------------------

	/** One dragged Script or folder of this tree. */
	record MovedItem(String path, boolean folder) {
	}

	/** What is being dragged: the selected items. */
	record MovedItems(List<MovedItem> items) {
	}

	/**
	 * The items that really move when {@code selection} is dropped: an item inside another selected folder is
	 * dropped (it moves with that folder, never a second time), as are duplicates. The service applies the same rule;
	 * the tree applies it first to decide whether a drop is possible at all.
	 */
	static List<MovedItem> normalize(List<MovedItem> selection) {
		List<MovedItem> effective = new ArrayList<>();
		for (MovedItem item : selection) {
			boolean duplicate = effective.stream().anyMatch(e -> e.path().equalsIgnoreCase(item.path()));
			boolean insideSelectedFolder = selection.stream()
					.anyMatch(other -> other.folder() && item.path().toLowerCase().startsWith(other.path().toLowerCase() + "/"));
			if (!duplicate && !insideSelectedFolder) {
				effective.add(item);
			}
		}
		return effective;
	}

	/**
	 * Why dropping {@code selection} onto {@code destinationFolderOrNull} can never be a valid move, or {@code null}
	 * if the owner should try it: a folder onto itself or one of its subfolders refuses the whole drop, and a drop
	 * where every item already is moves nothing. Name conflicts and filesystem errors depend on the disk: the owner
	 * checks them, for the whole batch, before moving anything.
	 */
	static String moveRefusal(List<MovedItem> selection, String destinationFolderOrNull) {
		List<MovedItem> effective = normalize(selection);
		int alreadyThere = 0;
		for (MovedItem item : effective) {
			String refusal = moveRefusal(item, destinationFolderOrNull);
			if (refusal == null) {
				continue;
			}
			if (!refusal.endsWith("is already there.")) {
				return refusal;
			}
			alreadyThere++;
		}
		return effective.isEmpty() || alreadyThere == effective.size() ? "Everything selected is already there." : null;
	}

	/**
	 * Why dropping {@code item} onto {@code destinationFolderOrNull} ({@code null}: the library root) can never be a
	 * valid move, or {@code null} if the owner should try it. Name conflicts and filesystem errors are the owner's
	 * to report: they depend on the disk, not on the tree.
	 */
	static String moveRefusal(MovedItem item, String destinationFolderOrNull) {
		String currentFolder = parentOf(item.path());
		if (Objects.equals(lower(currentFolder), lower(destinationFolderOrNull))) {
			return "'" + item.path() + "' is already there.";
		}
		if (item.folder() && destinationFolderOrNull != null) {
			String destination = destinationFolderOrNull.toLowerCase();
			String source = item.path().toLowerCase();
			if (destination.equals(source)) {
				return "A folder cannot be moved into itself.";
			}
			if (destination.startsWith(source + "/")) {
				return "A folder cannot be moved into one of its own subfolders.";
			}
		}
		return null;
	}

	private static String lower(String s) {
		return s == null ? null : s.toLowerCase();
	}

	/** The folder a drop on {@code path} targets: a folder itself, the folder holding a Script, or the root for empty space. */
	private static String dropFolder(TreePath path) {
		return path == null ? null : folderOf(itemOf(path));
	}

	/** Moves only: a Script or folder of this tree onto a folder of this tree. Nothing from outside (no import). */
	private final class MoveTransferHandler extends TransferHandler {

		private static final long serialVersionUID = 1L;
		private final DataFlavor flavor;

		MoveTransferHandler() {
			try {
				flavor = new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + MovedItems.class.getName(), "Scripts Library items",
						MovedItems.class.getClassLoader());
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public int getSourceActions(JComponent c) {
			return MOVE;
		}

		@Override
		protected Transferable createTransferable(JComponent c) {
			List<MovedItem> selection = selectedItems();
			if (selection.isEmpty()) {
				return null;
			}
			MovedItems moved = new MovedItems(selection);
			return new Transferable() {
				@Override
				public DataFlavor[] getTransferDataFlavors() {
					return new DataFlavor[] { flavor };
				}

				@Override
				public boolean isDataFlavorSupported(DataFlavor f) {
					return flavor.equals(f);
				}

				@Override
				public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException {
					if (!flavor.equals(f)) {
						throw new UnsupportedFlavorException(f);
					}
					return moved;
				}
			};
		}

		@Override
		public boolean canImport(TransferSupport support) {
			if (!support.isDrop() || !support.isDataFlavorSupported(flavor)) {
				return false;
			}
			support.setDropAction(MOVE);
			MovedItems moved = transferred(support);
			return moved != null && moveRefusal(moved.items(), dropFolder(((JTree.DropLocation) support.getDropLocation()).getPath())) == null;
		}

		@Override
		public boolean importData(TransferSupport support) {
			if (!canImport(support)) {
				return false;
			}
			List<MovedItem> items = normalize(transferred(support).items());
			String destination = dropFolder(((JTree.DropLocation) support.getDropLocation()).getPath());
			if (listener != null) {
				// After the drop completes: the owner may show a dialog (a conflict), which must not run inside the DnD callback.
				SwingUtilities.invokeLater(() -> listener.onMoveRequested(items, destination));
			}
			return true;
		}

		private MovedItems transferred(TransferSupport support) {
			try {
				return (MovedItems) support.getTransferable().getTransferData(flavor);
			} catch (UnsupportedFlavorException | java.io.IOException e) {
				return null;
			}
		}
	}

	// ------------------------------------------------------------------
	// Model
	// ------------------------------------------------------------------

	private DefaultMutableTreeNode findNode(String path, boolean folder) {
		Enumeration<?> nodes = ((DefaultMutableTreeNode) treeModel.getRoot()).depthFirstEnumeration();
		while (nodes.hasMoreElements()) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
			Object user = node.getUserObject();
			if (folder && user instanceof FolderItem item && item.path.equals(path)) {
				return node;
			}
			if (!folder && user instanceof AssetItem item && item.relativePath.equals(path)) {
				return node;
			}
		}
		return null;
	}

	/** The relative paths of every Script leaf currently shown (test/inspection hook). */
	public List<String> shownAssetPaths() {
		List<String> shown = new ArrayList<>();
		Enumeration<?> nodes = ((DefaultMutableTreeNode) treeModel.getRoot()).depthFirstEnumeration();
		while (nodes.hasMoreElements()) {
			Object user = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
			if (user instanceof AssetItem asset) {
				shown.add(asset.relativePath);
			}
		}
		return shown;
	}

	/** The relative paths of every folder currently shown (test/inspection hook). */
	public List<String> shownFolderPaths() {
		List<String> shown = new ArrayList<>();
		Enumeration<?> nodes = ((DefaultMutableTreeNode) treeModel.getRoot()).depthFirstEnumeration();
		while (nodes.hasMoreElements()) {
			Object user = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
			if (user instanceof FolderItem folder) {
				shown.add(folder.path);
			}
		}
		return shown;
	}

	/** The names shown directly under {@code folderPath} ({@code null}: the top level), in display order, folders marked with a trailing {@code /} (test/inspection hook). */
	List<String> shownChildren(String folderPath) {
		DefaultMutableTreeNode node = folderPath == null ? (DefaultMutableTreeNode) treeModel.getRoot() : findNode(folderPath, true);
		List<String> names = new ArrayList<>();
		if (node == null) {
			return names;
		}
		for (int i = 0; i < node.getChildCount(); i++) {
			Object user = ((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject();
			names.add(user instanceof FolderItem f ? f.label + "/" : user.toString());
		}
		return names;
	}

	/** Test hook: the tree component. */
	JTree tree() {
		return tree;
	}

	/**
	 * Rebuilds the tree from the current library state - never touches any open editor tab/dirty buffer (that
	 * guard lives in the frame). Folder expansion and the selected item (when it still exists) are preserved.
	 */
	public void reload() {
		Set<String> expanded = expandedFolders();
		Object selected = selectedItem();
		DefaultMutableTreeNode root = new DefaultMutableTreeNode(ROOT_LABEL);
		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();

		List<ScriptAsset> assets;
		List<String> folders;
		try {
			assets = service.listAssets();
			folders = service.listFolders();
		} catch (BroadSQLException notConfigured) {
			treeModel.setRoot(root); // the Scripts Library folder isn't configured/doesn't exist yet: an empty tree
			return;
		}

		Map<String, DefaultMutableTreeNode> folderNodes = new HashMap<>();
		if (query.isEmpty()) {
			for (String folder : folders) {
				folderNode(root, folderNodes, folder);
			}
		}
		for (ScriptAsset asset : assets) {
			if (!query.isEmpty() && !matches(asset, query)) {
				continue;
			}
			String path = asset.relativePath();
			int slash = path.lastIndexOf('/');
			DefaultMutableTreeNode parent = slash >= 0 ? folderNode(root, folderNodes, path.substring(0, slash)) : root;
			parent.add(new DefaultMutableTreeNode(new AssetItem(path, asset.displayName())));
		}
		sortChildren(root);
		treeModel.setRoot(root);
		tree.expandPath(new TreePath(root.getPath()));
		if (firstLoad || !query.isEmpty()) {
			expandAll();
			firstLoad = false;
		} else {
			restoreExpanded(expanded);
		}
		if (selected instanceof AssetItem a) {
			selectAsset(a.relativePath);
		} else if (selected instanceof FolderItem f) {
			selectFolder(f.path);
		}
	}

	private DefaultMutableTreeNode folderNode(DefaultMutableTreeNode root, Map<String, DefaultMutableTreeNode> folderNodes, String folderPath) {
		DefaultMutableTreeNode existing = folderNodes.get(folderPath);
		if (existing != null) {
			return existing;
		}
		int slash = folderPath.lastIndexOf('/');
		DefaultMutableTreeNode parent = slash >= 0 ? folderNode(root, folderNodes, folderPath.substring(0, slash)) : root;
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(new FolderItem(folderPath, slash >= 0 ? folderPath.substring(slash + 1) : folderPath));
		parent.add(node);
		folderNodes.put(folderPath, node);
		return node;
	}

	/**
	 * The one display order of the tree, in every folder: folders first, then Scripts; each group alphabetical
	 * without regard to case, ties (names differing only by case, possible on Linux) broken by exact spelling, so
	 * the order is fully deterministic whatever order the filesystem listed them in.
	 */
	static final Comparator<DefaultMutableTreeNode> DISPLAY_ORDER = Comparator
			.comparing((DefaultMutableTreeNode n) -> !(n.getUserObject() instanceof FolderItem))
			.thenComparing(n -> n.getUserObject().toString(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(n -> n.getUserObject().toString());

	private static void sortChildren(DefaultMutableTreeNode node) {
		List<DefaultMutableTreeNode> children = new ArrayList<>();
		for (int i = 0; i < node.getChildCount(); i++) {
			children.add((DefaultMutableTreeNode) node.getChildAt(i));
		}
		children.sort(DISPLAY_ORDER);
		node.removeAllChildren();
		for (DefaultMutableTreeNode child : children) {
			node.add(child);
			sortChildren(child);
		}
	}

	private Set<String> expandedFolders() {
		Set<String> expanded = new HashSet<>();
		DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
		Enumeration<TreePath> paths = tree.getExpandedDescendants(new TreePath(root.getPath()));
		if (paths != null) {
			while (paths.hasMoreElements()) {
				Object last = paths.nextElement().getLastPathComponent();
				if (last instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof FolderItem folder) {
					expanded.add(folder.path);
				}
			}
		}
		return expanded;
	}

	private void restoreExpanded(Set<String> expanded) {
		DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
		Enumeration<?> nodes = root.depthFirstEnumeration();
		while (nodes.hasMoreElements()) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
			if (node.getUserObject() instanceof FolderItem folder && expanded.contains(folder.path)) {
				tree.expandPath(new TreePath(node.getPath()));
			}
		}
	}

	private void expandAll() {
		for (int i = 0; i < tree.getRowCount(); i++) {
			tree.expandRow(i);
		}
	}

	private static boolean matches(ScriptAsset asset, String queryLower) {
		if (asset.relativePath().toLowerCase().contains(queryLower)) {
			return true;
		}
		String description = asset.metadataHeader().metadata().getDescription();
		return description != null && description.toLowerCase().contains(queryLower);
	}

	/** Folder icons (closed/open, even for an empty folder, which is a tree leaf) and the Script icon. */
	private static final class ItemRenderer extends DefaultTreeCellRenderer {
		private static final long serialVersionUID = 1L;
		private final javax.swing.Icon folderClosed = EditorIcons.get(EditorIcons.Kind.FOLDER_CLOSED);
		private final javax.swing.Icon folderOpen = EditorIcons.get(EditorIcons.Kind.FOLDER_OPEN);
		private final javax.swing.Icon script = EditorIcons.get(EditorIcons.Kind.SCRIPT);

		@Override
		public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
			super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
			Object user = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
			if (user instanceof FolderItem) {
				setIcon(expanded ? folderOpen : folderClosed);
			} else if (user instanceof AssetItem) {
				setIcon(script);
			}
			return this;
		}
	}

	/** Test hook: the icon kind the tree shows for the item {@code path}, given its expansion. */
	EditorIcons.Kind iconFor(String path, boolean folder) {
		DefaultMutableTreeNode node = findNode(path, folder);
		TreePath treePath = new TreePath(node.getPath());
		Component c = tree.getCellRenderer().getTreeCellRendererComponent(tree, node, false, tree.isExpanded(treePath), node.isLeaf(), 0, false);
		return ((EditorIcons.VectorIcon) ((JLabel) c).getIcon()).kind();
	}

	/** One Script leaf's identity in the tree - deliberately not the {@code ScriptAsset} itself, so the tree doesn't hold stale content snapshots between reloads. */
	private static final class AssetItem {
		final String relativePath;
		final String label;

		AssetItem(String relativePath, String label) {
			this.relativePath = relativePath;
			this.label = label;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof AssetItem other && relativePath.equals(other.relativePath);
		}

		@Override
		public int hashCode() {
			return Objects.hash(relativePath);
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** One folder node's identity: its path relative to the library root. */
	private static final class FolderItem {
		final String path;
		final String label;

		FolderItem(String path, String label) {
			this.path = path;
			this.label = label;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof FolderItem other && path.equals(other.path);
		}

		@Override
		public int hashCode() {
			return Objects.hash(path);
		}

		@Override
		public String toString() {
			return label;
		}
	}
}
