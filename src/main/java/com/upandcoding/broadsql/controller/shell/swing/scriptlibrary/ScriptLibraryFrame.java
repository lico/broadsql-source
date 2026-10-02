package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.core.catalog.EntryMetadata;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.FolderMoveOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.FormatOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.MetadataIntegrityContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.MetadataIntegrityException;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.RunOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.SaveOutcome;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptFormatterService;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibraryService;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptLibrarySession;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunContext;
import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptRunCoordinator;
import com.upandcoding.broadsql.controller.shell.swing.AppIcon;
import com.upandcoding.broadsql.controller.shell.swing.BroadSqlLookAndFeel;
import com.upandcoding.broadsql.controller.shell.swing.api.UnsavedChangesDialog;

/**
 * The BroadSQL Editor's main window (SPRINT 0917-01; "Script Library" was its earlier user-facing name) - one persistent workspace per BroadSQL process
 * (see {@code ScriptLibraryWorkspaceHolder}). Follows the same BroadSQL GUI conventions as
 * {@code JSettingsFrame}/{@code JApiSettingsFrame}: {@link BroadSqlLookAndFeel#installOnce()} first,
 * a {@link JMenuBar} (text-only items) and {@link UnsavedChangesDialog} for every dirty-buffer guard.
 *
 * <p><b>Layout</b> (SPRINT 3009A): an icon-only toolbar under the menu, then three resizable panes side by side:
 * the Scripts tree ({@link ScriptLibraryBrowserPanel}), the tabbed editor, which runs from the toolbar down to
 * the status bar and receives the extra width when the window grows, and a narrow inspector with the Metadata
 * and Output tabs. Every command is one {@link Action} ({@link EditorActions}) shared by the toolbar, the menus
 * and the keyboard shortcuts. Divider positions are not persisted: BroadSQL has no store for window
 * preferences, and this window does not introduce one.
 *
 * <p><b>SPRINT 3009A correction pass</b>:
 * <ul>
 * <li>New Script opens a new, unnamed and unsaved tab ({@link ScriptEditorTabbedPane#newUnsavedScript}); its first
 * Save asks for the name. {@code EDIT}/{@code LIB EDIT} without a name open the window on such a tab
 * ({@link #openWorkspaceWithNewScript}), so the editor is never empty at startup.</li>
 * <li>The Scripts tree mirrors the active tab ({@link #syncTreeSelection}): the active Script is selected and
 * revealed; a new unsaved Script clears the selection. Selection in the tree never opens anything, so there is
 * no feedback loop.</li>
 * <li>Metadata edits patch only the edited directive of the live buffer ({@link MetadataPanel.Change}), and Save
 * reloads the Metadata tab from the saved text.</li>
 * <li>Validate, Save with Comment and the View &gt; Refresh / toolbar Refresh entries are gone; Refresh remains in
 * the Scripts pane's context menu, for changes made outside BroadSQL.</li>
 * <li>Run is available whenever a Script is active and a connection is open, clean or not
 * ({@link EditorActions#runEnabled}), and always comes back from a run, whatever happened.</li>
 * </ul>
 *
 * <p><b>Window lifecycle</b>: closing this window (the X button, or File > Close Window) first closes every tab
 * through the normal guard (see {@link #closeWorkspaceWindow}): a clean tab closes silently and a dirty one asks
 * Save/Discard/Cancel. Only when no tab remains is the window hidden ({@code setDefaultCloseOperation(DO_NOTHING_ON_CLOSE)}
 * plus a {@link WindowAdapter}, never {@code dispose()}) and the workspace marked closed, so nothing stale survives and
 * application exit has nothing left to protect. Reopening reuses the same window. Closing an individual
 * <em>tab</em> runs the same guard - see {@link #closeTabWithGuard}, and {@link ScriptTabContextMenu} for the batch closes.
 *
 * <p>Real logic (open/focus decisions, dirty tracking) lives in {@link ScriptLibrarySession}, not
 * here - this class is a thin renderer over it, per the sprint's headless-testing strategy
 * ({@code docs/plans/SPRINT_0917-01_IMPLEMENTATION_PLAN.md}, "Headless vs. real GUI verification").
 * All show/hide/focus/tab-select operations are expected to be called on the EDT, exactly like every
 * other BroadSQL Swing window.
 */
public final class ScriptLibraryFrame extends JFrame {

	private static final Logger log = LoggerFactory.getLogger(ScriptLibraryFrame.class);
	private static final String FRAME_TITLE = "BroadSQL Editor";

	/** Default width of the Scripts pane and of the right-hand inspector (Metadata/Output). */
	static final int SCRIPTS_PANE_WIDTH = 220;
	static final int INSPECTOR_PANE_WIDTH = 290;

	private final ScriptLibraryService service;
	private final ScriptLibrarySession session;

	private final ScriptFormatterService formatterService = new ScriptFormatterService();
	private final ScriptRunCoordinator runCoordinator = new ScriptRunCoordinator();

	private static final int INSPECTOR_TAB_METADATA = 0;
	private static final int INSPECTOR_TAB_OUTPUT = 1;

	private ScriptLibraryBrowserPanel browserPanel;
	private ScriptEditorTabbedPane editorTabbedPane;
	private ScriptTabCloser tabCloser;
	private MetadataPanel metadataPanel;
	/** The tab {@link #metadataPanel} currently reflects/edits - see {@link #refreshMetadataPanel()}. */
	private ScriptEditorTab metadataBoundTab;
	private JTabbedPane inspectorTabs;
	private JTextArea executionOutputArea;
	private Action runAction;
	/** True from the start of a Run until its result is shown, whatever the result. */
	private boolean running;
	private JLabel statusLabel;

	private ScriptRunContext executionContext = ScriptRunContext.empty();

	public ScriptLibraryFrame(ScriptLibraryService service, ScriptLibrarySession session) {
		this.service = service;
		this.session = session;
	}

	public void initApp() {
		BroadSqlLookAndFeel.installOnce();
		initComponents();
	}

