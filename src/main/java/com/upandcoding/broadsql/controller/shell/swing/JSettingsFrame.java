package com.upandcoding.broadsql.controller.shell.swing;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;
import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.ConsoleSettings;
import com.upandcoding.broadsql.controller.shell.commands.CommandInterpreter;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.controller.shell.output.ConsoleLogger;
import com.upandcoding.broadsql.controller.shell.output.ConsolePrinter;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * The BroadSQL Settings window ({@code CONFIG}) - one tab per entity (Connections, Database Groups,
 * Environments), sharing a single File/Edit/View menu bar dispatched to whichever tab is currently
 * selected.
 *
 * <p>SPRINT 0911D reworked this screen's navigation and lifecycle model end to end (see
 * {@code docs/TECHNICAL_CHANGE.md} for the full rationale):
 * <ul>
 * <li>Tabs are now the only entity navigation - the old per-entity "Connections"/"Database
 * Groups"/"Environments" menus (which duplicated the tabs) are gone, replaced by one shared
 * File/Edit/View menu bar whose actions and enablement follow whichever tab is selected.</li>
 * <li>The Connections list is a sortable, filterable {@link JTable} (Connection / Database Group /
 * Environment / Status columns) supporting multi-selection and bulk Deactivate/Reactivate/Delete
 * permanently, instead of the old single-selection {@code JList} of bare IDs.</li>
 * <li>Active/Inactive/All are one consistent {@link SettingsViewMode} across all three tabs, via a
 * visible "Show:" control on each, not menu-only.</li>
 * <li>Save validation is fully separated from Deactivate: {@link #saveConnectionDetails()} is the
 * only path that runs {@code assertGroupEnvironmentPairAvailable} et al. (via
 * {@link DatabaseDefinitionsVault#saveDatabaseDefinition}), while
 * {@link #deactivateSelectedConnections()} calls
 * {@link DatabaseDefinitionsVault#softDeleteDatabaseDefinitions} directly - which runs no validation
 * at all. Before this sprint, "Delete connection" reused {@code saveConnectionDetails}, so an
 * obsolete connection with an already-conflicting (Database Group, Environment) pair could not be
 * deactivated without first editing it into a valid state - exactly backwards, since it is precisely
 * the obsolete/conflicting connection a user needs to remove.</li>
 * <li>A failed Save never reloads, reselects, or resets the form - see
 * {@link #saveConnectionDetails()}'s {@code catch} block. Before this sprint, any failed
 * {@code saveConnectionDetails} call still ran {@code reloadConnections()} unconditionally at the end
 * of the method, so a rejected duplicate-(Group, Environment) save silently jumped the selection back
 * to the first connection ({@code $CDF}) and discarded every field the user had just entered.</li>
 * </ul>
 */
public class JSettingsFrame extends javax.swing.JFrame {

	private static final Logger log = LoggerFactory.getLogger(JSettingsFrame.class);

	protected ConsoleSettings consoleSettings;
	protected ConsoleLogger consoleLogger;
	protected ShellConsole cmdLineConsole;
	protected ConsolePrinter consoleUtils;
	protected CommandInterpreter consoleManager;
	DatabaseDefinitionsVault databaseConnectionsCollection;

	private DatabaseConnection connection;

	public ConsoleSettings getConsoleSettings() {
		return consoleSettings;
	}

	public void setConsoleSettings(ConsoleSettings consoleSettings) {
		this.consoleSettings = consoleSettings;
	}

	public ConsoleLogger getConsoleLogger() {
		return consoleLogger;
	}

	public void setConsoleLogger(ConsoleLogger consoleLogger) {
		this.consoleLogger = consoleLogger;
	}

	public ShellConsole getCmdLineConsole() {
		return cmdLineConsole;
	}

	public void setCmdLineConsole(ShellConsole cmdLineConsole) {
		this.cmdLineConsole = cmdLineConsole;
	}

	public ConsolePrinter getConsoleUtils() {
		return consoleUtils;
	}

	public void setConsoleUtils(ConsolePrinter consoleUtils) {
		this.consoleUtils = consoleUtils;
	}

	public CommandInterpreter getConsoleManager() {
		return consoleManager;
	}

	public void setConsoleManager(CommandInterpreter consoleManager) {
		this.consoleManager = consoleManager;
	}

	public DatabaseDefinitionsVault getDatabaseConnectionsCollection() {
		return databaseConnectionsCollection;
	}

	public void setDatabaseConnectionsCollection(DatabaseDefinitionsVault databaseConnectionsCollection) {
		this.databaseConnectionsCollection = databaseConnectionsCollection;
	}

	public DatabaseConnection getConnection() {
		return connection;
	}

	public void setConnection(DatabaseConnection connection) {
		this.connection = connection;
	}

	private static final String TYPE_DEFAULT = "-- Select type";

	/**
	 * Presentation-only sentinel shown in the Database Group combo when a Connection has none (a
	 * standalone connection, per docs/CONNECTION_MODEL.md) - never persisted. {@link #getConnectionFromInput()}
	 * translates a selection of this label back to a blank/{@code null} Database Group before saving;
	 * {@link #populateConnectionForm} translates a blank/{@code null} Database Group back to this label
	 * when displaying an existing connection.
	 */
	static final String NO_GROUP_LABEL = "<No Database Group>";

	private enum SettingsTab {
		CONNECTIONS, GROUPS, ENVIRONMENTS
	}

	/** Tracks per-selection state for the Connections table, computed once per refresh and reused by both the button bar and the Edit menu. */
	private static final class ConnectionSelectionState {
		int count;
		boolean includesSystemConnection;
		boolean allActive;
		boolean allInactive;
	}

	private SettingsViewMode connectionsViewMode = SettingsViewMode.ACTIVE;

	/** Backing data for {@code jTableConnections} - one row per connection currently in view, in display order. */
	private final List<DatabaseDefinition> connectionsTableRows = new ArrayList<>();

	/** The ID of the connection currently loaded into the form, or {@code null} for a new/duplicated, unsaved draft. */
	private String loadedConnectionId;

	/** Snapshot of the form's field values as last loaded/blanked/saved - drives the unsaved-changes guard on window close (requirement 17). */
	private String[] connectionFormSnapshot;

	private ConnectionsTableModel connectionsTableModel;
	private TableRowSorter<ConnectionsTableModel> connectionsRowSorter;

	public JSettingsFrame() {
	}

	private void initListValues() throws BroadSQLException {
		TreeSet<String> types = databaseConnectionsCollection.getDbTypes();
		DefaultComboBoxModel boxModel = new DefaultComboBoxModel();
		if (types != null && !types.isEmpty()) {
			for (String s : types) {
				boxModel.addElement(s);
			}//for
			jConnTypeList.setModel(boxModel);
		}
		jConnTypeList.setRenderer(new TypeListCellRenderer(databaseConnectionsCollection.getUnavailableTypes()));

		TreeSet<String> groups = databaseConnectionsCollection.getGroups();
		DefaultComboBoxModel boxModelInst = new DefaultComboBoxModel();
		boxModelInst.addElement(NO_GROUP_LABEL);
		if (groups != null) {
			for (String s : groups) {
				boxModelInst.addElement(s);
			}//for
		}
		jConnGroupList.setModel(boxModelInst);

		TreeSet<String> environments = databaseConnectionsCollection.getEnvironments();
		DefaultComboBoxModel boxModelEnv = new DefaultComboBoxModel();
		if (environments != null && !environments.isEmpty()) {
			for (String s : environments) {
				boxModelEnv.addElement(s);
			}//for
			jConnEnvironmentList.setModel(boxModelEnv);
		}
	}//initListValues

	public void initApp() throws BroadSQLException {
		try {
			// Modern, BroadSQL-accented Look & Feel (must be set before any component is created) -
			// centralized in BroadSqlLookAndFeel rather than called ad hoc here, per the GUI-polish
			// follow-up's requirement 12.
			BroadSqlLookAndFeel.installOnce();

			initComponents();

			// BroadSQL branding instead of the default Java icon (SPRINT 0911D, requirement 22) -
			// title bar, taskbar and Alt-Tab all read this same Image from a single setIconImage() call.
			java.awt.Image icon = AppIcon.load();
			if (icon != null) {
				setIconImage(icon);
			}

			initListValues();
			jUserScriptsPanel.setVault(databaseConnectionsCollection);
			jDatabaseGroupsPanel.setOnChange(() -> {
				try {
					refreshGroupsCombo();
				} catch (BroadSQLException ex) {
					log.error(ex.getLocalizedMessage());
				}
			});
			jDatabaseGroupsPanel.setOnSelectionChanged(this::refreshMenuState);
			jDatabaseGroupsPanel.setVault(databaseConnectionsCollection);
			jEnvironmentsPanel.setOnChange(() -> {
				try {
					refreshEnvironmentsCombo();
				} catch (BroadSQLException ex) {
					log.error(ex.getLocalizedMessage());
				}
			});
			jEnvironmentsPanel.setOnSelectionChanged(this::refreshMenuState);
			jEnvironmentsPanel.setVault(databaseConnectionsCollection);

			applyConnectionsViewMode(SettingsViewMode.ACTIVE);
			rebuildConnectionsTableRows();
			blankConnectionForm();
			refreshConnectionActionState();
			refreshMenuState();
		} catch (Exception se) {
			throw new BroadSQLException(se);
		}
	}

	// ==================================================================================
	// Component construction (hand-built - this class is no longer NetBeans-form-generated
	// throughout; SPRINT 0911D replaced the GroupLayout connection form and the JList-based
	// connections list with GridBagLayout/JTable, matching the pattern already used by
	// JDatabaseGroupsPanel/JEnvironmentsPanel).
	// ==================================================================================

	@SuppressWarnings("unchecked")
	private void initComponents() {
		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		setTitle(SpringPropertiesConfig.APP_TITLE_CONFIG);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				attemptClose();
			}
		});

		jMainTabbedPane = new JTabbedPane();
		jDatabaseGroupsPanel = new JDatabaseGroupsPanel();
		jEnvironmentsPanel = new JEnvironmentsPanel();

		jMainTabbedPane.addTab("Connections", buildConnectionsTab());
		jMainTabbedPane.addTab("Database Groups", jDatabaseGroupsPanel);
		jMainTabbedPane.addTab("Environments", jEnvironmentsPanel);
		jMainTabbedPane.addChangeListener(e -> refreshMenuState());

		buildMenuBar();

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(jMainTabbedPane, BorderLayout.CENTER);

		pack();
	}// initComponents

	/**
	 * Root layout: {@link SettingsFilterBar} spans the full tab width in {@code NORTH} - outside and
	 * above the {@code JSplitPane} - with the table/form content padded and centered below it. Matches
	 * {@link JDatabaseGroupsPanel}/{@link JEnvironmentsPanel}'s structure exactly (GUI-polish corrective
	 * pass, requirement 7: "all three tabs should follow: [section/filter header across full width] /
	 * [left master view] | [right detail/editor]").
	 */
	private JPanel buildConnectionsTab() {
		JPanel tab = new JPanel(new BorderLayout());

		jConnectionsFilterBar = new SettingsFilterBar("Active connections", true);
		jConnectionsFilterBar.setOnViewChange(this::safelySwitchConnectionsView);
		jConnectionsFilterBar.setOnSearchChange(this::applyConnectionsSearchFilter);
		tab.add(jConnectionsFilterBar, BorderLayout.NORTH);

		JPanel content = new JPanel(new BorderLayout());
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildConnectionsTable(), buildConnectionDetailContainer());
		splitPane.setDividerLocation(420);
		content.add(splitPane, BorderLayout.CENTER);
		tab.add(content, BorderLayout.CENTER);

		// The connection action buttons live inside buildConnectionFormPanel(), directly below the
		// form fields - not anchored to the bottom of this tab/the window (GUI-polish follow-up,
		// requirement 1: on a tall window that used to leave a large disconnected empty gap between
		// the form and its own actions).
		return tab;
	}

	private JScrollPane buildConnectionsTable() {
		connectionsTableModel = new ConnectionsTableModel();
		jTableConnections = new JTable(connectionsTableModel);
		jTableConnections.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		jTableConnections.setRowHeight(26);
		jTableConnections.setShowVerticalLines(false);
		jTableConnections.setIntercellSpacing(new Dimension(0, 1));
		jTableConnections.setFillsViewportHeight(true);
		jTableConnections.setAutoCreateRowSorter(true);
		connectionsRowSorter = (TableRowSorter<ConnectionsTableModel>) jTableConnections.getRowSorter();
		jTableConnections.setDefaultRenderer(Object.class, new ConnectionsRowRenderer());
		jTableConnections.getSelectionModel().addListSelectionListener(evt -> {
			if (!evt.getValueIsAdjusting()) {
				onConnectionsSelectionChanged();
			}
		});
		JScrollPane scroll = new JScrollPane(jTableConnections);
		scroll.setMinimumSize(new Dimension(300, 100));
		scroll.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));
		return scroll;
	}

	private JPanel buildConnectionDetailContainer() {
		jTabbedPaneConnection = new JTabbedPane();
		jTabbedPaneConnection.addTab("Connection", buildConnectionFormPanel());
		jUserScriptsPanel = new JUserScriptsPanel();
		jTabbedPaneConnection.addTab("Login Scripts", jUserScriptsPanel);

		jMultiSelectionLabel = new JLabel("", SwingConstants.CENTER);
		jMultiSelectionLabel.setFont(jMultiSelectionLabel.getFont().deriveFont(Font.PLAIN, 14f));
		JPanel multiPanel = new JPanel(new BorderLayout());
		multiPanel.add(jMultiSelectionLabel, BorderLayout.CENTER);

		jConnectionDetailCardLayout = new CardLayout();
		jConnectionDetailContainer = new JPanel(jConnectionDetailCardLayout);
		jConnectionDetailContainer.add(jTabbedPaneConnection, "single");
		jConnectionDetailContainer.add(multiPanel, "multi");
		return jConnectionDetailContainer;
	}

	private JPanel buildConnectionFormPanel() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(5, 6, 5, 6);
		c.fill = GridBagConstraints.HORIZONTAL;

		jConnId = new JTextField(20);
		jConnName = new JTextField(30);
		jConnUrl = new JTextField(30);
		jConnTypeList = new javax.swing.JComboBox();
		jConnTypeList.addItemListener(evt -> jConnTypeListItemStateChanged(evt));
		jConnDriver = new JTextField(30);
		jConnDriver.setEditable(false);
		jConnUserName = new JTextField(30);
		jConnPassword = new JPasswordField(20);
		jConnPasswordRetype = new JPasswordField(20);
		jConnGroupList = new javax.swing.JComboBox();
		jConnEnvironmentList = new javax.swing.JComboBox();
		jConnComment = new JTextArea(3, 30);
		JScrollPane commentScroll = new JScrollPane(jConnComment);

		int row = 0;
		row = addFormRow(panel, c, row, "ID", jConnId);
		row = addFormRow(panel, c, row, "Name", jConnName);
		row = addFormRow(panel, c, row, "URL", jConnUrl);
		row = addFormRow(panel, c, row, "Type", jConnTypeList);
		row = addFormRow(panel, c, row, "Driver", jConnDriver);
		row = addFormRow(panel, c, row, "User name", jConnUserName);
		row = addFormRow(panel, c, row, "Password", jConnPassword);
		row = addFormRow(panel, c, row, "Repeat password", jConnPasswordRetype);
		row = addFormRow(panel, c, row, "Database Group", jConnGroupList);
		row = addFormRow(panel, c, row, "Environment", jConnEnvironmentList);

		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		c.anchor = GridBagConstraints.NORTHWEST;
		panel.add(new JLabel("Comment"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.anchor = GridBagConstraints.WEST;
		panel.add(commentScroll, c);
		row++;

		// Action buttons live directly below the form they operate on (GUI-polish follow-up,
		// requirement 1) - never anchored to the bottom of the window/tab, since this whole panel
		// scrolls with the form rather than being pinned to a BorderLayout.SOUTH region.
		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 2;
		c.weightx = 1;
		c.weighty = 0;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(14, 6, 4, 6);
		panel.add(buildConnectionActionButtonsRow1(), c);
		row++;

		c.gridy = row;
		c.insets = new Insets(6, 6, 5, 6);
		panel.add(buildConnectionActionButtonsRow2(), c);
		row++;

		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 2;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		panel.add(new JPanel(), c);

		return panel;
	}

	/**
	 * "creation/testing" (New, Duplicate, Test) followed by "persistence/lifecycle" (Save, Deactivate,
	 * Reactivate) on one row, per the GUI-polish follow-up's mockup - the two conceptual groups are set
	 * off with extra horizontal gap plus a thin vertical separator, not a second panel/border, so they
	 * read as one toolbar. Short, contextual labels ("New"/"Test", not "New connection"/"Test
	 * connection" - requirement 2): the user is already inside the Connections tab/editor, so repeating
	 * "connection" is noise.
	 */
	private JPanel buildConnectionActionButtonsRow1() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

		jButtonNewConnection = new JButton("New");
		jButtonNewConnection.addActionListener(e -> newConnection());
		jButtonDuplicateConnection = new JButton("Duplicate");
		jButtonDuplicateConnection.addActionListener(e -> duplicateConnection());
		jButtonTestConnection = new JButton("Test");
		jButtonTestConnection.addActionListener(e -> testConnection());
		panel.add(jButtonNewConnection);
		panel.add(jButtonDuplicateConnection);
		panel.add(jButtonTestConnection);

		panel.add(new javax.swing.JSeparator(SwingConstants.VERTICAL) {
			@Override
			public Dimension getPreferredSize() {
				return new Dimension(1, 22);
			}
		});

		jButtonSaveConnection = new JButton("Save");
		jButtonSaveConnection.addActionListener(e -> {
			try {
				saveConnectionDetails();
			} catch (BroadSQLException ex) {
				log.error(ex.getLocalizedMessage());
			}
		});
		jButtonDeactivateConnection = new JButton("Deactivate");
		jButtonDeactivateConnection.addActionListener(e -> deactivateSelectedConnections());
		jButtonReactivateConnection = new JButton("Reactivate");
		jButtonReactivateConnection.addActionListener(e -> reactivateSelectedConnections());
		panel.add(jButtonSaveConnection);
		panel.add(jButtonDeactivateConnection);
		panel.add(jButtonReactivateConnection);

		return panel;
	}

	/**
	 * "Delete permanently" isolated on its own row, below the rest - a deliberate, cheap visual "danger
	 * zone" separation for the one action here that is never undoable, rather than lining it up beside
	 * the reversible lifecycle actions above.
	 */
	private JPanel buildConnectionActionButtonsRow2() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		jButtonHardDeleteConnection = new JButton("Delete permanently");
		jButtonHardDeleteConnection.addActionListener(e -> hardDeleteSelectedConnections());
		panel.add(jButtonHardDeleteConnection);
		return panel;
	}

	private int addFormRow(JPanel panel, GridBagConstraints c, int row, String label, Component field) {
		c.gridwidth = 1;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		panel.add(new JLabel(label), c);
		c.gridx = 1;
		c.weightx = 1;
		panel.add(field, c);
		return row + 1;
	}

	/**
	 * One shared File/Edit/View menu bar (SPRINT 0911D, requirement 1) replacing the old per-entity
	 * "Connections"/"Database Groups"/"Environments" menus, which duplicated the tabs directly beneath
	 * them. Every action here dispatches to whichever tab {@link #activeTab()} reports as selected;
	 * {@link #refreshMenuState()} keeps enablement in sync with that tab's current selection/view.
	 */
	private void buildMenuBar() {
		JMenuBar menuBar = new JMenuBar();

		// File contains only Exit (GUI-polish follow-up, requirement 3) - "New" under File
		// conventionally suggests a new file/document (in BroadSQL, that could be misread as a new
		// CDF); New instead lives in Edit, since it creates a new entity within whichever Settings tab
		// is currently selected, not a new top-level document.
		JMenu fileMenu = new JMenu("File");
		jMenuItemClose = new JMenuItem("Exit");
		jMenuItemClose.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F4, 0));
		jMenuItemClose.addActionListener(e -> attemptClose());
		fileMenu.add(jMenuItemClose);
		menuBar.add(fileMenu);

		JMenu editMenu = new JMenu("Edit");
		jMenuItemNew = new JMenuItem("New");
		jMenuItemNew.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_MASK));
		jMenuItemNew.addActionListener(e -> menuNewActionPerformed());
		editMenu.add(jMenuItemNew);
		jMenuItemSave = new JMenuItem("Save");
		jMenuItemSave.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_MASK));
		jMenuItemSave.addActionListener(e -> menuSaveActionPerformed());
		editMenu.add(jMenuItemSave);
		jMenuItemDuplicate = new JMenuItem("Duplicate");
		jMenuItemDuplicate.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_MASK));
		jMenuItemDuplicate.addActionListener(e -> menuDuplicateActionPerformed());
		editMenu.add(jMenuItemDuplicate);
		jMenuItemTest = new JMenuItem("Test");
		jMenuItemTest.addActionListener(e -> menuTestActionPerformed());
		editMenu.add(jMenuItemTest);
		editMenu.addSeparator();
		jMenuItemDeactivate = new JMenuItem("Deactivate");
		jMenuItemDeactivate.addActionListener(e -> menuDeactivateActionPerformed());
		editMenu.add(jMenuItemDeactivate);
		jMenuItemReactivate = new JMenuItem("Reactivate");
		jMenuItemReactivate.addActionListener(e -> menuReactivateActionPerformed());
		editMenu.add(jMenuItemReactivate);
		jMenuItemHardDelete = new JMenuItem("Delete permanently");
		// Deliberately no accelerator (requirement 21: "be extremely careful with the Delete key" - a
		// keyboard shortcut must never permanently destroy data without the same confirmation the
		// button/menu path already requires, so this is reachable only via an explicit menu/button click).
		jMenuItemHardDelete.addActionListener(e -> menuHardDeleteActionPerformed());
		editMenu.add(jMenuItemHardDelete);
		menuBar.add(editMenu);

		JMenu viewMenu = new JMenu("View");
		ButtonGroup viewGroup = new ButtonGroup();
		jMenuItemViewActive = new JRadioButtonMenuItem("Active", true);
		jMenuItemViewInactive = new JRadioButtonMenuItem("Inactive", false);
		jMenuItemViewAll = new JRadioButtonMenuItem("All", false);
		jMenuItemViewActive.addActionListener(e -> menuSetViewMode(SettingsViewMode.ACTIVE));
		jMenuItemViewInactive.addActionListener(e -> menuSetViewMode(SettingsViewMode.INACTIVE));
		jMenuItemViewAll.addActionListener(e -> menuSetViewMode(SettingsViewMode.ALL));
		viewGroup.add(jMenuItemViewActive);
		viewGroup.add(jMenuItemViewInactive);
		viewGroup.add(jMenuItemViewAll);
		viewMenu.add(jMenuItemViewActive);
		viewMenu.add(jMenuItemViewInactive);
		viewMenu.add(jMenuItemViewAll);
		menuBar.add(viewMenu);

		setJMenuBar(menuBar);
	}// buildMenuBar

	// ==================================================================================
	// Shared File/Edit/View menu dispatch
	// ==================================================================================

	private SettingsTab activeTab() {
		int idx = jMainTabbedPane.getSelectedIndex();
		if (idx == 1) {
			return SettingsTab.GROUPS;
		}
		if (idx == 2) {
			return SettingsTab.ENVIRONMENTS;
		}
		return SettingsTab.CONNECTIONS;
	}

	private void menuNewActionPerformed() {
		switch (activeTab()) {
		case CONNECTIONS:
			newConnection();
			break;
		case GROUPS:
			jDatabaseGroupsPanel.triggerNew();
			break;
		case ENVIRONMENTS:
			jEnvironmentsPanel.triggerNew();
			break;
		}
	}

	private void menuDuplicateActionPerformed() {
		if (activeTab() == SettingsTab.CONNECTIONS) {
			duplicateConnection();
		}
	}

	private void menuTestActionPerformed() {
		if (activeTab() == SettingsTab.CONNECTIONS) {
			testConnection();
		}
	}

	private void menuSaveActionPerformed() {
		switch (activeTab()) {
		case CONNECTIONS:
			try {
				saveConnectionDetails();
			} catch (BroadSQLException ex) {
				log.error(ex.getLocalizedMessage());
			}
			break;
		case GROUPS:
			jDatabaseGroupsPanel.triggerSave();
			break;
		case ENVIRONMENTS:
			jEnvironmentsPanel.triggerSave();
			break;
		}
	}

	private void menuDeactivateActionPerformed() {
		switch (activeTab()) {
		case CONNECTIONS:
			deactivateSelectedConnections();
			break;
		case GROUPS:
			jDatabaseGroupsPanel.triggerDeactivate();
			break;
		case ENVIRONMENTS:
			jEnvironmentsPanel.triggerDeactivate();
			break;
		}
	}

	private void menuReactivateActionPerformed() {
		switch (activeTab()) {
		case CONNECTIONS:
			reactivateSelectedConnections();
			break;
		case GROUPS:
			jDatabaseGroupsPanel.triggerReactivate();
			break;
		case ENVIRONMENTS:
			jEnvironmentsPanel.triggerReactivate();
			break;
		}
	}

	private void menuHardDeleteActionPerformed() {
		switch (activeTab()) {
		case CONNECTIONS:
			hardDeleteSelectedConnections();
			break;
		case GROUPS:
			jDatabaseGroupsPanel.triggerHardDelete();
			break;
		case ENVIRONMENTS:
			jEnvironmentsPanel.triggerHardDelete();
			break;
		}
	}

	private void menuSetViewMode(SettingsViewMode mode) {
		switch (activeTab()) {
		case CONNECTIONS:
			safelySwitchConnectionsView(mode);
			break;
		case GROUPS:
			jDatabaseGroupsPanel.triggerSetViewMode(mode);
			refreshMenuState();
			break;
		case ENVIRONMENTS:
			jEnvironmentsPanel.triggerSetViewMode(mode);
			refreshMenuState();
			break;
		}
	}

	private void safelySwitchConnectionsView(SettingsViewMode mode) {
		try {
			switchConnectionsView(mode);
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
		}
	}

	/**
	 * Single place deciding what every File/Edit/View menu item can do, and what the "Show:" radios
	 * reflect, for whichever tab is currently selected - called after every tab switch, selection
	 * change, and view-mode change on any of the three tabs, so the shared menu never goes stale
	 * (SPRINT 0911D requirement 2's "the current view should be obvious without opening a menu"
	 * extends to every shared control, not just the per-tab "Show:" buttons).
	 */
	private void refreshMenuState() {
		switch (activeTab()) {
		case CONNECTIONS: {
			ConnectionSelectionState s = computeConnectionSelectionState();
			boolean cardIsSingle = s.count <= 1;
			boolean selectionBlocksEditing = s.count == 1 && (s.includesSystemConnection || s.allInactive);
			jMenuItemNew.setEnabled(connectionsViewMode != SettingsViewMode.INACTIVE);
			jMenuItemDuplicate.setEnabled(s.count == 1 && !s.includesSystemConnection && s.allActive);
			jMenuItemSave.setEnabled(cardIsSingle && !selectionBlocksEditing);
			jMenuItemTest.setEnabled(cardIsSingle && !selectionBlocksEditing);
			jMenuItemDeactivate.setEnabled(s.count >= 1 && !s.includesSystemConnection && s.allActive);
			jMenuItemReactivate.setEnabled(s.count >= 1 && !s.includesSystemConnection && s.allInactive);
			jMenuItemHardDelete.setEnabled(s.count >= 1 && !s.includesSystemConnection && s.allInactive);
			setViewMenuSelection(connectionsViewMode);
			break;
		}
		case GROUPS: {
			boolean editable = jDatabaseGroupsPanel.hasEditableSelection();
			boolean active = editable && jDatabaseGroupsPanel.isSelectedActive();
			jMenuItemNew.setEnabled(true);
			jMenuItemDuplicate.setEnabled(false);
			jMenuItemSave.setEnabled(true);
			jMenuItemTest.setEnabled(false);
			jMenuItemDeactivate.setEnabled(editable && active);
			jMenuItemReactivate.setEnabled(editable && !active);
			jMenuItemHardDelete.setEnabled(editable && !active);
			setViewMenuSelection(jDatabaseGroupsPanel.getViewMode());
			break;
		}
		case ENVIRONMENTS: {
			boolean editable = jEnvironmentsPanel.hasEditableSelection();
			boolean active = editable && jEnvironmentsPanel.isSelectedActive();
			jMenuItemNew.setEnabled(true);
			jMenuItemDuplicate.setEnabled(false);
			jMenuItemSave.setEnabled(true);
			jMenuItemTest.setEnabled(false);
			jMenuItemDeactivate.setEnabled(editable && active);
			jMenuItemReactivate.setEnabled(editable && !active);
			jMenuItemHardDelete.setEnabled(editable && !active);
			setViewMenuSelection(jEnvironmentsPanel.getViewMode());
			break;
		}
		}
	}// refreshMenuState

	private void setViewMenuSelection(SettingsViewMode mode) {
		jMenuItemViewActive.setSelected(mode == SettingsViewMode.ACTIVE);
		jMenuItemViewInactive.setSelected(mode == SettingsViewMode.INACTIVE);
		jMenuItemViewAll.setSelected(mode == SettingsViewMode.ALL);
	}

	private void attemptClose() {
		if (activeTab() == SettingsTab.CONNECTIONS && connectionFormIsDirty()) {
			int choice = JOptionPane.showConfirmDialog(this, "This connection has unsaved changes.\nSave before closing?", "Unsaved changes",
					JOptionPane.YES_NO_CANCEL_OPTION);
			if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
				return;
			}
			if (choice == JOptionPane.YES_OPTION) {
				try {
					saveConnectionDetails();
				} catch (BroadSQLException ex) {
					log.error(ex.getLocalizedMessage());
					return;
				}
				if (connectionFormIsDirty()) {
					// Save was rejected (validation failure) - stay open, exactly like a normal failed
					// Save (requirement 5): nothing entered is lost.
					return;
				}
			}
			// NO_OPTION falls through: the user explicitly chose to discard the draft.
		}
		dispose();
	}// attemptClose

	// ==================================================================================
	// Connections tab - table model, selection, view mode
	// ==================================================================================

	private final class ConnectionsTableModel extends AbstractTableModel {
		private final String[] columnNames = { "Connection", "Database Group", "Environment", "Status" };

		@Override
		public int getRowCount() {
			return connectionsTableRows.size();
		}

		@Override
		public int getColumnCount() {
			return columnNames.length;
		}

		@Override
		public String getColumnName(int column) {
			return columnNames[column];
		}

		@Override
		public boolean isCellEditable(int rowIndex, int columnIndex) {
			return false;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			DatabaseDefinition d = connectionsTableRows.get(rowIndex);
			switch (columnIndex) {
			case 0:
				return d.getId();
			case 1:
				return StringUtils.defaultString(d.getDatabaseGroup());
			case 2:
				return StringUtils.defaultString(d.getEnvironment());
			case 3:
				return DatabaseDefinition.STATUS_INACTIVE.equalsIgnoreCase(d.getStatus()) ? "Inactive" : "Active";
			default:
				return "";
			}
		}
	}

	/** Greys out every cell of an inactive connection's row - meaningfully mixed only in the "All" view. */
	private final class ConnectionsRowRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
			Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			int modelRow = table.convertRowIndexToModel(row);
			if (modelRow >= 0 && modelRow < connectionsTableRows.size() && !isSelected) {
				boolean inactive = DatabaseDefinition.STATUS_INACTIVE.equalsIgnoreCase(connectionsTableRows.get(modelRow).getStatus());
				c.setForeground(inactive ? Color.GRAY : Color.BLACK);
			}
			return c;
		}
	}

	private static final class TypeListCellRenderer extends javax.swing.DefaultListCellRenderer {
		private final Set<String> unavailableTypes;

		TypeListCellRenderer(Set<String> unavailableTypes) {
			this.unavailableTypes = unavailableTypes;
		}

		@Override
		public Component getListCellRendererComponent(javax.swing.JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
			boolean unavailable = value != null && unavailableTypes.contains(String.valueOf(value));
			Object display = unavailable ? value + " (driver not found)" : value;
			Component c = super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus);
			if (unavailable && !isSelected) {
				c.setForeground(Color.GRAY);
			}
			return c;
		}
	}

	private void applyConnectionsViewMode(SettingsViewMode mode) {
		connectionsViewMode = mode;
		jConnectionsFilterBar.setTitle(mode == SettingsViewMode.ACTIVE ? "Active connections" : mode == SettingsViewMode.INACTIVE ? "Inactive connections" : "All connections");
		jConnectionsFilterBar.setViewMode(mode);
	}

	private void switchConnectionsView(SettingsViewMode mode) throws BroadSQLException {
		applyConnectionsViewMode(mode);
		jTableConnections.clearSelection();
		rebuildConnectionsTableRows();
		refreshConnectionActionState();
		refreshMenuState();
	}// switchConnectionsView

	private List<DatabaseDefinition> activeConnectionsSorted() {
		List<DatabaseDefinition> list = new ArrayList<>(databaseConnectionsCollection.getPlatforms().values());
		for (DatabaseDefinition d : list) {
			if (d.getStatus() == null) {
				d.setStatus(DatabaseDefinition.STATUS_ACTIVE);
			}
		}
		list.sort(Comparator.comparing(DatabaseDefinition::getId, String.CASE_INSENSITIVE_ORDER));
		return list;
	}// activeConnectionsSorted

	private void rebuildConnectionsTableRows() throws BroadSQLException {
		databaseConnectionsCollection.load();
		connectionsTableRows.clear();
		switch (connectionsViewMode) {
		case ACTIVE:
			connectionsTableRows.addAll(activeConnectionsSorted());
			break;
		case INACTIVE:
			connectionsTableRows.addAll(databaseConnectionsCollection.getInactiveConnectionDetails());
			break;
		case ALL:
		default:
			connectionsTableRows.addAll(activeConnectionsSorted());
			connectionsTableRows.addAll(databaseConnectionsCollection.getInactiveConnectionDetails());
			connectionsTableRows.sort(Comparator.comparing(DatabaseDefinition::getId, String.CASE_INSENSITIVE_ORDER));
			break;
		}
		connectionsTableModel.fireTableDataChanged();
	}// rebuildConnectionsTableRows

	private void reloadConnectionsTableAndSelect(String idToSelect) throws BroadSQLException {
		rebuildConnectionsTableRows();
		selectConnectionById(idToSelect);
	}

	private void reloadConnectionsTableAndSelectNearest(int previousViewRow) throws BroadSQLException {
		rebuildConnectionsTableRows();
		int rowCount = jTableConnections.getRowCount();
		if (rowCount > 0) {
			int target = Math.max(0, Math.min(previousViewRow, rowCount - 1));
			jTableConnections.getSelectionModel().setSelectionInterval(target, target);
		}
	}

	private void selectConnectionById(String id) {
		if (id == null) {
			return;
		}
		for (int modelRow = 0; modelRow < connectionsTableRows.size(); modelRow++) {
			if (id.equalsIgnoreCase(connectionsTableRows.get(modelRow).getId())) {
				int viewRow = jTableConnections.convertRowIndexToView(modelRow);
				if (viewRow >= 0) {
					jTableConnections.getSelectionModel().setSelectionInterval(viewRow, viewRow);
				}
				return;
			}
		}
	}// selectConnectionById

	private void applyConnectionsSearchFilter(String query) {
		if (StringUtils.isBlank(query)) {
			connectionsRowSorter.setRowFilter(null);
			return;
		}
		String lower = query.toLowerCase();
		connectionsRowSorter.setRowFilter(new RowFilter<ConnectionsTableModel, Integer>() {
			@Override
			public boolean include(Entry<? extends ConnectionsTableModel, ? extends Integer> entry) {
				DatabaseDefinition d = connectionsTableRows.get(entry.getIdentifier());
				return containsIgnoreCase(d.getId(), lower) || containsIgnoreCase(d.getDatabaseGroup(), lower) || containsIgnoreCase(d.getEnvironment(), lower);
			}
		});
	}// applyConnectionsSearchFilter

	private static boolean containsIgnoreCase(String haystack, String lowerNeedle) {
		return haystack != null && haystack.toLowerCase().contains(lowerNeedle);
	}

	private List<DatabaseDefinition> getSelectedConnections() {
		List<DatabaseDefinition> result = new ArrayList<>();
		for (int viewRow : jTableConnections.getSelectedRows()) {
			int modelRow = jTableConnections.convertRowIndexToModel(viewRow);
			if (modelRow >= 0 && modelRow < connectionsTableRows.size()) {
				result.add(connectionsTableRows.get(modelRow));
			}
		}
		return result;
	}// getSelectedConnections

	private ConnectionSelectionState computeConnectionSelectionState() {
		List<DatabaseDefinition> selected = getSelectedConnections();
		ConnectionSelectionState s = new ConnectionSelectionState();
		s.count = selected.size();
		if (!selected.isEmpty()) {
			s.allActive = true;
			s.allInactive = true;
			for (DatabaseDefinition d : selected) {
				if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(d.getId())) {
					s.includesSystemConnection = true;
				}
				boolean inactive = DatabaseDefinition.STATUS_INACTIVE.equalsIgnoreCase(d.getStatus());
				if (inactive) {
					s.allActive = false;
				} else {
					s.allInactive = false;
				}
			}
		}
		return s;
	}// computeConnectionSelectionState

	private void onConnectionsSelectionChanged() {
		List<DatabaseDefinition> selected = getSelectedConnections();
		if (selected.size() > 1) {
			jConnectionDetailCardLayout.show(jConnectionDetailContainer, "multi");
			jMultiSelectionLabel.setText(selected.size() + " connections selected");
		} else {
			jConnectionDetailCardLayout.show(jConnectionDetailContainer, "single");
			populateConnectionForm(selected.isEmpty() ? null : selected.get(0));
		}
		refreshConnectionActionState();
		refreshMenuState();
	}// onConnectionsSelectionChanged

	private void refreshConnectionActionState() {
		ConnectionSelectionState s = computeConnectionSelectionState();
		boolean cardIsSingle = s.count <= 1;
		boolean selectionBlocksEditing = s.count == 1 && (s.includesSystemConnection || s.allInactive);

		jButtonNewConnection.setEnabled(connectionsViewMode != SettingsViewMode.INACTIVE);
		jButtonDuplicateConnection.setEnabled(s.count == 1 && !s.includesSystemConnection && s.allActive);
		jButtonTestConnection.setEnabled(cardIsSingle && !selectionBlocksEditing);
		jButtonSaveConnection.setEnabled(cardIsSingle && !selectionBlocksEditing);
		jButtonDeactivateConnection.setEnabled(s.count >= 1 && !s.includesSystemConnection && s.allActive);
		jButtonReactivateConnection.setEnabled(s.count >= 1 && !s.includesSystemConnection && s.allInactive);
		jButtonHardDeleteConnection.setEnabled(s.count >= 1 && !s.includesSystemConnection && s.allInactive);
	}// refreshConnectionActionState

	// ==================================================================================
	// Connections tab - form population / editability
	// ==================================================================================

	private void jConnTypeListItemStateChanged(java.awt.event.ItemEvent evt) {
		jConnDriver.setText(driverForType(databaseConnectionsCollection.getDbDrivers(), jConnTypeList.getSelectedItem()));
	}

	/** The (read-only) Driver field for the selected type: that type's JDBC driver, empty for no or an unknown type. */
	static String driverForType(java.util.Map<String, String> drivers, Object selectedType) {
		String type = selectedType == null ? null : selectedType.toString();
		return type != null && drivers != null && drivers.containsKey(type) ? drivers.get(type) : "";
	}

	private void blankConnectionForm() {
		loadedConnectionId = null;
		jConnId.setText("");
		jConnName.setText("");
		jConnDriver.setText("");
		jConnUrl.setText("");
		jConnTypeList.getModel().setSelectedItem(TYPE_DEFAULT);
		jConnUserName.setText("");
		jConnPassword.setText("");
		jConnPasswordRetype.setText("");
		jConnGroupList.getModel().setSelectedItem(NO_GROUP_LABEL);
		jConnEnvironmentList.getModel().setSelectedItem("");
		jConnComment.setText("");
		jUserScriptsPanel.setConnectionId(null);
		updateConnectionEditability(null);
		snapshotConnectionForm();
	}// blankConnectionForm

	private void populateConnectionForm(DatabaseDefinition selected) {
		if (selected == null) {
			blankConnectionForm();
			return;
		}
		loadedConnectionId = selected.getId();
		jConnId.setText(selected.getId());
		jConnName.setText(selected.getDbName());
		jConnDriver.setText(selected.getDbDriver());
		jConnUrl.setText(selected.getUrl());
		jConnTypeList.getModel().setSelectedItem(selected.getDbType());
		jConnUserName.setText(selected.getUserName());
		jConnPassword.setText(selected.getUserPassword());
		jConnPasswordRetype.setText(selected.getUserPassword());
		jConnGroupList.getModel().setSelectedItem(StringUtils.isBlank(selected.getDatabaseGroup()) ? NO_GROUP_LABEL : selected.getDatabaseGroup());
		jConnEnvironmentList.getModel().setSelectedItem(selected.getEnvironment());
		jConnComment.setText(selected.getComment());
		jUserScriptsPanel.setConnectionId(selected.getId());
		updateConnectionEditability(selected);
		snapshotConnectionForm();
	}// populateConnectionForm

	/**
	 * Locks the form against modifying the {@code $CDF} system connection and any inactive connection
	 * (inactive records are view-only everywhere in Settings now, per the consistent lifecycle model -
	 * only Reactivate/Delete permanently apply to them). Replaces the old view-wide
	 * {@code viewingInactiveConnections} check with a per-record one, since a single "All" view can now
	 * mix active and inactive rows.
	 */
	private void updateConnectionEditability(DatabaseDefinition selected) {
		boolean isSystemConnection = selected != null && SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(selected.getId());
		boolean isInactiveRecord = selected != null && DatabaseDefinition.STATUS_INACTIVE.equalsIgnoreCase(selected.getStatus());
		boolean editable = !isSystemConnection && !isInactiveRecord;
		setConnectionFieldsEditable(editable);
	}// updateConnectionEditability

	private void setConnectionFieldsEditable(boolean editable) {
		jConnId.setEditable(editable);
		jConnName.setEditable(editable);
		jConnUrl.setEditable(editable);
		jConnUserName.setEditable(editable);
		jConnPassword.setEditable(editable);
		jConnPasswordRetype.setEditable(editable);
		jConnComment.setEditable(editable);
		jConnTypeList.setEnabled(editable);
		jConnGroupList.setEnabled(editable);
		jConnEnvironmentList.setEnabled(editable);
	}// setConnectionFieldsEditable

	private void snapshotConnectionForm() {
		connectionFormSnapshot = currentConnectionFormValues();
	}

	private String[] currentConnectionFormValues() {
		return new String[] { jConnId.getText(), jConnName.getText(), jConnUrl.getText(), String.valueOf(jConnTypeList.getSelectedItem()), jConnUserName.getText(),
				String.valueOf(jConnPassword.getPassword()), String.valueOf(jConnGroupList.getSelectedItem()), String.valueOf(jConnEnvironmentList.getSelectedItem()),
				jConnComment.getText() };
	}

	private boolean connectionFormIsDirty() {
		return connectionFormSnapshot != null && !java.util.Arrays.equals(connectionFormSnapshot, currentConnectionFormValues());
	}

	// ==================================================================================
	// Connections tab - actions
	// ==================================================================================

	private void newConnection() {
		jTableConnections.clearSelection();
		blankConnectionForm();
		jConnectionDetailCardLayout.show(jConnectionDetailContainer, "single");
		refreshConnectionActionState();
		refreshMenuState();
	}// newConnection

	/**
	 * Preserves the existing Duplicate capability (SPRINT 0911D requirement 6): copies the source
	 * connection's fields into a new, unsaved draft ({@code loadedConnectionId} reset to {@code null}
	 * so Save treats it as a create, not an edit of the source) with {@code _COPY}/{@code  (COPY)}
	 * suffixes on ID/Name. The draft may temporarily collide with the source's (Database Group,
	 * Environment) pair - that is expected and must not make Duplicate unusable; the user changes
	 * Environment or Group before Save, and if Save is attempted before doing so, requirement 5 applies
	 * (the rejected save preserves the draft exactly, rather than discarding it).
	 */
	private void duplicateConnection() {
		List<DatabaseDefinition> selected = getSelectedConnections();
		if (selected.size() != 1) {
			return;
		}
		DatabaseDefinition source = selected.get(0);
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(source.getId())) {
			return;
		}
		jTableConnections.clearSelection();
		populateConnectionForm(source);
		loadedConnectionId = null;
		jConnId.setText(source.getId() + "_COPY");
		jConnName.setText(source.getDbName() + " (COPY)");
		updateConnectionEditability(null);
		snapshotConnectionForm();
		jConnectionDetailCardLayout.show(jConnectionDetailContainer, "single");
		refreshConnectionActionState();
		refreshMenuState();
	}// duplicateConnection

	private boolean checkPasswords() {
		return passwordsMatch(jConnPassword.getPassword(), jConnPasswordRetype.getPassword());
	}//checkPasswords

	/**
	 * Whether the password and its retype are the same. The retype was compared through getSelectedText(), the
	 * selected part of each field (normally none), so two different passwords always "matched".
	 */
	static boolean passwordsMatch(char[] password, char[] retype) {
		return java.util.Arrays.equals(password, retype);
	}

	private DatabaseDefinition getConnectionFromInput() {
		return connectionFromForm(jConnId.getText(), jConnName.getText(), jConnDriver.getText(), jConnUrl.getText(),
				jConnTypeList.getModel().getSelectedItem(), jConnUserName.getText(), jConnPassword.getPassword(),
				jConnGroupList.getModel().getSelectedItem(), jConnEnvironmentList.getModel().getSelectedItem(), jConnComment.getText());
	}//getConnectionFromInput

	/**
	 * The connection the form's fields describe, exactly as Save and Test use it (null without an ID): every field,
	 * the Driver field included, is carried over; the "no Database Group" entry of the list means none.
	 */
	static DatabaseDefinition connectionFromForm(String id, String name, String driver, String url, Object type, String userName, char[] password,
			Object group, Object environment, String comment) {
		if (StringUtils.isBlank(id)) {
			return null;
		}
		DatabaseDefinition conn = new DatabaseDefinition();
		conn.setId(id);
		conn.setDbName(name);
		conn.setDbDriver(driver);
		conn.setUrl(url);
		conn.setDbType(String.valueOf(type));
		conn.setUserName(userName);
		conn.setUserPassword(String.valueOf(password));
		String selectedGroup = String.valueOf(group);
		conn.setDatabaseGroup(NO_GROUP_LABEL.equals(selectedGroup) ? null : selectedGroup);
		conn.setEnvironment(String.valueOf(environment));
		conn.setComment(comment);
		return conn;
	}

	/**
	 * Saves the currently-loaded/drafted connection - the only path that runs full validation
	 * (required Environment, ID collisions, and - via {@link DatabaseDefinitionsVault#saveDatabaseDefinition})
	 * the (Database Group, Environment) uniqueness check. On any failure, this shows the error and
	 * returns immediately: it never reloads the table, never changes the selection, and never resets a
	 * single field (SPRINT 0911D requirement 5 - the bug this sprint fixes: before this, a failed save
	 * still fell through to an unconditional {@code reloadConnections()}, which silently jumped the
	 * selection back to the first connection and discarded everything just typed).
	 */
	private void saveConnectionDetails() throws BroadSQLException {
		if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(loadedConnectionId)) {
			JOptionPane.showMessageDialog(this, "The '" + SpringPropertiesConfig.CDF_ID + "' connection is a system entry and cannot be modified.");
			return;
		}
		if (!checkPasswords()) {
			JOptionPane.showMessageDialog(this, "Password and repeated password are different");
			return;
		}
		DatabaseDefinition draft = getConnectionFromInput();
		if (draft == null) {
			JOptionPane.showMessageDialog(this, "Connection ID is required.");
			return;
		}
		if (StringUtils.isBlank(draft.getEnvironment())) {
			// Small UI-side check for a better error experience - the DAO layer (assertEnvironmentIsValid)
			// is still the authoritative check on save.
			JOptionPane.showMessageDialog(this, "Environment is required for a Connection.");
			return;
		}
		draft.setStatus(DatabaseDefinition.STATUS_ACTIVE);
		String visibleId = draft.getId();
		boolean isNewRecord = StringUtils.isBlank(loadedConnectionId) || !loadedConnectionId.equalsIgnoreCase(visibleId);
		try {
			// CONNECTIONS.ID is a case-insensitive key of at most 15 characters, which an inactive connection still
			// occupies: checked here with the ID as stored, so the user gets a clear message instead of a database error
			String existingId = null;
			if (isNewRecord) {
				DatabaseDefinitionsVault.assertConnectionIdLength(visibleId);
				existingId = databaseConnectionsCollection.findConnectionIdIgnoreCase(visibleId);
			}
			if (existingId != null && databaseConnectionsCollection.contains(existingId)) {
				JOptionPane.showMessageDialog(this, DatabaseDefinitionsVault.connectionIdTakenMessage(visibleId, existingId) + " Choose another identifier.");
				return;
			}
			if (existingId != null) {
				int choice = JOptionPane.showConfirmDialog(this,
						"An inactive connection already exists for ID '" + existingId + "'.\n"
								+ "Reactivating it will restore its previous settings and discard everything you just entered here.\n"
								+ "Do you want to reactivate connection '" + existingId + "'?",
						"Reactivate connection", JOptionPane.YES_NO_OPTION);
				if (choice == JOptionPane.YES_OPTION) {
					databaseConnectionsCollection.reactivateDatabaseDefinition(existingId);
					if (connectionsViewMode == SettingsViewMode.INACTIVE) {
						applyConnectionsViewMode(SettingsViewMode.ACTIVE);
					}
					reloadConnectionsTableAndSelect(existingId);
					JOptionPane.showMessageDialog(this, "Connection '" + existingId + "' reactivated.");
				}
				// "No": the form is left exactly as typed - nothing is saved, nothing else to do here.
				return;
			}
			databaseConnectionsCollection.saveDatabaseDefinition(draft);
			reloadConnectionsTableAndSelect(draft.getId());
			JOptionPane.showMessageDialog(this, "Connection '" + draft.getId() + "' " + (isNewRecord ? "created." : "saved."));
		} catch (BroadSQLException bse) {
			// See method javadoc - this is the fix: report the error and stop, touching nothing else.
			JOptionPane.showMessageDialog(this, "ERROR: " + bse.getLocalizedMessage());
			return;
		}
		refreshConnectionActionState();
		refreshMenuState();
	}//saveConnectionDetails

	/**
	 * Deactivates every selected connection - calls {@link DatabaseDefinitionsVault#softDeleteDatabaseDefinitions}
	 * directly, which runs no validation at all (SPRINT 0911D requirement 7: deactivation must never be
	 * blocked by Save-only validation such as the (Database Group, Environment) uniqueness check - an
	 * obsolete connection with an already-conflicting pair is exactly the kind of connection a user
	 * needs to be able to remove).
	 */
	private void deactivateSelectedConnections() {
		List<DatabaseDefinition> selected = getSelectedConnections();
		if (selected.isEmpty()) {
			return;
		}
		String message = selected.size() == 1 ? ("Deactivate connection '" + selected.get(0).getId() + "'?") : ("Deactivate " + selected.size() + " connections?");
		int input = JOptionPane.showConfirmDialog(this, message);
		if (input != JOptionPane.YES_OPTION) {
			return;
		}
		int[] viewRows = jTableConnections.getSelectedRows();
		int firstViewRow = viewRows.length > 0 ? viewRows[0] : 0;
		List<String> ids = selected.stream().map(DatabaseDefinition::getId).collect(Collectors.toList());
		try {
			databaseConnectionsCollection.softDeleteDatabaseDefinitions(ids);
			reloadConnectionsTableAndSelectNearest(firstViewRow);
			JOptionPane.showMessageDialog(this, ids.size() == 1 ? ("Connection '" + ids.get(0) + "' deactivated.") : (ids.size() + " connections deactivated."));
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
		refreshConnectionActionState();
		refreshMenuState();
	}// deactivateSelectedConnections

	private void reactivateSelectedConnections() {
		List<DatabaseDefinition> selected = getSelectedConnections();
		if (selected.isEmpty()) {
			return;
		}
		List<String> ids = selected.stream().map(DatabaseDefinition::getId).collect(Collectors.toList());
		try {
			databaseConnectionsCollection.reactivateDatabaseDefinitions(ids);
			if (connectionsViewMode == SettingsViewMode.INACTIVE) {
				applyConnectionsViewMode(SettingsViewMode.ACTIVE);
			}
			if (ids.size() == 1) {
				reloadConnectionsTableAndSelect(ids.get(0));
			} else {
				rebuildConnectionsTableRows();
			}
			JOptionPane.showMessageDialog(this, ids.size() == 1 ? ("Connection '" + ids.get(0) + "' reactivated.") : (ids.size() + " connections reactivated."));
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
		refreshConnectionActionState();
		refreshMenuState();
	}// reactivateSelectedConnections

	/**
	 * Permanently deletes every selected connection - prevalidated all-or-nothing by
	 * {@link DatabaseDefinitionsVault#hardDeleteDatabaseDefinitions} (SPRINT 0911D requirement 15): if
	 * any selected connection cannot be hard deleted, none are, and the exception names exactly which
	 * one blocked the batch.
	 */
	private void hardDeleteSelectedConnections() {
		List<DatabaseDefinition> selected = getSelectedConnections();
		if (selected.isEmpty()) {
			return;
		}
		String message = selected.size() == 1 ? ("Permanently delete connection '" + selected.get(0).getId() + "'?\nThis cannot be undone.")
				: ("Permanently delete " + selected.size() + " connections?\nThis cannot be undone.");
		int input = JOptionPane.showConfirmDialog(this, message, "Delete permanently", JOptionPane.YES_NO_OPTION);
		if (input != JOptionPane.YES_OPTION) {
			return;
		}
		int[] viewRows = jTableConnections.getSelectedRows();
		int firstViewRow = viewRows.length > 0 ? viewRows[0] : 0;
		List<String> ids = selected.stream().map(DatabaseDefinition::getId).collect(Collectors.toList());
		try {
			databaseConnectionsCollection.hardDeleteDatabaseDefinitions(ids);
			reloadConnectionsTableAndSelectNearest(firstViewRow);
			JOptionPane.showMessageDialog(this, ids.size() == 1 ? ("Connection '" + ids.get(0) + "' permanently deleted.") : (ids.size() + " connections permanently deleted."));
		} catch (BroadSQLException ex) {
			// Prevalidated all-or-nothing - nothing was deleted; the message names exactly which
			// selected connection blocked the whole batch. Not a bug, so no log.error() here.
			JOptionPane.showMessageDialog(this, ex.getLocalizedMessage());
		}
		refreshConnectionActionState();
		refreshMenuState();
	}// hardDeleteSelectedConnections

	private void testConnection() {
		if (!checkPasswords()) {
			JOptionPane.showMessageDialog(this, "Password and repeated password are different");
			return;
		}
		DatabaseDefinition platform = getConnectionFromInput();
		if (platform == null) {
			JOptionPane.showMessageDialog(this, "Connection ID is required.");
			return;
		}
		if (connection != null) {
			StringBuffer result;
			String res = null;
			try {
				result = connection.testConnectionToPlatform(platform);
				res = result.toString();
			} catch (BroadSQLException e) {
				// test failed
			}
			if (StringUtils.isNotBlank(res)) {
				res = "OK - " + res;
			} else {
				res = "ERROR: connection failed";
			}
			JOptionPane.showMessageDialog(this, res);
		} else {
			JOptionPane.showMessageDialog(this, "Connection '" + platform.getId() + "' is null.");
		}
	}//testConnection

	/**
	 * Reloads the Database Group combo box on the connection form from the CDF - called (via
	 * {@link JDatabaseGroupsPanel#setOnChange}) whenever the "Database Groups" tab saves or changes a
	 * group, so a newly added/renamed/deactivated group is reflected immediately on the "Connections"
	 * tab, without restarting CONFIG.
	 */
	private void refreshGroupsCombo() throws BroadSQLException {
		String previouslySelected = String.valueOf(jConnGroupList.getModel().getSelectedItem());
		TreeSet<String> groups = databaseConnectionsCollection.getGroups();
		DefaultComboBoxModel boxModelInst = new DefaultComboBoxModel();
		boxModelInst.addElement(NO_GROUP_LABEL);
		if (groups != null) {
			for (String s : groups) {
				boxModelInst.addElement(s);
			}//for
		}
		jConnGroupList.setModel(boxModelInst);
		boxModelInst.setSelectedItem(previouslySelected);
	}

	/**
	 * Reloads the Environment combo box on the connection form from the CDF - called (via
	 * {@link JEnvironmentsPanel#setOnChange}) whenever the "Environments" tab saves or changes an
	 * environment, so a newly added/renamed/deactivated environment is reflected immediately on the
	 * "Connections" tab, without restarting CONFIG.
	 */
	private void refreshEnvironmentsCombo() throws BroadSQLException {
		String previouslySelected = String.valueOf(jConnEnvironmentList.getModel().getSelectedItem());
		TreeSet<String> environments = databaseConnectionsCollection.getEnvironments();
		DefaultComboBoxModel boxModelEnv = new DefaultComboBoxModel();
		if (environments != null) {
			for (String s : environments) {
				boxModelEnv.addElement(s);
			}//for
		}
		jConnEnvironmentList.setModel(boxModelEnv);
		boxModelEnv.setSelectedItem(previouslySelected);
	}

	// ==================================================================================
	// Component fields (hand-declared - see initComponents/buildXxx above)
	// ==================================================================================

	private JButton jButtonNewConnection;
	private JButton jButtonDuplicateConnection;
	private JButton jButtonSaveConnection;
	private JButton jButtonTestConnection;
	private JButton jButtonDeactivateConnection;
	private JButton jButtonReactivateConnection;
	private JButton jButtonHardDeleteConnection;
	private JTextArea jConnComment;
	private JTextField jConnDriver;
	private JTextField jConnId;
	private javax.swing.JComboBox jConnGroupList;
	private javax.swing.JComboBox jConnEnvironmentList;
	private JTextField jConnName;
	private JPasswordField jConnPassword;
	private JPasswordField jConnPasswordRetype;
	private javax.swing.JComboBox jConnTypeList;
	private JTextField jConnUrl;
	private JTextField jConnUserName;
	private SettingsFilterBar jConnectionsFilterBar;
	private JTable jTableConnections;
	private JPanel jConnectionDetailContainer;
	private CardLayout jConnectionDetailCardLayout;
	private JLabel jMultiSelectionLabel;
	private JDatabaseGroupsPanel jDatabaseGroupsPanel;
	private JEnvironmentsPanel jEnvironmentsPanel;
	private JTabbedPane jMainTabbedPane;
	private JTabbedPane jTabbedPaneConnection;
	private JUserScriptsPanel jUserScriptsPanel;

	private JMenuItem jMenuItemNew;
	private JMenuItem jMenuItemClose;
	private JMenuItem jMenuItemDuplicate;
	private JMenuItem jMenuItemTest;
	private JMenuItem jMenuItemSave;
	private JMenuItem jMenuItemDeactivate;
	private JMenuItem jMenuItemReactivate;
	private JMenuItem jMenuItemHardDelete;
	private JRadioButtonMenuItem jMenuItemViewActive;
	private JRadioButtonMenuItem jMenuItemViewInactive;
	private JRadioButtonMenuItem jMenuItemViewAll;
}