	private void initComponents() {
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		setTitle(FRAME_TITLE);
		Image icon = AppIcon.load();
		if (icon != null) {
			setIconImage(icon);
		}
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				closeWorkspaceWindow();
			}
		});

		browserPanel = new ScriptLibraryBrowserPanel(service);
		browserPanel.setListener(new ScriptLibraryBrowserListener() {
			@Override
			public void onAssetOpenRequested(String relativePath) {
				openAsset(relativePath);
			}

			@Override
			public void onNewRequested(String folderOrNull) {
				newScript(folderOrNull);
			}

			@Override
			public void onNewFolderRequested(String parentFolderOrNull) {
				newFolder(parentFolderOrNull);
			}

			@Override
			public void onRenameRequested(String relativePath) {
				renameAsset(relativePath);
			}

			@Override
			public void onDuplicateRequested(String relativePath) {
				duplicateAsset(relativePath);
			}

			@Override
			public void onDeleteRequested(String relativePath) {
				deleteAsset(relativePath);
			}

			@Override
			public void onRenameFolderRequested(String folderPath) {
				renameFolder(folderPath);
			}

			@Override
			public void onDeleteFolderRequested(String folderPath) {
				deleteFolder(folderPath);
			}

			@Override
			public void onRefreshRequested() {
				refreshAll();
			}

			@Override
			public void onMoveRequested(List<ScriptLibraryBrowserPanel.MovedItem> items, String destinationFolderOrNull) {
				moveItems(items, destinationFolderOrNull);
			}

			@Override
			public void onCopyPathRequested(String relativePath, ScriptPathText.Kind kind) {
				copyPath(relativePath, kind);
			}

			@Override
			public void onSendToCliRequested(String relativePath) {
				ScriptEditorTab tab = openTabForPath(relativePath);
				sendToCli(relativePath, tab != null && tab.isDirty());
			}
		});

		editorTabbedPane = new ScriptEditorTabbedPane();
		editorTabbedPane.putClientProperty("JTabbedPane.tabHeight", 28);
		tabCloser = new ScriptTabCloser(editorTabbedPane, session, tab -> UnsavedChangesDialog.ask(this, tab.asset().displayName()), tab -> saveTab(tab));
		editorTabbedPane.addChangeListener(e -> {
			refreshMetadataPanel();
			syncTreeSelection();
			updateRunEnabled();
		});
		// Visible per-tab close control ("x"), FlatLaf's own native tab-close affordance rather than a
		// hand-built third visual language (SPRINT 0917-01 corrective acceptance pass, defect 7: tabs
		// had no obvious close mechanism at all). The callback still runs the normal dirty-tab guard -
		// FlatLaf only renders the control and reports the click, it never removes the tab itself.
		editorTabbedPane.putClientProperty("JTabbedPane.tabClosable", true);
		editorTabbedPane.putClientProperty("JTabbedPane.tabCloseCallback",
				(java.util.function.BiConsumer<JTabbedPane, Integer>) (pane, index) -> closeTabAtIndex(index));
		ScriptTabContextMenu.install(editorTabbedPane, tabCloser, new ScriptTabContextMenu.ScriptActions() {
			@Override
			public void copyPath(ScriptEditorTab tab, ScriptPathText.Kind kind) {
				if (requireSaved(tab)) {
					ScriptLibraryFrame.this.copyPath(tab.asset().relativePath(), kind);
				}
			}

			@Override
			public void sendToCli(ScriptEditorTab tab) {
				if (requireSaved(tab)) {
					ScriptLibraryFrame.this.sendToCli(tab.asset().relativePath(), tab.isDirty());
				}
			}
		});

		metadataPanel = new MetadataPanel();
		metadataPanel.setCommitListener(this::applyMetadataChangeToBoundTab);
		// The Database Group and Environment selectors offer exactly what Save accepts: the CDF's ids, read when a list opens.
		metadataPanel.setChoicesSupplier(() -> MetadataChoices.fromVault(executionContext.databaseConnectionsVault()));
		metadataPanel.setPathCopyHandler(kind -> {
			ScriptEditorTab tab = editorTabbedPane.activeTab();
			if (tab != null && !tab.asset().isUnsaved()) {
				copyPath(tab.asset().relativePath(), kind);
			}
		});

		executionOutputArea = new JTextArea();
		executionOutputArea.setEditable(false);
		executionOutputArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));

		inspectorTabs = new JTabbedPane();
		inspectorTabs.putClientProperty("JTabbedPane.tabHeight", 28);
		inspectorTabs.addTab("Metadata", borderless(new JScrollPane(metadataPanel)));
		inspectorTabs.addTab("Output", borderless(new JScrollPane(executionOutputArea)));
		inspectorTabs.setMinimumSize(new Dimension(200, 100));
		inspectorTabs.setPreferredSize(new Dimension(INSPECTOR_PANE_WIDTH, 400));
		browserPanel.setMinimumSize(new Dimension(140, 100));
		editorTabbedPane.setMinimumSize(new Dimension(300, 100));

		JSplitPane editorAndInspector = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorTabbedPane, inspectorTabs);
		// The editor takes every extra pixel when the window grows; the inspector keeps its width.
		editorAndInspector.setResizeWeight(1.0);
		editorAndInspector.setContinuousLayout(true);
		editorAndInspector.setBorder(null);
		placeInspectorDividerOnceSized(editorAndInspector);
		JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, browserPanel, editorAndInspector);
		mainSplit.setResizeWeight(0.0);
		mainSplit.setContinuousLayout(true);
		mainSplit.setBorder(null);
		mainSplit.setDividerLocation(SCRIPTS_PANE_WIDTH);

		statusLabel = new JLabel(" ");
		statusLabel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, EditorActions.separatorColor()),
				BorderFactory.createEmptyBorder(3, 8, 3, 8)));

		buildActionsAndMenuBar();

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(EditorActions.toolBar(actions), BorderLayout.NORTH);
		getContentPane().add(mainSplit, BorderLayout.CENTER);
		getContentPane().add(statusLabel, BorderLayout.SOUTH);
		setSize(1100, 780);
		setMinimumSize(new Dimension(720, 420));
		setLocationRelativeTo(null);
	}

	private static JScrollPane borderless(JScrollPane scroll) {
		scroll.setBorder(null);
		return scroll;
	}

	/** Puts the editor/inspector divider {@link #INSPECTOR_PANE_WIDTH} from the right edge the first time the split has a real width; after that the user's position is kept. */
	private static void placeInspectorDividerOnceSized(JSplitPane split) {
		split.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				if (split.getWidth() > 0) {
					split.setDividerLocation(Math.max(300, split.getWidth() - INSPECTOR_PANE_WIDTH - split.getDividerSize()));
					split.removeComponentListener(this);
				}
			}
		});
	}

	private interface AssetAction {
		void run(String relativePath);
	}

	/** Runs {@code action} on the active tab's Script; a new Script not saved yet has no path, so it is asked to be saved first. */
	private void withActiveAsset(AssetAction action) {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab != null && requireSaved(tab)) {
			action.run(tab.asset().relativePath());
		}
	}

	/** {@code false}, with a status message, for a new Script not saved yet: it has no name or path for this action. */
	private boolean requireSaved(ScriptEditorTab tab) {
		if (tab.asset().isUnsaved()) {
			status("'" + tab.asset().displayName() + "' is not saved yet: save it first to give it a name.");
			return false;
		}
		return true;
	}

	/** The commands, each one Action shared by the toolbar, the menus and the keyboard shortcuts (see {@link EditorActions.Command}). */
	private java.util.Map<EditorActions.Command, Action> actions;

	private static KeyStroke ctrl(int keyCode) {
		return EditorActions.ctrl(keyCode);
	}

	private void buildActionsAndMenuBar() {
		java.util.Map<EditorActions.Command, Runnable> handlers = new java.util.EnumMap<>(EditorActions.Command.class);
		handlers.put(EditorActions.Command.NEW, () -> newScript(browserPanel.targetFolderForNew()));
		handlers.put(EditorActions.Command.NEW_FOLDER, () -> newFolder(browserPanel.targetFolderForNew()));
		handlers.put(EditorActions.Command.SAVE, this::saveActive);
		handlers.put(EditorActions.Command.FORMAT, () -> formatActive(false));
		handlers.put(EditorActions.Command.RUN, this::runActive);
		handlers.put(EditorActions.Command.HISTORY, this::showHistoryForActive);
		handlers.put(EditorActions.Command.RENAME, () -> withActiveAsset(this::renameAsset));
		handlers.put(EditorActions.Command.DUPLICATE, () -> withActiveAsset(this::duplicateAsset));
		handlers.put(EditorActions.Command.DELETE, () -> withActiveAsset(this::deleteAsset));
		actions = EditorActions.actions(handlers);
		Action newAction = actions.get(EditorActions.Command.NEW);
		Action newFolderAction = actions.get(EditorActions.Command.NEW_FOLDER);
		Action saveAction = actions.get(EditorActions.Command.SAVE);
		Action formatAction = actions.get(EditorActions.Command.FORMAT);
		Action historyAction = actions.get(EditorActions.Command.HISTORY);
		Action renameAction = actions.get(EditorActions.Command.RENAME);
		Action duplicateAction = actions.get(EditorActions.Command.DUPLICATE);
		Action deleteAction = actions.get(EditorActions.Command.DELETE);
		runAction = actions.get(EditorActions.Command.RUN);
		updateRunEnabled();

		JMenuBar menuBar = new JMenuBar();

		JMenu fileMenu = new JMenu("File");
		fileMenu.add(EditorActions.menuItem(newAction, "New Script"));
		fileMenu.add(EditorActions.menuItem(newFolderAction, "New Folder..."));
		JMenuItem openItem = new JMenuItem("Open");
		openItem.addActionListener(e -> {
			if (!browserPanel.openSelected()) {
				status("Select a Script in the Scripts pane to open it (or double click it).");
			}
		});
		fileMenu.add(openItem);
		fileMenu.add(EditorActions.menuItem(saveAction, "Save"));
		JMenuItem saveAll = new JMenuItem("Save All");
		saveAll.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
		saveAll.addActionListener(e -> saveAll());
		fileMenu.add(saveAll);
		fileMenu.addSeparator();
		fileMenu.add(EditorActions.menuItem(renameAction, "Rename..."));
		fileMenu.add(EditorActions.menuItem(duplicateAction, "Duplicate..."));
		fileMenu.add(EditorActions.menuItem(deleteAction, "Delete..."));
		fileMenu.addSeparator();
		JMenuItem closeTabItem = new JMenuItem("Close");
		closeTabItem.setAccelerator(ctrl(KeyEvent.VK_W));
		closeTabItem.addActionListener(e -> closeActiveTab());
		fileMenu.add(closeTabItem);
		JMenuItem closeAllItem = new JMenuItem("Close All");
		closeAllItem.addActionListener(e -> closeAllTabs());
		fileMenu.add(closeAllItem);
		fileMenu.addSeparator();
		JMenuItem recentlyDeletedItem = new JMenuItem("Recently Deleted...");
		recentlyDeletedItem.addActionListener(e -> showRecentlyDeleted());
		fileMenu.add(recentlyDeletedItem);
		fileMenu.addSeparator();
		JMenuItem exitItem = new JMenuItem("Close Window");
		exitItem.addActionListener(e -> closeWorkspaceWindow());
		fileMenu.add(exitItem);

		JMenu editMenu = new JMenu("Edit");
		JMenuItem undoItem = new JMenuItem("Undo");
		undoItem.setAccelerator(ctrl(KeyEvent.VK_Z));
		undoItem.addActionListener(e -> undoActive());
		editMenu.add(undoItem);
		JMenuItem redoItem = new JMenuItem("Redo");
		redoItem.setAccelerator(ctrl(KeyEvent.VK_Y));
		redoItem.addActionListener(e -> redoActive());
		editMenu.add(redoItem);
		editMenu.addSeparator();
		JMenuItem findItem = new JMenuItem("Find...");
		findItem.setAccelerator(ctrl(KeyEvent.VK_F));
		findItem.addActionListener(e -> findInActive());
		editMenu.add(findItem);
		JMenuItem replaceItem = new JMenuItem("Replace...");
		replaceItem.setAccelerator(ctrl(KeyEvent.VK_H));
		replaceItem.addActionListener(e -> replaceInActive());
		editMenu.add(replaceItem);
		editMenu.addSeparator();
		editMenu.add(EditorActions.menuItem(formatAction, "Format Document"));
		JMenuItem formatSelectionItem = new JMenuItem("Format Selection");
		formatSelectionItem.addActionListener(e -> formatActive(true));
		editMenu.add(formatSelectionItem);
		editMenu.addSeparator();
		for (ScriptPathText.Kind kind : ScriptPathText.Kind.values()) {
			JMenuItem copyItem = new JMenuItem(kind.label);
			copyItem.addActionListener(e -> withActiveAsset(path -> copyPath(path, kind)));
			editMenu.add(copyItem);
		}

		JMenu runMenu = new JMenu("Run");
		runMenu.add(EditorActions.menuItem(runAction, "Run"));
		JMenuItem sendToCliItem = new JMenuItem("Send to CLI");
		sendToCliItem.addActionListener(e -> {
			ScriptEditorTab tab = editorTabbedPane.activeTab();
			if (tab != null && requireSaved(tab)) {
				sendToCli(tab.asset().relativePath(), tab.isDirty());
			}
		});
		runMenu.add(sendToCliItem);

		JMenu historyMenu = new JMenu("History");
		historyMenu.add(EditorActions.menuItem(historyAction, "History..."));

		JMenu helpMenu = new JMenu("Help");
		JMenuItem aboutItem = new JMenuItem("About BroadSQL Editor");
		aboutItem.addActionListener(e -> EditorAboutDialog.show(this));
		helpMenu.add(aboutItem);

		menuBar.add(fileMenu);
		menuBar.add(editMenu);
		menuBar.add(runMenu);
		menuBar.add(historyMenu);
		menuBar.add(helpMenu);
		setJMenuBar(menuBar);
	}

	// ------------------------------------------------------------------
	// Open/focus entry points (called by ScriptLibraryWorkspaceLauncher)
	// ------------------------------------------------------------------

	/**
	 * Refreshed on every {@code EDIT}/{@code LIB EDIT} invocation (see
	 * {@code ScriptLibraryWorkspaceLauncher}) - a connection can change between calls
	 * ({@code CONNECT}/{@code DISCONNECT}), so a stale snapshot from the workspace's first creation
	 * would make {@code Run}'s enabled state wrong. Only {@code Run}/{@code Send to CLI} read this;
	 * every other capability in the window is unaffected by it.
	 */
	public void setExecutionContext(ScriptRunContext context) {
		this.executionContext = context;
		updateRunEnabled();
	}

	public void openOrFocusWorkspace() {
		boolean wasAlreadyOpen = session.isOpen();
		session.markOpen();
		setVisible(true);
		if (getExtendedState() == Frame.ICONIFIED) {
			setExtendedState(Frame.NORMAL);
		}
		toFront();
		requestFocus();
		if (wasAlreadyOpen) {
			// Reshow after hide (spec section 27/29) - run the same external-modification detection as
			// Refresh for every open tab. Skipped on the very first open (nothing could have changed
			// outside BroadSQL between "not yet created" and "just created").
			checkExternalChangesForAllOpenTabs();
		}
	}

	/**
	 * {@code EDIT}/{@code LIB EDIT} without a name (SPRINT 3009A): the window, with a new unsaved Script ready to
	 * type in, exactly what New Script gives. An untouched new Script already open is reused rather than adding a
	 * second empty one.
	 */
	public void openWorkspaceWithNewScript() {
		openOrFocusWorkspace();
		ScriptEditorTab untouched = editorTabbedPane.untouchedUnsavedScript();
		if (untouched != null) {
			editorTabbedPane.setSelectedComponent(untouched);
			untouched.textArea().requestFocusInWindow();
			return;
		}
		newScript(null);
	}

	/** {@code EDIT <script>}/{@code LIB EDIT <script>}: only that Script is opened, and selected in the tree. */
	public void openOrFocusAsset(String relativePath) {
		openOrFocusWorkspace();
		openAsset(relativePath);
	}

	public void offerCreate(String requestedName) {
		int confirm = JOptionPane.showConfirmDialog(this,
				"No script named '" + requestedName + "' was found in the Scripts Library. Create it?", "Create Script", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}
		createNamedScript(requestedName);
	}

	// ------------------------------------------------------------------
	// Actions
	// ------------------------------------------------------------------

	private void openAsset(String relativePath) {
		try {
			ScriptAsset asset = service.open(relativePath);
			session.openOrFocus(asset.assetId(), asset.relativePath());
			editorTabbedPane.openOrFocus(asset);
			refreshMetadataPanel();
			syncTreeSelection();
			updateRunEnabled();
			status("Opened '" + asset.relativePath() + "'.");
		} catch (BroadSQLException ex) {
			log.warn("Could not open '{}': {}", relativePath, ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	/**
	 * New Script (toolbar, File menu, Scripts pane context menu, and {@code EDIT} without a name): a new tab with the
	 * new-Script seed ({@link ScriptLibraryService#newAssetSeedContent}), unnamed and not written anywhere until its
	 * first Save, which proposes {@code folderOrNull}.
	 */
	private void newScript(String folderOrNull) {
		ScriptEditorTab tab = editorTabbedPane.newUnsavedScript(ScriptLibraryService.newAssetSeedContent(), folderOrNull);
		session.openOrFocus(tab.asset().assetId(), null);
		refreshMetadataPanel();
		syncTreeSelection();
		updateRunEnabled();
		tab.textArea().setCaretPosition(tab.textArea().getDocument().getLength());
		tab.textArea().requestFocusInWindow();
		status("New script: type it, then Save to name it" + (folderOrNull != null ? " (in '" + folderOrNull + "')" : "") + ".");
	}

	/** {@code EDIT missing.sql} accepted ("Create it?"): the name is known, so the file is created at once, as before. */
	private void createNamedScript(String name) {
		try {
			ScriptAsset asset = service.create(name.trim(), ScriptLibraryService.newAssetSeedContent());
			session.openOrFocus(asset.assetId(), asset.relativePath());
			editorTabbedPane.openOrFocus(asset);
			reloadTree(List.of(asset.relativePath()));
			refreshMetadataPanel();
			updateRunEnabled();
			status("Created '" + asset.relativePath() + "'.");
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void renameAsset(String relativePath) {
		String newName = JOptionPane.showInputDialog(this, "New name:", relativePath);
		if (newName == null || newName.isBlank() || newName.trim().equals(relativePath)) {
			return;
		}
		try {
			ScriptAsset current = service.open(relativePath);
			ScriptAsset renamed = service.rename(current.assetId(), relativePath, newName.trim(), null);
			session.openOrFocus(renamed.assetId(), renamed.relativePath());
			retargetOpenTabs(List.of(renamed));
			reloadTree(List.of(renamed.relativePath()));
			status("Renamed to '" + renamed.relativePath() + "'.");
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void duplicateAsset(String relativePath) {
		String newName = JOptionPane.showInputDialog(this, "New name for the duplicate:", relativePath);
		if (newName == null || newName.isBlank()) {
			return;
		}
		try {
			ScriptAsset duplicate = service.duplicate(relativePath, newName.trim());
			session.openOrFocus(duplicate.assetId(), duplicate.relativePath());
			editorTabbedPane.openOrFocus(duplicate);
			refreshMetadataPanel();
			reloadTree(null); // the duplicate is now the active tab: the tree follows it
			status("Duplicated to '" + duplicate.relativePath() + "'.");
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void deleteAsset(String relativePath) {
		int confirm = JOptionPane.showConfirmDialog(this,
				"Delete '" + relativePath + "'?\nIt is moved to the Scripts Library archive and stays recoverable (File > Recently Deleted, or LIB RESTORE / LIB UNDO).",
				"Delete", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			ScriptAsset current = service.open(relativePath);
			if (editorTabbedPane.tabForAsset(current.assetId()) != null) {
				editorTabbedPane.closeTab(current.assetId());
				session.closeTab(current.assetId());
			}
			service.delete(relativePath);
			reloadTree(null);
			status("Deleted '" + relativePath + "' (archived).");
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	/**
	 * New Folder (toolbar, File menu, Scripts pane context menu). The destination is always
	 * {@link ScriptLibraryBrowserPanel#targetFolderForNew()}, resolved by the caller from the tree selection; the
	 * dialog names it and asks only for the new folder's name (a name with {@code /} creates nested folders).
	 */
	private void newFolder(String parentFolderOrNull) {
		String where = parentFolderOrNull == null ? "the Scripts Library root" : "'" + parentFolderOrNull + "'";
		String name = JOptionPane.showInputDialog(this, "New folder in " + where + ":", "New Folder", JOptionPane.PLAIN_MESSAGE);
		if (name == null || name.isBlank()) {
			return;
		}
		String path = parentFolderOrNull == null ? name.trim() : parentFolderOrNull + "/" + name.trim();
		try {
			service.createFolder(path);
			reloadTree(List.of(path));
			status("Created folder '" + path + "'.");
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void renameFolder(String folderPath) {
		String newName = JOptionPane.showInputDialog(this, "New folder name:", folderPath);
		if (newName == null || newName.isBlank() || newName.trim().equals(folderPath)) {
			return;
		}
		try {
			FolderMoveOutcome outcome = service.renameFolder(folderPath, newName.trim());
			retargetOpenTabs(outcome.movedScripts());
			reloadTree(List.of(outcome.newFolderKey()));
			status("Renamed folder to '" + outcome.newFolderKey() + "'.");
			warnAboutHistory(outcome.historyWarning());
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	/**
	 * Drag and drop of the selected Scripts and folders onto a folder: one operation
	 * ({@link ScriptLibraryService#moveItems}), validated entirely before anything moves. Every moved Script keeps
	 * its identity and history; an open tab keeps representing it, unsaved edits included, and its next Save
	 * writes to the new path (the tab's asset is retargeted, never reloaded). A refusal or a filesystem failure is
	 * reported; the tree is reloaded from disk either way, so it always shows what is really there.
	 */
	private void moveItems(List<ScriptLibraryBrowserPanel.MovedItem> items, String destinationFolderOrNull) {
		String where = destinationFolderOrNull == null ? "the Scripts Library root" : "'" + destinationFolderOrNull + "'";
		try {
			ScriptLibraryService.MoveOutcome outcome = service.moveItems(
					items.stream().map(i -> new ScriptLibraryService.MoveRequest(i.path(), i.folder())).toList(), destinationFolderOrNull);
			retargetOpenTabs(outcome.movedScripts());
			reloadTree(outcome.newKeys());
			status(outcome.newKeys().size() == 1 ? "Moved '" + items.get(0).path() + "' to '" + outcome.newKeys().get(0) + "'."
					: "Moved " + outcome.newKeys().size() + " items to " + where + ".");
			warnAboutHistory(outcome.historyWarning());
		} catch (BroadSQLException ex) {
			reloadTree(null);
			status("Not moved: " + ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "Nothing was moved: " + ex.getLocalizedMessage(), "Move", JOptionPane.WARNING_MESSAGE);
		}
	}

	private void warnAboutHistory(String historyWarning) {
		if (historyWarning != null) {
			JOptionPane.showMessageDialog(this, historyWarning, "Move", JOptionPane.WARNING_MESSAGE);
		}
	}

	/** Points every open tab among {@code movedScripts} (matched by asset id) at its new path: tab title, session, Metadata path and status bar path all follow; the buffer and dirty state are untouched. */
	private void retargetOpenTabs(List<ScriptAsset> movedScripts) {
		for (ScriptAsset moved : movedScripts) {
			if (editorTabbedPane.retargetOpenTab(moved) != null) {
				ScriptLibrarySession.TabState state = session.tab(moved.assetId());
				if (state != null) {
					state.relativePath = moved.relativePath();
				}
			}
		}
		refreshFileInfo();
	}

	/**
	 * Reloads the tree from disk, then selects {@code select} (the items an action just created or moved) or, with
	 * {@code null}, mirrors the active tab ({@link #syncTreeSelection}).
	 */
	private void reloadTree(List<String> select) {
		browserPanel.reload();
		if (select != null && !select.isEmpty()) {
			browserPanel.selectPaths(select);
		} else {
			syncTreeSelection();
		}
	}

	/**
	 * The tree mirrors the active tab: its Script is selected, its folders expanded and the row scrolled into view;
	 * a new Script with no path yet clears the selection, so no unrelated Script ever looks active. With no tab,
	 * the tree is left as it is. Selecting in the tree opens nothing, so this never loops back.
	 */
	private void syncTreeSelection() {
		browserPanel.mirror(editorTabbedPane.activeTab());
	}

	/** The open tab of the Script at {@code relativePath}, or {@code null}. */
	private ScriptEditorTab openTabForPath(String relativePath) {
		for (String assetId : editorTabbedPane.openAssetIds()) {
			ScriptEditorTab tab = editorTabbedPane.tabForAsset(assetId);
			if (tab != null && relativePath.equals(tab.asset().relativePath())) {
				return tab;
			}
		}
		return null;
	}

	/** Copy Path (Metadata tab, tab and tree context menus, Edit menu): puts the chosen form of {@code relativePath} on the system clipboard. */
	private void copyPath(String relativePath, ScriptPathText.Kind kind) {
		try {
			String text = ScriptPathText.text(kind, relativePath, kind == ScriptPathText.Kind.FULL_PATH ? service.absolutePathOf(relativePath) : null);
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
			status("Copied '" + text + "'.");
		} catch (BroadSQLException | IllegalStateException ex) {
			status("Not copied: " + ex.getLocalizedMessage());
		}
	}

	/**
	 * Send to CLI: places the command running {@code relativePath} ({@code @reports/QR13.sql;}) on the BroadSQL
	 * prompt of the console this Editor was opened from, without running it; the user reviews it and presses Enter
	 * there. The Editor stays open. The console is the one of the interpreter the last {@code EDIT} call carried.
	 */
	private void sendToCli(String relativePath, boolean unsavedChanges) {
		ShellConsole console = executionContext.consoleCommandInterpreter() == null ? null : executionContext.consoleCommandInterpreter().getConsole();
		ScriptPathText.SendResult result = ScriptPathText.sendToCli(relativePath, unsavedChanges, console == null ? null : console::offerCommandInput);
		status(result.message());
	}

	private void deleteFolder(String folderPath) {
		int confirm = JOptionPane.showConfirmDialog(this, "Delete the empty folder '" + folderPath + "'?", "Delete Folder", JOptionPane.YES_NO_OPTION);
		if (confirm != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			service.deleteEmptyFolder(folderPath);
			reloadTree(null);
			status("Deleted folder '" + folderPath + "'.");
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}

	private void saveActive() {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab != null) {
			saveTab(tab);
		}
	}

	private void saveAll() {
		for (String assetId : List.copyOf(editorTabbedPane.openAssetIds())) {
			ScriptEditorTab tab = editorTabbedPane.tabForAsset(assetId);
			if (tab != null && tab.isDirty()) {
				saveTab(tab);
			}
		}
	}

	/**
	 * Save: writes the buffer (a new Script's first Save asks for its name, see {@link #saveNewScript}), then
	 * reloads the Metadata tab from the saved text, so metadata typed directly in the editor shows in the tab
	 * at once. Reloading only reads: it never writes back, so the tab stays clean.
	 *
	 * @return {@code true} if the save succeeded (or there was nothing to do) - {@code false} on
	 *         failure or if the user cancelled, so a caller like {@link #closeTabWithGuard} knows not to proceed.
	 */
	private boolean saveTab(ScriptEditorTab tab) {
		if (tab == metadataBoundTab) {
			// Spec section 3.2: any metadata field the user is still mid-edit in must be committed into
			// the buffer before Save reads it - never left stranded in the form only.
			metadataPanel.commitPendingEdits();
		}
		if (tab.asset().isUnsaved()) {
			return saveNewScript(tab);
		}
		ScriptAsset asset = tab.asset();
		if (tab.isDirty() && !saveTabExternalChangeGuardPasses(tab)) {
			return false;
		}
		try {
			SaveOutcome outcome = service.save(asset.assetId(), asset.relativePath(), tab.currentText(), null,
					MetadataIntegrityContext.fromVault(executionContext.databaseConnectionsVault()));
			ScriptAsset refreshed = service.open(asset.relativePath());
			tab.markSaved(refreshed);
			session.setDirty(asset.assetId(), false);
			reloadMetadataAfterSave(tab);
			if (outcome.status() == SaveOutcome.Status.SAVED_HISTORY_FAILED) {
				JOptionPane.showMessageDialog(this,
						"File saved, but revision history could not be updated: " + outcome.historyFailureMessage(),
						"Save", JOptionPane.WARNING_MESSAGE);
			} else {
				status(outcome.revisionCreated() ? "Saved '" + asset.relativePath() + "' (new revision)." : "Saved '" + asset.relativePath() + "' (no changes).");
			}
			return true;
		} catch (MetadataIntegrityException ex) {
			// Nothing was written and the tab stays dirty. Show the offending metadata and put the cursor in its field.
			reportMetadataIntegrityFailure(tab, ex);
			return false;
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: could not save '" + asset.relativePath() + "': " + ex.getLocalizedMessage());
			return false;
		}
	}

	/**
	 * The first Save of a new Script: asks for its name (a path in the Scripts Library, the folder chosen when it
	 * was created proposed), then creates the file with the buffer's text after the same metadata check as every
	 * Save. The tab becomes that Script (same buffer, now clean) and the tree selects it. Cancelling the name, or
	 * a refused name, leaves the tab unsaved.
	 */
	private boolean saveNewScript(ScriptEditorTab tab) {
		String folder = tab.suggestedFolder();
		String name = JOptionPane.showInputDialog(this, "Save '" + tab.asset().displayName() + "' as (a path in the Scripts Library; "
				+ ScriptLibraryService.DEFAULT_EXTENSION + " is added if you give no extension):", folder != null ? folder + "/" : "");
		if (name == null || name.isBlank()) {
			status("Not saved.");
			return false;
		}
		String unsavedId = tab.asset().assetId();
		try {
			ScriptAsset created = service.create(name.trim(), tab.currentText(), MetadataIntegrityContext.fromVault(executionContext.databaseConnectionsVault()));
			editorTabbedPane.adoptSaved(tab, created);
			session.closeTab(unsavedId);
			session.openOrFocus(created.assetId(), created.relativePath());
			reloadMetadataAfterSave(tab);
			reloadTree(List.of(created.relativePath()));
			updateRunEnabled();
			status("Saved '" + created.relativePath() + "'.");
			return true;
		} catch (MetadataIntegrityException ex) {
			reportMetadataIntegrityFailure(tab, ex);
			return false;
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: could not save '" + name.trim() + "': " + ex.getLocalizedMessage());
			return false;
		}
	}

	/** After a successful Save of {@code tab}: the Metadata tab shows what the saved text declares, and Path/Last modified are current. */
	private void reloadMetadataAfterSave(ScriptEditorTab tab) {
		if (tab == metadataBoundTab) {
			metadataPanel.load(ScriptMetadataHeader.parse(tab.currentText()));
		}
		if (tab == editorTabbedPane.activeTab()) {
			refreshFileInfo();
		}
	}

	private void reportMetadataIntegrityFailure(ScriptEditorTab tab, MetadataIntegrityException ex) {
		editorTabbedPane.setSelectedComponent(tab);
		refreshMetadataPanel();
		inspectorTabs.setSelectedIndex(INSPECTOR_TAB_METADATA);
		status("Not saved: " + ex.issues().get(0).message());
		JOptionPane.showMessageDialog(this, ex.getLocalizedMessage(), "Cannot save '" + tab.asset().displayName() + "'", JOptionPane.ERROR_MESSAGE);
		metadataPanel.focusField(ex.issues().get(0).field());
	}

	/**
	 * Pre-Save external-modification check (spec section 29, wired into Save per the plan's Phase D
	 * scope): a dirty tab about to overwrite a file that changed outside BroadSQL is never silently
	 * allowed to do so - the same {@link ExternalChangeDialog} choice as {@link #checkExternalChangeForTab},
	 * except {@code RELOAD_EXTERNAL} here means discarding the pending save (the buffer now matches disk,
	 * so there is nothing left to save) rather than just refreshing a clean tab.
	 *
	 * @return {@code true} if the caller should proceed with the save.
	 */
	private boolean saveTabExternalChangeGuardPasses(ScriptEditorTab tab) {
		ScriptAsset asset = tab.asset();
		try {
			com.upandcoding.broadsql.controller.shell.scriptlibrary.ExternalChangeStatus changeStatus =
					service.detectExternalChange(asset.relativePath(), asset.lastModifiedMillis());
			if (changeStatus == com.upandcoding.broadsql.controller.shell.scriptlibrary.ExternalChangeStatus.UNCHANGED
					|| changeStatus == com.upandcoding.broadsql.controller.shell.scriptlibrary.ExternalChangeStatus.DELETED_EXTERNALLY) {
				return true;
			}
			ExternalChangeDialog.Decision decision = askExternalChangeDecision(tab);
			if (decision == ExternalChangeDialog.Decision.KEEP_EDITOR) {
				return true;
			}
			if (decision == ExternalChangeDialog.Decision.RELOAD_EXTERNAL) {
				ScriptAsset fresh = service.open(asset.relativePath());
				tab.reloadContent(fresh);
				if (tab == editorTabbedPane.activeTab()) {
					refreshMetadataPanel();
				}
				status("'" + asset.relativePath() + "' reloaded from disk; your unsaved changes were discarded.");
			}
			return false;
		} catch (BroadSQLException ex) {
			log.warn("Could not check '{}' for external changes before saving: {}", asset.relativePath(), ex.getLocalizedMessage());
			return true;
		}
	}

	/**
	 * Called once, by {@code ScriptLibraryWorkspaceHolder.shutdown()}, when BroadSQL itself is
	 * exiting - not a normal window-hide (X), which never reaches this method. Offers Save/Discard for
	 * every dirty tab, then disposes this frame so the JVM can actually terminate: a hidden-but-never-
	 * disposed frame would otherwise keep the Swing event dispatch thread, and therefore the whole
	 * process, alive indefinitely after {@code EXIT} (window X intentionally never disposes - see this
	 * class's own javadoc - so without this shutdown step nothing else in BroadSQL ever would either).
	 *
	 * <p>BroadSQL's own shutdown sequence ({@code BroadSQL.main}, after
	 * {@code CommandInterpreter.run()} returns) has no mechanism to abort/cancel an in-progress exit,
	 * so unlike every other place {@link UnsavedChangesDialog} is used in this window, a Cancel choice
	 * here cannot actually keep BroadSQL running - it can only mean "leave this buffer unsaved." This
	 * is a real, documented limitation (see
	 * {@code docs/plans/SPRINT_0917-01_IMPLEMENTATION_PLAN.md}), not a silent data-loss bug: Save still
	 * genuinely protects the content if chosen.
	 */
	public void shutdownProtectDirtyBuffersThenDispose() {
		tabCloser.protectDirtyTabsAtExit(); // only tabs that are really dirty; tabs closed earlier are gone
		dispose();
	}

	/**
	 * The window's X button and File > Close Window. Every tab goes through the normal close guard (a clean
	 * tab closes silently, a dirty one asks Save/Discard/Cancel). Only when no tab remains is the window hidden
	 * and the workspace marked closed, so a later application exit finds nothing to protect again; a Cancel on
	 * any tab keeps the window open. Reopening with {@code EDIT}/{@code LIB EDIT} reuses the same window.
	 */
	private void closeWorkspaceWindow() {
		if (tabCloser.closeAll()) {
			session.markClosed();
			setVisible(false);
		}
	}

	private void closeActiveTab() {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab != null) {
			closeTabWithGuard(tab);
		}
	}

	/** The per-tab "x" close control's callback (see {@code initComponents}) - closes whichever tab was clicked, not necessarily the active one. */
	private void closeTabAtIndex(int index) {
		if (index < 0 || index >= editorTabbedPane.getTabCount()) {
			return;
		}
		if (editorTabbedPane.getComponentAt(index) instanceof ScriptEditorTab tab) {
			closeTabWithGuard(tab);
		}
	}

	/** {@code File > Close All} (spec section 9.3) - each tab follows the normal dirty guard; a Cancel on one tab simply leaves it open and moves on to the next. */
	private void closeAllTabs() {
		tabCloser.closeAll();
	}

	/** The dirty-tab guard: Save/Discard/Cancel, same convention as every other BroadSQL Swing window (see {@link UnsavedChangesDialog}). A failed Save leaves the tab open and dirty. */
	private void closeTabWithGuard(ScriptEditorTab tab) {
		tabCloser.close(tab);
	}

	private void undoActive() {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab != null && tab.canUndo()) {
			tab.undo();
		}
	}

	private void redoActive() {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab != null && tab.canRedo()) {
			tab.redo();
		}
	}

	private void findInActive() {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab == null) {
			return;
		}
		String needle = JOptionPane.showInputDialog(this, "Find:");
		if (needle == null || needle.isEmpty()) {
			return;
		}
		SearchContext context = new SearchContext();
		context.setSearchFor(needle);
		boolean found = SearchEngine.find(tab.textArea(), context).wasFound();
		status(found ? "Found '" + needle + "'." : "'" + needle + "' not found.");
	}

	private void replaceInActive() {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab == null) {
			return;
		}
		String needle = JOptionPane.showInputDialog(this, "Find:");
		if (needle == null || needle.isEmpty()) {
			return;
		}
		String replacement = JOptionPane.showInputDialog(this, "Replace with:");
		if (replacement == null) {
			return;
		}
		SearchContext context = new SearchContext();
		context.setSearchFor(needle);
		context.setReplaceWith(replacement);
		int count = SearchEngine.replaceAll(tab.textArea(), context).getCount();
		status(count + " occurrence(s) replaced.");
	}

	/**
	 * Reloads the Metadata tab from the active tab's <b>current editor buffer</b> (not the last-saved
	 * asset) - metadata synchronization is explicit-per-field, not silently automatic (spec section 19):
	 * this runs on every tab switch, and after Save. Before switching away, any pending edit still sitting in a
	 * focused metadata field is committed onto the tab that was bound until now ({@link #metadataBoundTab}) -
	 * there is no separate "Apply to Script" step: editing a field patches the buffer directly, on
	 * focus-loss/Enter, via {@link #applyMetadataChangeToBoundTab}.
	 */
	private void refreshMetadataPanel() {
		if (metadataBoundTab != null) {
			metadataPanel.commitPendingEdits();
		}
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		metadataBoundTab = tab;
		metadataPanel.load(tab == null ? ScriptMetadataHeader.parse("") : ScriptMetadataHeader.parse(tab.currentText()));
		refreshFileInfo();
	}

	/** The Metadata tab's read-only Path and Last modified, from the active tab's asset (its current path and last known file time); empty for a new unsaved Script. */
	private void refreshFileInfo() {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab == null || tab.asset().isUnsaved()) {
			metadataPanel.showFile(null, null, null);
			return;
		}
		ScriptAsset asset = tab.asset();
		String fullPath;
		try {
			fullPath = service.absolutePathOf(asset.relativePath()).toString();
		} catch (BroadSQLException ex) {
			fullPath = null;
		}
		metadataPanel.showFile(asset.relativePath(), fullPath, asset.lastModifiedMillis());
	}

	/**
	 * {@link MetadataPanel}'s commit callback (focus-lost/Enter on any field, spec section 3.2): applies the
	 * edited fields to the bound tab's <b>current</b> text with {@link MetadataPanel.Change#applyTo}, which changes
	 * only those directives, and replaces only the characters that differ in the document, so the caret, the rest
	 * of the text and the undo history stay as they are. The tab becomes dirty through normal change tracking.
	 * Never saves - {@code File > Save} is the one persistence action (spec section 3/10).
	 */
	private void applyMetadataChangeToBoundTab(MetadataPanel.Change change) {
		if (metadataBoundTab == null) {
			return;
		}
		replaceChangedRegion(metadataBoundTab.textArea(), change.applyTo(metadataBoundTab.currentText()));
		status("Metadata updated - unsaved.");
	}

	/** Replaces, in {@code area}, only the span between the common prefix and the common suffix of its text and {@code updated}. */
	static void replaceChangedRegion(javax.swing.text.JTextComponent area, String updated) {
		String current = area.getText();
		if (current.equals(updated)) {
			return;
		}
		int prefix = 0;
		int max = Math.min(current.length(), updated.length());
		while (prefix < max && current.charAt(prefix) == updated.charAt(prefix)) {
			prefix++;
		}
		int suffix = 0;
		while (suffix < max - prefix && current.charAt(current.length() - 1 - suffix) == updated.charAt(updated.length() - 1 - suffix)) {
			suffix++;
		}
		try {
			javax.swing.text.Document document = area.getDocument();
			document.remove(prefix, current.length() - suffix - prefix);
			document.insertString(prefix, updated.substring(prefix, updated.length() - suffix), null);
		} catch (javax.swing.text.BadLocationException e) {
			area.setText(updated); // cannot happen: offsets come from the document's own text
		}
	}

	/** {@code Format Document}/{@code Format Selection} (spec section 6.13/6.14) - modifies the buffer, never saves automatically; the tab becomes dirty like any other edit. */
	private void formatActive(boolean selectionOnly) {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab == null) {
			return;
		}
		if (selectionOnly) {
			String selected = tab.textArea().getSelectedText();
			if (selected == null || selected.isBlank()) {
				status("Nothing selected to format.");
				return;
			}
			FormatOutcome selectionOutcome = formatterService.formatSelection(selected);
			if (!selectionOutcome.isSupported()) {
				JOptionPane.showMessageDialog(this, selectionOutcome.reason(), "Format Selection", JOptionPane.WARNING_MESSAGE);
				return;
			}
			tab.textArea().replaceSelection(selectionOutcome.formattedContent());
			if (!selectionOutcome.notes().isEmpty()) {
				JOptionPane.showMessageDialog(this, String.join("\n", selectionOutcome.notes()), "Format Selection", JOptionPane.WARNING_MESSAGE);
			}
			status("Selection formatted.");
			return;
		}
		FormatOutcome outcome = formatterService.format(tab.currentText());
		if (!outcome.isSupported()) {
			JOptionPane.showMessageDialog(this, outcome.reason(), "Format Document", JOptionPane.WARNING_MESSAGE);
			return;
		}
		tab.textArea().setText(outcome.formattedContent());
		if (!outcome.notes().isEmpty()) {
			JOptionPane.showMessageDialog(this, String.join("\n", outcome.notes()), "Format Document", JOptionPane.WARNING_MESSAGE);
		}
		status("Formatted.");
	}

	/** Run's one enabled state, shared by the toolbar button and the menu item: see {@link EditorActions#runEnabled}. */
	private void updateRunEnabled() {
		if (runAction != null) {
			runAction.setEnabled(EditorActions.runEnabled(editorTabbedPane == null ? null : editorTabbedPane.activeTab(),
					executionContext.hasActiveConnection(), running));
		}
	}

	/**
	 * {@code Run} (spec section 7) - executes the active, saved asset using
	 * {@link ScriptRunCoordinator}, which reuses BroadSQL's existing execution infrastructure
	 * unmodified, on the current BroadSQL connection. Every execution path reads from disk (confirmed during
	 * Phase C's investigation - see {@link ScriptRunCoordinator}'s own javadoc), so a dirty tab, or a new Script
	 * never saved, is never run as-is: the choice offered is {@code [Save and Run] [Cancel]}, never a silent run
	 * of the stale on-disk version, and never an in-memory "run current editor content" path.
	 * Runs on a background thread - never blocks the EDT - and marshals the result back via
	 * {@link SwingUtilities#invokeLater} from a {@code finally}, so Run always becomes available again and the
	 * status bar never stays on "Running...", whatever happened.
	 */
	private void runActive() {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab == null || running) {
			return;
		}
		// Spec sections 6.1/13 (SPRINT 0917-01 corrective acceptance pass, defect 5): Output is selected
		// immediately, before any dialog/background work, so the user is never left looking at an unrelated
		// tab wondering whether Run did anything at all.
		inspectorTabs.setSelectedIndex(INSPECTOR_TAB_OUTPUT);
		boolean needsSave = tab.isDirty() || tab.asset().isUnsaved();
		if (!needsSave) {
			// Run always reads from disk regardless (see this method's own javadoc), so a clean tab is
			// never at risk of running stale content - this only keeps the editor's own display in sync
			// with an external edit. A dirty tab's own external-change conflict is handled once, inside
			// saveTab() below, via the [Save and Run] path - not duplicated here.
			checkExternalChangeForTab(tab);
		}
		if (!executionContext.hasActiveConnection()) {
			JOptionPane.showMessageDialog(this, "Run requires an active database connection.", "Run", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (needsSave) {
			Object[] options = { "Save and Run", "Cancel" };
			String question = tab.asset().isUnsaved() ? "This script has not been saved yet." : "This script contains unsaved changes.";
			int choice = JOptionPane.showOptionDialog(this, question, "Run",
					JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
			if (choice != 0) {
				return;
			}
			if (!saveTab(tab)) {
				return;
			}
		}

		ScriptAsset asset = tab.asset();
		// SPRINT 0110A: the dialog shows one field per name of the Script's -- @params: lines; no @params, no dialog
		String argumentText = "";
		List<String> parameterNames = runParameterNames(tab.currentText());
		if (!parameterNames.isEmpty()) {
			RunParametersDialog dialog = new RunParametersDialog(this, parameterNames);
			dialog.setLocationRelativeTo(this);
			dialog.setVisible(true);
			if (!dialog.isConfirmed()) {
				status("Run cancelled.");
				return;
			}
			argumentText = dialog.argumentText();
		}

		status("Running '" + asset.relativePath() + "'...");
		executionOutputArea.setText("Running " + asset.relativePath() + "...");
		running = true;
		updateRunEnabled();
		ScriptRunContext contextSnapshot = executionContext;
		String finalArguments = argumentText;
		Thread worker = new Thread(() -> {
			RunOutcome outcome = null;
			try {
				outcome = runCoordinator.run(asset.relativePath(), contextSnapshot, finalArguments);
			} catch (Throwable unexpected) {
				log.error("BroadSQL Editor Run of '{}' failed unexpectedly", asset.relativePath(), unexpected);
				outcome = RunOutcome.executed("ERROR: unexpected failure: " + unexpected, new BroadSQLException(String.valueOf(unexpected), unexpected));
			} finally {
				RunOutcome result = outcome;
				SwingUtilities.invokeLater(() -> {
					running = false;
					updateRunEnabled();
					showRunOutcome(result);
				});
			}
		}, "ScriptLibrary-Run");
		worker.start();
	}

	private void showRunOutcome(RunOutcome outcome) {
		if (outcome == null) {
			status("Execution failed.");
			return;
		}
		if (outcome.status() == RunOutcome.Status.NO_ACTIVE_CONNECTION) {
			JOptionPane.showMessageDialog(this, "Run requires an active database connection.", "Run", JOptionPane.WARNING_MESSAGE);
			status("Run cancelled: no active connection.");
			return;
		}
		executionOutputArea.setText(outcome.capturedOutput());
		executionOutputArea.setCaretPosition(0);
		status(statusText(outcome));
	}

	/**
	 * SPRINT 0110A: the names the {@link RunParametersDialog} asks for: those declared by the Script's
	 * {@code -- @params:} lines, in declaration order, duplicates removed (spec section 17.7). Empty means no dialog.
	 */
	static List<String> runParameterNames(String scriptText) {
		return EntryMetadata.parse(scriptText).getParams();
	}

	/** SPRINT 0110A: the status bar text of a finished Run: the Script status when the run produced one. */
	static String statusText(RunOutcome outcome) {
		if (outcome.scriptStatus() != null) {
			switch (outcome.scriptStatus()) {
				case SUCCESS:
					return "Execution completed: SUCCESS.";
				case COMPLETED_WITH_ERRORS:
					return "Execution completed with errors: COMPLETED_WITH_ERRORS, see Output.";
				case CANCELLED:
					return "Execution cancelled: CANCELLED, see Output.";
				default:
					return "Execution failed: FAILED, see Output.";
			}
		}
		return outcome.error() != null ? "Execution failed: see Output." : outcome.hasErrors() ? "Execution completed with errors: see Output." : "Execution completed.";
	}

	/** {@code Recently Deleted} (spec section 30, Phase F) - a minimum-viable recovery screen for any deleted asset, not just the active tab's. */
	private void showRecentlyDeleted() {
		RecentlyDeletedDialog dialog = new RecentlyDeletedDialog(this, service);
		dialog.setRestoredListener(() -> reloadTree(null));
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	/** {@code History} (spec section 9) - a non-modal window for the active tab's asset, so it can stay open while the user keeps working (and, once Compare exists in Phase E, while comparing). */
	private void showHistoryForActive() {
		ScriptEditorTab tab = editorTabbedPane.activeTab();
		if (tab == null || !requireSaved(tab)) {
			return;
		}
		ScriptAsset asset = tab.asset();
		HistoryDialog dialog = new HistoryDialog(this, service, asset.assetId(), asset.displayName());
		dialog.setRestoreListener(restored -> {
			tab.reloadContent(restored);
			refreshMetadataPanel();
			reloadTree(null);
			status("'" + restored.relativePath() + "' restored.");
		});
		dialog.setLocationRelativeTo(this);
		dialog.setVisible(true);
	}

	/**
	 * {@code Refresh} (the Scripts pane's context menu; spec section 12) - for changes made outside BroadSQL:
	 * rescans the library tree and checks every open tab for external modification. Never discards a dirty
	 * buffer, and never touches a new unsaved Script.
	 */
	private void refreshAll() {
		reloadTree(null);
		checkExternalChangesForAllOpenTabs();
		status("Refreshed.");
	}

	private void checkExternalChangesForAllOpenTabs() {
		for (String assetId : List.copyOf(editorTabbedPane.openAssetIds())) {
			ScriptEditorTab tab = editorTabbedPane.tabForAsset(assetId);
			if (tab != null) {
				checkExternalChangeForTab(tab);
			}
		}
	}

	/**
	 * External modification detection (spec section 29). A clean tab whose file changed outside
	 * BroadSQL is reloaded silently. A dirty tab is never silently overwritten - the user is asked via
	 * {@link ExternalChangeDialog}. A file deleted outside BroadSQL cannot be "reloaded," so that case
	 * is reported informationally rather than offered as a choice. A new unsaved Script has no file to check.
	 */
	private void checkExternalChangeForTab(ScriptEditorTab tab) {
		ScriptAsset asset = tab.asset();
		if (asset.isUnsaved()) {
			return;
		}
		try {
			com.upandcoding.broadsql.controller.shell.scriptlibrary.ExternalChangeStatus changeStatus =
					service.detectExternalChange(asset.relativePath(), asset.lastModifiedMillis());
			if (changeStatus == com.upandcoding.broadsql.controller.shell.scriptlibrary.ExternalChangeStatus.UNCHANGED) {
				return;
			}
			if (changeStatus == com.upandcoding.broadsql.controller.shell.scriptlibrary.ExternalChangeStatus.DELETED_EXTERNALLY) {
				JOptionPane.showMessageDialog(this,
						"'" + asset.relativePath() + "' was deleted outside BroadSQL. Save to recreate it, or close this tab.",
						"External Change Detected", JOptionPane.WARNING_MESSAGE);
				return;
			}
			if (!tab.isDirty()) {
				ScriptAsset fresh = service.open(asset.relativePath());
				tab.reloadContent(fresh);
				if (tab == editorTabbedPane.activeTab()) {
					refreshMetadataPanel();
				}
				status("'" + asset.relativePath() + "' was reloaded (changed outside BroadSQL).");
				return;
			}
			ExternalChangeDialog.Decision decision = askExternalChangeDecision(tab);
			if (decision == ExternalChangeDialog.Decision.RELOAD_EXTERNAL) {
				ScriptAsset fresh = service.open(asset.relativePath());
				tab.reloadContent(fresh);
				if (tab == editorTabbedPane.activeTab()) {
					refreshMetadataPanel();
				}
			}
			// KEEP_EDITOR/CANCEL: do nothing - the editor buffer is left exactly as it was.
		} catch (BroadSQLException ex) {
			log.warn("Could not check '{}' for external changes: {}", asset.relativePath(), ex.getLocalizedMessage());
		}
	}

	/**
	 * Asks {@link ExternalChangeDialog}, looping on {@code COMPARE}: shows the on-disk external content
	 * against the editor's current buffer in a {@link CompareDialog}, then re-asks the same question -
	 * {@code Compare} is never itself a terminal choice, only a way to see the difference before
	 * deciding.
	 */
	private ExternalChangeDialog.Decision askExternalChangeDecision(ScriptEditorTab tab) throws BroadSQLException {
		ScriptAsset asset = tab.asset();
		while (true) {
			ExternalChangeDialog.Decision decision = ExternalChangeDialog.ask(this, asset.displayName());
			if (decision != ExternalChangeDialog.Decision.COMPARE) {
				return decision;
			}
			ScriptAsset onDisk = service.open(asset.relativePath());
			CompareDialog dialog = new CompareDialog(this, "Compare: " + asset.displayName(), "On Disk", "Editor (unsaved)");
			dialog.showComparison(onDisk.content(), tab.currentText(), onDisk.metadataHeader(),
					com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptMetadataHeader.parse(tab.currentText()),
					asset.relativePath(), asset.relativePath());
			dialog.setLocationRelativeTo(this);
			// Modal here specifically (CompareDialog defaults to non-modal for its other use, from
			// HistoryDialog) - this loop must block until the user closes the comparison before
			// re-asking the same question underneath it.
			dialog.setModal(true);
			dialog.setVisible(true);
		}
	}

	private void status(String message) {
		if (statusLabel != null) {
			statusLabel.setText(message);
		}
	}

	/** Exposed for the session-scoped holder's shutdown check - see {@code ScriptLibraryWorkspaceHolder}. */
	public ScriptLibrarySession session() {
		return session;
	}

	/** Exposed for tests/the holder - never used to bypass the normal open/focus entry points above. */
	ScriptEditorTabbedPane editorTabbedPane() {
		return editorTabbedPane;
	}
}
