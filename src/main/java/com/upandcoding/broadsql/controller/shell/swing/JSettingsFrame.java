package com.upandcoding.broadsql.controller.shell.swing;

//import com.upandcoding.broadsql.model.Document;
import java.awt.Dimension;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JOptionPane;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatLightLaf;
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

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 *
 * @author UpAndCoding.com
 */
public class JSettingsFrame extends javax.swing.JFrame {

	private static final Logger log = LoggerFactory.getLogger(JSettingsFrame.class);
	/*
	
	@Autowired
	DatabaseConnectionsCollection databaseConnectionsCollection;
	*/

	/*
	@Autowired
	ShellConsoleSettings consoleSettings;
	
	@Autowired
	protected ShellConsoleLogger consoleLogger;
	
	@Autowired
	protected ShellConsole cmdLineConsole;
	
	@Autowired
	protected ShellConsolePrinter consoleUtils;
	
	@Autowired
	protected ShellConsoleCommandInterpreter consoleManager;
	
	@Autowired
	DatabaseDefinitionsVault databaseConnectionsCollection;

	@Autowired
	Session session;
	*/
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

	//private SQLDatabaseAsDocumentsDB database;
	private static final String TYPE_DEFAULT = "-- Select type";

	/**
	 * Presentation-only sentinel shown in the Database Group combo when a Connection has none (a
	 * standalone connection, per docs/CONNECTION_MODEL.md) - never persisted. {@link #getConnectionFromInput()}
	 * translates a selection of this label back to a blank/{@code null} Database Group before saving;
	 * {@link #buidConnectionDetails()} translates a blank/{@code null} Database Group back to this label
	 * when displaying an existing connection.
	 */
	private static final String NO_GROUP_LABEL = "<No Database Group>";

	// --- Active/inactive connections views (hand-added, not NetBeans-generated - see docs/TECHNICAL_CHANGE.md) ---
	private boolean viewingInactiveConnections = false;
	private final HashMap<String, DatabaseDefinition> inactiveConnectionsById = new HashMap<>();
	private javax.swing.JRadioButtonMenuItem jMenuItemViewActive;
	private javax.swing.JRadioButtonMenuItem jMenuItemViewInactive;
	private javax.swing.JLabel jConnectionsViewTitle;
	private javax.swing.JPanel jPanelConnectionsWrapper;
	private javax.swing.JPanel jPanelInactiveActions;
	private javax.swing.JButton jButtonReactivateConnection;
	private javax.swing.JButton jButtonHardDeleteConnection;

	/**
	 * Renders every row in grey while {@link #viewingInactiveConnections} is {@code true} - the whole
	 * "Inactive connections" list is, by definition, inactive, so a uniform grey is enough (no
	 * per-row lookup needed, unlike a single mixed active+inactive list would require).
	 */
	private static final class ConnectionListCellRenderer extends javax.swing.DefaultListCellRenderer {
		private final java.util.function.BooleanSupplier greyed;

		ConnectionListCellRenderer(java.util.function.BooleanSupplier greyed) {
			this.greyed = greyed;
		}

		@Override
		public java.awt.Component getListCellRendererComponent(javax.swing.JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
			java.awt.Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (greyed.getAsBoolean() && !isSelected) {
				c.setForeground(java.awt.Color.GRAY);
			}
			return c;
		}
	}

	/**
	 * Greys out and suffixes with " (driver not found)" any Type item whose {@code TYPE.DRIVER} class
	 * is not loadable on this JVM's classpath (see {@link DatabaseDefinitionsVault#isDriverAvailable}) -
	 * marks rather than hides it, so a Connection already using it is still visible/editable, and the
	 * type can still be picked (e.g. to pre-configure a Connection before its driver jar is copied into
	 * {@code drivers/}/{@code lib/}); only {@code CONNECT} itself actually refuses to proceed. Unlike
	 * {@link ConnectionListCellRenderer} (whole-list, single flag - every row in the "inactive" view is
	 * inactive), this checks each item individually since available and unavailable types are mixed in
	 * the same list.
	 */
	private static final class TypeListCellRenderer extends javax.swing.DefaultListCellRenderer {
		private final Set<String> unavailableTypes;

		TypeListCellRenderer(Set<String> unavailableTypes) {
			this.unavailableTypes = unavailableTypes;
		}

		@Override
		public java.awt.Component getListCellRendererComponent(javax.swing.JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
			boolean unavailable = value != null && unavailableTypes.contains(String.valueOf(value));
			Object display = unavailable ? value + " (driver not found)" : value;
			java.awt.Component c = super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus);
			if (unavailable && !isSelected) {
				c.setForeground(java.awt.Color.GRAY);
			}
			return c;
		}
	}

	/**
	 * Creates new form JSettingsFrame
	 */
	public JSettingsFrame() {
	}

	private void initListValues() throws BroadSQLException {
		// The connections list itself is populated by switchConnectionsView()/reloadConnectionsList(),
		// active or inactive depending on the current view - not here.

		// Load list of db types
		TreeSet<String> types = databaseConnectionsCollection.getDbTypes();
		DefaultComboBoxModel boxModel = new DefaultComboBoxModel();
		if (types != null && !types.isEmpty()) {
			for (String s : types) {
				boxModel.addElement(s);
			}//for
			jConnTypeList.setModel(boxModel);
		}
		jConnTypeList.setRenderer(new TypeListCellRenderer(databaseConnectionsCollection.getUnavailableTypes()));

		// Load list of database groups - NO_GROUP_LABEL first, a Connection's Database Group is optional
		TreeSet<String> groups = databaseConnectionsCollection.getGroups();
		DefaultComboBoxModel boxModelInst = new DefaultComboBoxModel();
		boxModelInst.addElement(NO_GROUP_LABEL);
		if (groups != null) {
			for (String s : groups) {
				boxModelInst.addElement(s);
			}//for
		}
		jConnGroupList.setModel(boxModelInst);

		// Load list of environment
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

			// Modern, light Look & Feel (must be set before any component is created)
			FlatLightLaf.setup();

			initComponents();

			// Hides a few elements
			showConnectionsPanel(false);

			// Load actual values from CDF
			initListValues();
			jListConnectionsID.setCellRenderer(new ConnectionListCellRenderer(() -> viewingInactiveConnections));
			switchConnectionsView(false);
			jUserScriptsPanel.setVault(databaseConnectionsCollection);
			jDatabaseGroupsPanel.setOnChange(() -> {
				try {
					refreshGroupsCombo();
				} catch (BroadSQLException ex) {
					log.error(ex.getLocalizedMessage());
				}
			});
			jDatabaseGroupsPanel.setVault(databaseConnectionsCollection);
			jEnvironmentsPanel.setOnChange(() -> {
				try {
					refreshEnvironmentsCombo();
				} catch (BroadSQLException ex) {
					log.error(ex.getLocalizedMessage());
				}
			});
			jEnvironmentsPanel.setVault(databaseConnectionsCollection);

		} catch (Exception se) {
			throw new BroadSQLException(se);
		}
	}

	/**
	 * This method is called from within the constructor to initialize the form.
	 * WARNING: Do NOT modify this code. The content of this method is always
	 * regenerated by the Form Editor.
	 */
	@SuppressWarnings("unchecked")
	// <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
	private void initComponents() {

		jPanel1 = new javax.swing.JPanel();
		jSplitPane2 = new javax.swing.JSplitPane();
		jScrollPaneConnections = new javax.swing.JScrollPane();
		jListConnectionsID = new javax.swing.JList();
		jPanelConnection = new javax.swing.JPanel();
		jConnIdLabel = new javax.swing.JLabel();
		jConnId = new javax.swing.JTextField();
		jConnNameLabel = new javax.swing.JLabel();
		jConnName = new javax.swing.JTextField();
		jConnUrlLabel = new javax.swing.JLabel();
		jConnUrl = new javax.swing.JTextField();
		jConnTypeLabel = new javax.swing.JLabel();
		jConnDriverLabel = new javax.swing.JLabel();
		jConnDriver = new javax.swing.JTextField();
		jConnUserNameLabel = new javax.swing.JLabel();
		jConnUserName = new javax.swing.JTextField();
		jConnPasswordLabel = new javax.swing.JLabel();
		jButtonSaveConnection = new javax.swing.JButton();
		jConnEnvironmentLabel = new javax.swing.JLabel();
		jConnGroupLabel = new javax.swing.JLabel();
		jConnCommentLabel = new javax.swing.JLabel();
		jConnCommentScroll = new javax.swing.JScrollPane();
		jConnComment = new javax.swing.JTextArea();
		jButtonNewConnection = new javax.swing.JButton();
		jConnPassword = new javax.swing.JPasswordField();
		jConnPasswordRetype = new javax.swing.JPasswordField();
		jConnPasswordRetypeLabel = new javax.swing.JLabel();
		jButtonTestConnection = new javax.swing.JButton();
		jConnTypeList = new javax.swing.JComboBox();
		jConnGroupList = new javax.swing.JComboBox();
		jConnEnvironmentList = new javax.swing.JComboBox();
		jTabbedPaneConnection = new javax.swing.JTabbedPane();
		jUserScriptsPanel = new JUserScriptsPanel();
		jMainTabbedPane = new javax.swing.JTabbedPane();
		jDatabaseGroupsPanel = new JDatabaseGroupsPanel();
		jEnvironmentsPanel = new JEnvironmentsPanel();
		jMenuBar1 = new javax.swing.JMenuBar();
		jMenu1 = new javax.swing.JMenu();
		jMenuItem3 = new javax.swing.JMenuItem();
		jMenuItem1 = new javax.swing.JMenuItem();
		jMenuItem4 = new javax.swing.JMenuItem();
		jMenuItem5 = new javax.swing.JMenuItem();
		jMenuItem2 = new javax.swing.JMenuItem();

		setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
		setTitle(SpringPropertiesConfig.APP_TITLE_CONFIG);

		jScrollPaneConnections.setMinimumSize(new java.awt.Dimension(60, 60));

		jListConnectionsID.setModel(new javax.swing.AbstractListModel() {
			String[] strings = { "Item 1", "Item 2", "Item 3", "Item 4", "Item 5" };

			public int getSize() {
				return strings.length;
			}

			public Object getElementAt(int i) {
				return strings[i];
			}
		});
		jListConnectionsID.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		jListConnectionsID.setName(""); // NOI18N
		jListConnectionsID.addListSelectionListener(new javax.swing.event.ListSelectionListener() {
			public void valueChanged(javax.swing.event.ListSelectionEvent evt) {
				jListConnectionsIDValueChanged(evt);
			}
		});
		jScrollPaneConnections.setViewportView(jListConnectionsID);

		jSplitPane2.setLeftComponent(jScrollPaneConnections);

		//jPanelConnection.setPreferredSize(new java.awt.Dimension(800, 438));

		jConnIdLabel.setText("ID");

		jConnId.setText("jTextField1");

		jConnNameLabel.setText("Name");

		jConnName.setText("jTextField2");

		jConnUrlLabel.setText("URL");

		jConnUrl.setText("jTextField3");

		jConnTypeLabel.setText("Type");

		jConnDriverLabel.setText("Driver");

		jConnDriver.setEditable(false);
		jConnDriver.setText("jTextField1");

		jConnUserNameLabel.setText("User name");

		jConnUserName.setText("jTextField1");

		jConnPasswordLabel.setText("Password");

		jButtonSaveConnection.setText("Save changes");
		jButtonSaveConnection.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jButtonSaveConnectionActionPerformed(evt);
			}
		});

		jConnEnvironmentLabel.setText("Environment");

		jConnGroupLabel.setText("Database Group");

		jConnCommentLabel.setText("Comment");

		jConnComment.setColumns(20);
		jConnComment.setRows(3);
		jConnCommentScroll.setViewportView(jConnComment);

		jButtonNewConnection.setText("New connection");
		jButtonNewConnection.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jButtonNewConnectionActionPerformed(evt);
			}
		});

		jConnPassword.setText("jPasswordField1");

		jConnPasswordRetype.setText("jPasswordField1");

		jConnPasswordRetypeLabel.setText("Repeat password");

		jButtonTestConnection.setText("Test connection");
		jButtonTestConnection.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jButtonTestConnectionActionPerformed(evt);
			}
		});

		jConnTypeList.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));
		jConnTypeList.addItemListener(new java.awt.event.ItemListener() {
			public void itemStateChanged(java.awt.event.ItemEvent evt) {
				jConnTypeListItemStateChanged(evt);
			}
		});

		jConnGroupList.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));

		jConnEnvironmentList.setModel(new javax.swing.DefaultComboBoxModel(new String[] { "Item 1", "Item 2", "Item 3", "Item 4" }));

		javax.swing.GroupLayout jPanelConnectionLayout = new javax.swing.GroupLayout(jPanelConnection);
		jPanelConnection.setLayout(jPanelConnectionLayout);
		jPanelConnectionLayout.setHorizontalGroup(
		        jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                .addGroup(jPanelConnectionLayout.createSequentialGroup()
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
		                                .addGroup(jPanelConnectionLayout.createSequentialGroup()
		                                        .addComponent(jButtonNewConnection)
		                                        .addGap(18, 18, 18)
		                                        .addComponent(jButtonTestConnection)
		                                        .addGap(18, 18, 18)
		                                        .addComponent(jButtonSaveConnection))
		                                .addGroup(jPanelConnectionLayout.createSequentialGroup()
		                                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
		                                                .addComponent(jConnCommentLabel)
		                                                .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                                                        .addGroup(jPanelConnectionLayout.createSequentialGroup()
		                                                                .addGap(26, 26, 26)
		                                                                .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
		                                                                        .addComponent(jConnDriverLabel)
		                                                                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                                                                                .addComponent(jConnUserNameLabel)
		                                                                                .addComponent(jConnPasswordLabel, javax.swing.GroupLayout.Alignment.TRAILING))
		                                                                        .addComponent(jConnTypeLabel)
		                                                                        .addComponent(jConnUrlLabel)
		                                                                        .addComponent(jConnNameLabel)
		                                                                        .addComponent(jConnPasswordRetypeLabel)
		                                                                        .addComponent(jConnIdLabel)))
		                                                        .addComponent(jConnGroupLabel, javax.swing.GroupLayout.Alignment.TRAILING))
		                                                .addComponent(jConnEnvironmentLabel))
		                                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
		                                                .addComponent(jConnPasswordRetype, javax.swing.GroupLayout.PREFERRED_SIZE, 217, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                                .addComponent(jConnName, javax.swing.GroupLayout.PREFERRED_SIZE, 366, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                                .addComponent(jConnId, javax.swing.GroupLayout.PREFERRED_SIZE, 99, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                                .addComponent(jConnPassword, javax.swing.GroupLayout.PREFERRED_SIZE, 217, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                                .addComponent(jConnUrl, javax.swing.GroupLayout.PREFERRED_SIZE, 366, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                                .addComponent(jConnCommentScroll)
		                                                .addComponent(jConnGroupList, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
		                                                .addComponent(jConnEnvironmentList, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
		                                                .addComponent(jConnUserName)
		                                                .addComponent(jConnDriver)
		                                                .addComponent(jConnTypeList, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))))
		                        .addContainerGap())
		        );
		jPanelConnectionLayout.setVerticalGroup(
		        jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                .addGroup(jPanelConnectionLayout.createSequentialGroup()
		                        .addContainerGap()
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
		                                .addComponent(jConnIdLabel)
		                                .addComponent(jConnId, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
		                                .addComponent(jConnNameLabel)
		                                .addComponent(jConnName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
		                                .addComponent(jConnUrlLabel)
		                                .addComponent(jConnUrl, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
		                                .addComponent(jConnTypeLabel)
		                                .addComponent(jConnTypeList, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
		                                .addComponent(jConnDriver, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                .addComponent(jConnDriverLabel))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
		                                .addComponent(jConnUserName, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                .addComponent(jConnUserNameLabel))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                                .addComponent(jConnPasswordLabel)
		                                .addComponent(jConnPassword, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
		                                .addComponent(jConnPasswordRetype, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                .addComponent(jConnPasswordRetypeLabel))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                                .addComponent(jConnEnvironmentList, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                .addComponent(jConnEnvironmentLabel))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
		                                .addComponent(jConnGroupLabel)
		                                .addComponent(jConnGroupList, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                                .addComponent(jConnCommentLabel)
		                                .addComponent(jConnCommentScroll, javax.swing.GroupLayout.PREFERRED_SIZE, 72, javax.swing.GroupLayout.PREFERRED_SIZE))
		                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
		                        .addGroup(jPanelConnectionLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
		                                .addComponent(jButtonNewConnection)
		                                .addComponent(jButtonTestConnection)
		                                .addComponent(jButtonSaveConnection))
		                        .addContainerGap())
		        );

		jTabbedPaneConnection.addTab("Connection", jPanelConnection);
		jTabbedPaneConnection.addTab("Login Scripts", jUserScriptsPanel);
		jSplitPane2.setRightComponent(jTabbedPaneConnection);

		// Wraps jSplitPane2 with a view title (NORTH) and the inactive-view-only Reactivate/Delete
		// action bar (SOUTH) - hand-added, not NetBeans-generated (see docs/TECHNICAL_CHANGE.md).
		jConnectionsViewTitle = new javax.swing.JLabel("Active connections");
		jConnectionsViewTitle.setFont(jConnectionsViewTitle.getFont().deriveFont(java.awt.Font.BOLD, 14f));
		jConnectionsViewTitle.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8));

		jButtonReactivateConnection = new javax.swing.JButton("Reactivate");
		jButtonReactivateConnection.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				reactivateSelectedConnection();
			}
		});
		jButtonHardDeleteConnection = new javax.swing.JButton("Delete");
		jButtonHardDeleteConnection.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				hardDeleteSelectedConnection();
			}
		});
		jPanelInactiveActions = new javax.swing.JPanel();
		jPanelInactiveActions.add(jButtonReactivateConnection);
		jPanelInactiveActions.add(jButtonHardDeleteConnection);

		jPanelConnectionsWrapper = new javax.swing.JPanel(new java.awt.BorderLayout());
		jPanelConnectionsWrapper.add(jConnectionsViewTitle, java.awt.BorderLayout.NORTH);
		jPanelConnectionsWrapper.add(jSplitPane2, java.awt.BorderLayout.CENTER);
		jPanelConnectionsWrapper.add(jPanelInactiveActions, java.awt.BorderLayout.SOUTH);

		javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
		jPanel1.setLayout(jPanel1Layout);
		jPanel1Layout.setHorizontalGroup(
		        jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                .addGap(0, 0, Short.MAX_VALUE)
		                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                        .addGroup(jPanel1Layout.createSequentialGroup()
		                                .addComponent(jPanelConnectionsWrapper, javax.swing.GroupLayout.PREFERRED_SIZE, 981, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                .addGap(0, 10, Short.MAX_VALUE)))
		        );
		jPanel1Layout.setVerticalGroup(
		        jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                .addGap(0, 0, Short.MAX_VALUE)
		                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
		                        .addGroup(jPanel1Layout.createSequentialGroup()
		                                .addComponent(jPanelConnectionsWrapper, javax.swing.GroupLayout.PREFERRED_SIZE, 560, javax.swing.GroupLayout.PREFERRED_SIZE)
		                                .addGap(0, 11, Short.MAX_VALUE)))
		        );

		jMenu1.setText("Connections");

		// View active/inactive connections - hand-added, not NetBeans-generated (see docs/TECHNICAL_CHANGE.md).
		javax.swing.ButtonGroup connectionsViewGroup = new javax.swing.ButtonGroup();
		jMenuItemViewActive = new javax.swing.JRadioButtonMenuItem("View active connections", true);
		jMenuItemViewActive.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				try {
					switchConnectionsView(false);
				} catch (BroadSQLException ex) {
					log.error(ex.getLocalizedMessage());
				}
			}
		});
		jMenuItemViewInactive = new javax.swing.JRadioButtonMenuItem("View inactive connections", false);
		jMenuItemViewInactive.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				try {
					switchConnectionsView(true);
				} catch (BroadSQLException ex) {
					log.error(ex.getLocalizedMessage());
				}
			}
		});
		connectionsViewGroup.add(jMenuItemViewActive);
		connectionsViewGroup.add(jMenuItemViewInactive);
		jMenu1.add(jMenuItemViewActive);
		jMenu1.add(jMenuItemViewInactive);
		jMenu1.add(new javax.swing.JPopupMenu.Separator());

		jMenuItem3.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, java.awt.event.InputEvent.CTRL_MASK));
		jMenuItem3.setText("New connection");
		jMenuItem3.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jMenuItem3ActionPerformed(evt);
			}
		});
		jMenu1.add(jMenuItem3);

		jMenuItem5.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, java.awt.event.InputEvent.CTRL_MASK));
		jMenuItem5.setText("Duplicate connection");
		jMenuItem5.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jMenuItem5ActionPerformed(evt);
			}
		});
		jMenu1.add(jMenuItem5);

		jMenuItem1.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_MASK));
		jMenuItem1.setText("Save connection");
		jMenuItem1.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jMenuItem1ActionPerformed(evt);
			}
		});
		jMenu1.add(jMenuItem1);

		jMenuItem4.setText("Delete connection");
		jMenuItem4.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				try {
					jMenuItem4ActionPerformed(evt);
				} catch (BroadSQLException ex) {
					log.error(ex.getLocalizedMessage());
				}
			}
		});
		jMenu1.add(jMenuItem4);

		jMenuBar1.add(jMenu1);

		// "Database Groups" menu - New/Save/Delete drive JDatabaseGroupsPanel's own tab remotely,
		// mirroring the "Connections" menu above. No keyboard accelerators, to avoid colliding with
		// the "Connections" menu's Ctrl+N/Ctrl+S (JMenuBar accelerators are global regardless of which
		// tab is showing).
		jMenuGroups = new javax.swing.JMenu();
		jMenuGroups.setText("Database Groups");

		jMenuItemGroupNew = new javax.swing.JMenuItem();
		jMenuItemGroupNew.setText("New Database Group");
		jMenuItemGroupNew.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jDatabaseGroupsPanel.triggerNew();
			}
		});
		jMenuGroups.add(jMenuItemGroupNew);

		jMenuItemGroupSave = new javax.swing.JMenuItem();
		jMenuItemGroupSave.setText("Save Database Group");
		jMenuItemGroupSave.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jDatabaseGroupsPanel.triggerSave();
			}
		});
		jMenuGroups.add(jMenuItemGroupSave);

		jMenuItemGroupDelete = new javax.swing.JMenuItem();
		jMenuItemGroupDelete.setText("Delete Database Group");
		jMenuItemGroupDelete.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jDatabaseGroupsPanel.triggerDelete();
			}
		});
		jMenuGroups.add(jMenuItemGroupDelete);

		jMenuBar1.add(jMenuGroups);

		// "Environments" menu - same shape as "Database Groups" above, driving JEnvironmentsPanel.
		jMenuEnvironments = new javax.swing.JMenu();
		jMenuEnvironments.setText("Environments");

		jMenuItemEnvironmentNew = new javax.swing.JMenuItem();
		jMenuItemEnvironmentNew.setText("New Environment");
		jMenuItemEnvironmentNew.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jEnvironmentsPanel.triggerNew();
			}
		});
		jMenuEnvironments.add(jMenuItemEnvironmentNew);

		jMenuItemEnvironmentSave = new javax.swing.JMenuItem();
		jMenuItemEnvironmentSave.setText("Save Environment");
		jMenuItemEnvironmentSave.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jEnvironmentsPanel.triggerSave();
			}
		});
		jMenuEnvironments.add(jMenuItemEnvironmentSave);

		jMenuItemEnvironmentDelete = new javax.swing.JMenuItem();
		jMenuItemEnvironmentDelete.setText("Delete Environment");
		jMenuItemEnvironmentDelete.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jEnvironmentsPanel.triggerDelete();
			}
		});
		jMenuEnvironments.add(jMenuItemEnvironmentDelete);

		jMenuBar1.add(jMenuEnvironments);

		// "Exit" menu - houses the "Close" item moved out of "Connections", where it did not
		// conceptually belong (closing CONFIG is not a connections-management action).
		jMenuExit = new javax.swing.JMenu();
		jMenuExit.setText("Exit");

		jMenuItem2.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F4, 0));
		jMenuItem2.setText("Close");
		jMenuItem2.addActionListener(new java.awt.event.ActionListener() {
			public void actionPerformed(java.awt.event.ActionEvent evt) {
				jMenuItem2ActionPerformed(evt);
			}
		});
		jMenuExit.add(jMenuItem2);

		jMenuBar1.add(jMenuExit);

		setJMenuBar(jMenuBar1);

		jMainTabbedPane.addTab("Connections", jPanel1);
		jMainTabbedPane.addTab("Database Groups", jDatabaseGroupsPanel);
		jMainTabbedPane.addTab("Environments", jEnvironmentsPanel);

		getContentPane().setLayout(new java.awt.BorderLayout());
		getContentPane().add(jMainTabbedPane, java.awt.BorderLayout.CENTER);

		pack();
	}// </editor-fold>//GEN-END:initComponents

	private void jListConnectionsIDValueChanged(javax.swing.event.ListSelectionEvent evt) {//GEN-FIRST:event_jListConnectionsIDValueChanged
		try {
			// TODO add your handling code here:
			buidConnectionDetails();
			showConnectionsPanel(true);
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
		}
	}//GEN-LAST:event_jListConnectionsIDValueChanged

	private void jMenuItem2ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem2ActionPerformed
		// TODO add your handling code here:
		this.dispose();
	}//GEN-LAST:event_jMenuItem2ActionPerformed

	/**
	 * Reloads the Database Group combo box on the connection form from the CDF - called (via
	 * {@link JDatabaseGroupsPanel#setOnChange}) whenever the "Database Groups" tab saves or deletes a
	 * group, so a newly added/renamed/deactivated group is reflected immediately on the "Connections"
	 * tab, without restarting CONFIG.
	 *
	 * <p>Phase 1 of the {@code docs/CONNECTION_MODEL.md} rework: renamed from {@code refreshInstancesCombo}.
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
	 * {@link JEnvironmentsPanel#setOnChange}) whenever the "Environments" tab saves or deletes an
	 * environment, so a newly added/renamed/deactivated environment is reflected immediately on the
	 * "Connections" tab, without restarting CONFIG. Mirrors {@link #refreshGroupsCombo()}; before Phase
	 * 1 this combo was only ever populated once, in {@link #initListValues()}, since Environment had no
	 * management screen of its own.
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

	private void jButtonTestConnectionActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonTestConnectionActionPerformed
		testConnection();
	}//GEN-LAST:event_jButtonTestConnectionActionPerformed

	private void jButtonNewConnectionActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonNewConnectionActionPerformed
		newConnection();
		showConnectionsPanel(true);
	}//GEN-LAST:event_jButtonNewConnectionActionPerformed

	private void jButtonSaveConnectionActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jButtonSaveConnectionActionPerformed
		try {
			// TODO add your handling code here:
			saveConnectionDetails(false, true);
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
		}
	}//GEN-LAST:event_jButtonSaveConnectionActionPerformed

	private void jMenuItem1ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem1ActionPerformed
		// TODO add your handling code here:
		try {
			// TODO add your handling code here:
			saveConnectionDetails(false, true);
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
		}
	}//GEN-LAST:event_jMenuItem1ActionPerformed

	private void jMenuItem3ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem3ActionPerformed
		// TODO add your handling code here:
		newConnection();
		showConnectionsPanel(true);
	}//GEN-LAST:event_jMenuItem3ActionPerformed

	private void jMenuItem5ActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMenuItem3ActionPerformed
		// TODO add your handling code here:
		duplicateConnection();
		showConnectionsPanel(true);
	}//GEN-LAST:event_jMenuItem3ActionPerformed

	// DELETE Connection
	private void jMenuItem4ActionPerformed(java.awt.event.ActionEvent evt) throws BroadSQLException {//GEN-FIRST:event_jMenuItem4ActionPerformed
		String connId = jListConnectionsID.getSelectedValue().toString();
		DatabaseDefinition connection = getDatabaseConnection();
		if (connection != null) {
			int input = JOptionPane.showConfirmDialog(this, "Please confirm deletion of connection '" + connId + "'?");
			// 0=yes, 1=no, 2=cancel
			if (input == 0) {
				saveConnectionDetails(true, false);
				newConnection();
				JOptionPane.showMessageDialog(this, "Connection '" + connId + "' deleted");
			}
		} else {
			JOptionPane.showMessageDialog(this, "Connection '" + connId + "' does not exist in the Connections Definition File");
		}
	}//GEN-LAST:event_jMenuItem4ActionPerformed

	private void jConnTypeListItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_jConnTypeListItemStateChanged
		HashMap<String, String> drivers = databaseConnectionsCollection.getDbDrivers();
		String val = jConnTypeList.getSelectedItem().toString();
		if (drivers.containsKey(val)) {
			String driver = drivers.get(val);
			jConnDriver.setText(driver);
		} else {
			jConnDriver.setText("");
		}
	}

	/**
	 * Switches the "Connections" tab between its two mutually-exclusive views (rule confirmed
	 * 03/09/2026: two distinct views, not a single filtered list) - reloads the list from the right
	 * source ({@link #reloadConnectionsList}), updates the title, and restricts which actions are
	 * reachable: an inactive connection can only be viewed, reactivated, or hard deleted, never
	 * created/edited/saved/duplicated/soft-deleted (those controls are disabled/hidden entirely).
	 */
	private void switchConnectionsView(boolean inactive) throws BroadSQLException {
		viewingInactiveConnections = inactive;
		jConnectionsViewTitle.setText(inactive ? "Inactive connections" : "Active connections");
		jMenuItemViewActive.setSelected(!inactive);
		jMenuItemViewInactive.setSelected(inactive);

		jMenuItem3.setEnabled(!inactive); // New connection - not selection-dependent, unlike the three below

		// Save/Duplicate/Delete and field editability are selection-dependent (see
		// updateConnectionEditability - $CDF must stay locked even in the active view), so newConnection()
		// below (which resets them for the new, empty selection) is what actually applies the
		// view-level baseline here, not a direct assignment.
		newConnection();
		showConnectionsPanel(false);
		reloadConnectionsList(null);
	}// switchConnectionsView

	/**
	 * Reloads {@code jListConnectionsID} from the CDF, active or inactive depending on
	 * {@link #viewingInactiveConnections} - the single place both {@link #switchConnectionsView} and
	 * every action that changes a connection's status (reactivate, hard delete, save) refresh the list
	 * from, replacing the old always-active {@code reloadConnections}.
	 *
	 * @param idToSelect the ID to re-select afterward if still present in this view, or {@code null}/not
	 *                    found to select the first row (or nothing, if the view is now empty)
	 */
	private void reloadConnectionsList(String idToSelect) throws BroadSQLException {
		databaseConnectionsCollection.load();
		DefaultListModel<String> listModel = new DefaultListModel<>();
		int selectIndex = 0;
		int i = 0;
		if (viewingInactiveConnections) {
			inactiveConnectionsById.clear();
			for (DatabaseDefinition def : databaseConnectionsCollection.getInactiveConnectionDetails()) {
				inactiveConnectionsById.put(def.getId(), def);
				listModel.addElement(def.getId());
				if (def.getId().equalsIgnoreCase(idToSelect)) {
					selectIndex = i;
				}
				i++;
			}
		} else {
			TreeSet<String> ids = databaseConnectionsCollection.getIds();
			if (ids != null) {
				for (String s : ids) {
					listModel.addElement(s);
					if (s.equalsIgnoreCase(idToSelect)) {
						selectIndex = i;
					}
					i++;
				}
			}
		}
		jListConnectionsID.setModel(listModel);
		if (!listModel.isEmpty()) {
			jListConnectionsID.setSelectedIndex(selectIndex);
		}
	}// reloadConnectionsList

	/**
	 * Locks every editable connection-detail field when {@code false} - an inactive connection can be
	 * viewed but not modified, only reactivated or hard deleted (rule confirmed 03/09/2026).
	 */
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

	private void reactivateSelectedConnection() {
		if (jListConnectionsID.getSelectedValue() == null) {
			return;
		}
		String id = jListConnectionsID.getSelectedValue().toString();
		int input = JOptionPane.showConfirmDialog(this, "Reactivate connection '" + id + "'?");
		if (input != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			databaseConnectionsCollection.reactivateDatabaseDefinition(id);
			JOptionPane.showMessageDialog(this, "Connection '" + id + "' reactivated.");
			switchConnectionsView(false);
			reloadConnectionsList(id);
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}// reactivateSelectedConnection

	private void hardDeleteSelectedConnection() {
		if (jListConnectionsID.getSelectedValue() == null) {
			return;
		}
		String id = jListConnectionsID.getSelectedValue().toString();
		int input = JOptionPane.showConfirmDialog(this, "Permanently delete connection '" + id + "'? This cannot be undone.");
		if (input != JOptionPane.YES_OPTION) {
			return;
		}
		try {
			databaseConnectionsCollection.deleteDatabaseDefinition(id);
			JOptionPane.showMessageDialog(this, "Connection '" + id + "' permanently deleted.");
			newConnection();
			showConnectionsPanel(false);
			reloadConnectionsList(null);
		} catch (BroadSQLException ex) {
			log.error(ex.getLocalizedMessage());
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}// hardDeleteSelectedConnection

	private void showConnectionsPanel(boolean show) {
		Dimension minimumSize = new Dimension(100, 50);
		jScrollPaneConnections.setMinimumSize(minimumSize);

		jConnId.setVisible(show);
		jConnName.setVisible(show);
		jConnDriver.setVisible(show);
		jConnUrl.setVisible(show);
		jConnTypeList.setVisible(show);
		jConnUserName.setVisible(show);
		jConnPassword.setVisible(show);
		jConnPasswordRetype.setVisible(show);
		jConnGroupList.setVisible(show);
		jConnEnvironmentList.setVisible(show);
		jConnComment.setVisible(show);
		jConnIdLabel.setVisible(show);
		jConnNameLabel.setVisible(show);
		jConnDriverLabel.setVisible(show);
		jConnUrlLabel.setVisible(show);
		jConnTypeLabel.setVisible(show);
		jConnUserNameLabel.setVisible(show);
		jConnPasswordLabel.setVisible(show);
		jConnPasswordRetypeLabel.setVisible(show);
		jConnGroupLabel.setVisible(show);
		jConnEnvironmentLabel.setVisible(show);
		jConnCommentLabel.setVisible(show);
		jConnCommentScroll.setVisible(show);

		// New/Test/Save only ever apply to an active connection; Reactivate/Delete only to an inactive
		// one - the two button groups are mutually exclusive, driven by viewingInactiveConnections.
		boolean showActiveActions = show && !viewingInactiveConnections;
		boolean showInactiveActions = show && viewingInactiveConnections;
		jButtonSaveConnection.setVisible(showActiveActions);
		jButtonTestConnection.setVisible(showActiveActions);
		jButtonNewConnection.setVisible(showActiveActions);
		jButtonReactivateConnection.setVisible(showInactiveActions);
		jButtonHardDeleteConnection.setVisible(showInactiveActions);
	}//hideConnectionsPanel

	/**
	 * Gets the DatabaseConnection fro, the selected ID in the selection list
	 * Assumes that the connection already exists If nothing is selected,
	 * returns a null value
	 *
	 * @return
	 */
	private DatabaseDefinition getDatabaseConnection() {
		DatabaseDefinition conn = null;
		if (jListConnectionsID.getSelectedValue() != null) {
			String id = jListConnectionsID.getSelectedValue().toString();
			if (StringUtils.isNotBlank(id)) {
				conn = viewingInactiveConnections ? inactiveConnectionsById.get(id) : databaseConnectionsCollection.getPlatforms().get(id);
			}
		}
		return (conn);
	}//getDatabaseConnection

	private void buidConnectionDetails() throws BroadSQLException {
		DatabaseDefinition connection = getDatabaseConnection();
		if (connection != null) {
			jConnId.setText(connection.getId());
			jConnName.setText(connection.getDbName());
			jConnDriver.setText(connection.getDbDriver());
			jConnUrl.setText(connection.getUrl());
			jConnTypeList.getModel().setSelectedItem(connection.getDbType());
			jConnUserName.setText(connection.getUserName());
			jConnPassword.setText(connection.getUserPassword());
			jConnPasswordRetype.setText(connection.getUserPassword());
			jConnGroupList.getModel().setSelectedItem(StringUtils.isBlank(connection.getDatabaseGroup()) ? NO_GROUP_LABEL : connection.getDatabaseGroup());
			jConnEnvironmentList.getModel().setSelectedItem(connection.getEnvironment());
			jConnComment.setText(connection.getComment());
			jUserScriptsPanel.setConnectionId(connection.getId());
			updateConnectionEditability(connection);
		}
	}//buidConnectionDetails

	private void newConnection() {
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
	}//newConnection

	/**
	 * Locks the "Connections" tab against modifying or deleting the {@code $CDF} connection itself -
	 * BroadSQL's own connection to its Connections Definition File, a system row rather than a
	 * user-managed one. Deleting it would sever CONFIG's own ability to reopen the CDF; changing its
	 * URL/driver/credentials, Database Group, or Environment out from under a live connection would be
	 * just as damaging (see also {@code CommandSetConnectionPassword}, which already refuses to run
	 * against {@code $CDF} for the same reason).
	 *
	 * <p>Combines with the existing active/inactive-view restriction ({@link #switchConnectionsView}):
	 * whichever is more restrictive wins. Called after every change of which connection is displayed
	 * (a new selection via {@link #buidConnectionDetails()}, or the form being cleared via
	 * {@link #newConnection()}) - {@code switchConnectionsView} itself only sets the view-level
	 * baseline, so without this, selecting {@code $CDF} then switching away without a further page
	 * reload/deletion could leave the previous per-connection lock stale.
	 */
	private void updateConnectionEditability(DatabaseDefinition connection) {
		boolean isSystemConnection = connection != null && SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(connection.getId());
		boolean editable = !viewingInactiveConnections && !isSystemConnection;
		setConnectionFieldsEditable(editable);
		jMenuItem1.setEnabled(editable); // Save connection
		jMenuItem4.setEnabled(editable); // Delete connection (soft)
		jMenuItem5.setEnabled(editable); // Duplicate connection
		jButtonSaveConnection.setEnabled(editable);
	}// updateConnectionEditability

	private void duplicateConnection() {
		DatabaseDefinition connection = getConnectionFromInput();
		connection.setId(jConnId.getText() + "_COPY");
		connection.setDbName(jConnName.getText() + " (COPY)");
		jConnId.setText(connection.getId());
		jConnName.setText(connection.getDbName());
	}

	private boolean checkPasswords() {
		boolean samePasswords;
		String pwd1 = String.valueOf(jConnPassword.getSelectedText());
		String pwd2 = String.valueOf(jConnPasswordRetype.getSelectedText());
		samePasswords = StringUtils.equals(pwd1, pwd2);
		return (samePasswords);
	}//checkPasswords

	private DatabaseDefinition getConnectionFromInput() {
		DatabaseDefinition connection = null;
		if (StringUtils.isNotBlank(jConnId.getText())) {
			connection = new DatabaseDefinition();
			connection.setId(jConnId.getText());
			connection.setDbName(jConnName.getText());
			connection.setDbDriver(jConnDriver.getText());
			connection.setUrl(jConnUrl.getText());
			connection.setDbType(String.valueOf(jConnTypeList.getModel().getSelectedItem()));
			connection.setUserName(jConnUserName.getText());
			connection.setUserPassword(String.valueOf(jConnPassword.getPassword()));
			String selectedGroup = String.valueOf(jConnGroupList.getModel().getSelectedItem());
			connection.setDatabaseGroup(NO_GROUP_LABEL.equals(selectedGroup) ? null : selectedGroup);
			connection.setEnvironment(String.valueOf(jConnEnvironmentList.getModel().getSelectedItem()));
			//connection.setStatus(DatabaseDefinition.STATUS_ACTIVE); //status
			connection.setComment(jConnComment.getText());
		}
		return (connection);
	}//getConnectionFromInput

	/*
	 * Saves current connection details, whether it is a new or existing connection
	 */
	private void saveConnectionDetails(boolean shouldDelete, boolean showMessage) throws BroadSQLException {
		if (jConnId.isVisible()) {
			String selectedId = "";
			if (!jListConnectionsID.isSelectionEmpty()) {
				selectedId = jListConnectionsID.getSelectedValue().toString();
			}
			if (SpringPropertiesConfig.CDF_ID.equalsIgnoreCase(selectedId)) {
				// Authoritative guard, not just the disabled Save/Delete menu items and locked fields
				// (see updateConnectionEditability): BroadSQL's own connection to its Connections
				// Definition File is a system row, not a user-managed one.
				JOptionPane.showMessageDialog(this, "The '" + SpringPropertiesConfig.CDF_ID + "' connection is a system entry and cannot be "
						+ (shouldDelete ? "deleted." : "modified."));
				return;
			}
			String visibleId = jConnId.getText();

			if (checkPasswords()) {
				DatabaseDefinition connection = getConnectionFromInput();
				if (StringUtils.isBlank(connection.getEnvironment())) {
					// Small UI-side check for a better error experience - the DAO layer
					// (assertEnvironmentIsValid) is still the authoritative check on save.
					JOptionPane.showMessageDialog(this, "Environment is required for a Connection.");
					return;
				}
				if (shouldDelete) {
					connection.setStatus(DatabaseDefinition.STATUS_INACTIVE);
				} else {
					connection.setStatus(DatabaseDefinition.STATUS_ACTIVE);
				}
				// A new record if the ID being saved differs from (or there was no) selected row - an
				// in-place edit of the currently-selected active connection is never subject to the
				// collision checks below, exactly like before this change.
				boolean isNewRecord = StringUtils.isBlank(selectedId) || !selectedId.equalsIgnoreCase(visibleId);
				try {
					if (isNewRecord && databaseConnectionsCollection.contains(visibleId)) {
						JOptionPane.showMessageDialog(this, "A connection already exists for ID '" + visibleId + "'. Choose another identifier.");
					} else if (isNewRecord && databaseConnectionsCollection.isInactiveConnection(visibleId)) {
						int choice = JOptionPane.showConfirmDialog(this,
								"An inactive connection already exists for ID '" + visibleId + "'.\n"
										+ "Reactivating it will restore its previous settings and discard everything you just entered here.\n"
										+ "Do you want to reactivate connection '" + visibleId + "'?",
								"Reactivate connection", JOptionPane.YES_NO_OPTION);
						if (choice == JOptionPane.YES_OPTION) {
							databaseConnectionsCollection.reactivateDatabaseDefinition(visibleId);
							if (showMessage) {
								JOptionPane.showMessageDialog(this, "Connection '" + visibleId + "' reactivated.");
							}
						}
						// "No": the form is left exactly as typed - nothing is saved, nothing else to do here.
					} else {
						databaseConnectionsCollection.saveDatabaseDefinition(connection);
						if (!isNewRecord) {
							// Updating existing connection
							if (showMessage) {
								JOptionPane.showMessageDialog(this, "Connection '" + jListConnectionsID.getSelectedValue() + "' saved.");
							}
						} else {
							// Creating new Connection
							if (showMessage) {
								JOptionPane.showMessageDialog(this, "Connection '" + jConnId.getText() + "' created.");
							}
						}
					}
				} catch (BroadSQLException bse) {
					JOptionPane.showMessageDialog(this, "ERROR: '" + bse.getLocalizedMessage());
				}
				reloadConnections(shouldDelete, connection.getId());
			} else {
				JOptionPane.showMessageDialog(this, "Password and repeated password are different");
			}
		}
	}//saveConnectionDetails

	/**
	 * Refreshes {@code jListConnectionsID} after an add/edit/soft-delete - always the Active view,
	 * since Save/soft-Delete are only ever reachable from it. Delegates to
	 * {@link #reloadConnectionsList}, which is view-aware and shared with the Reactivate/hard-Delete
	 * actions in the Inactive view.
	 */
	private void reloadConnections(boolean shouldDelete, String id) throws BroadSQLException {
		reloadConnectionsList(id);
	}

	/*
	 *  Test connection
	 */
	private void testConnection() {
		if (jConnId.isVisible()) {
			String selectedId = "";
			if (!jListConnectionsID.isSelectionEmpty()) {
				selectedId = jListConnectionsID.getSelectedValue().toString();
			}
			String visibleId = jConnId.getText();

			if (checkPasswords()) {
				DatabaseDefinition platform = getConnectionFromInput();
				//DatabaseDefinition pl = databaseConnectionsCollection.getDatabaseConnection(platform.getId());
				//log.debug("PlatformID: {}", platform.getId());
				//log.debug("Platform: {}", platform.toString());
				if (connection != null) {
					//StringBuffer result = connection.testConnectionToExistingPlatform(platform.getId());
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
			} else {
				JOptionPane.showMessageDialog(this, "Password and repeated password are different");
			}
		}
	}//saveConnectionDetails


	// Variables declaration - do not modify//GEN-BEGIN:variables
	private javax.swing.JButton jButtonNewConnection;
	private javax.swing.JButton jButtonSaveConnection;
	private javax.swing.JButton jButtonTestConnection;
	private javax.swing.JTextArea jConnComment;
	private javax.swing.JLabel jConnCommentLabel;
	private javax.swing.JScrollPane jConnCommentScroll;
	private javax.swing.JTextField jConnDriver;
	private javax.swing.JLabel jConnDriverLabel;
	private javax.swing.JTextField jConnId;
	private javax.swing.JLabel jConnIdLabel;
	private javax.swing.JLabel jConnGroupLabel;
	private javax.swing.JComboBox jConnGroupList;
	private javax.swing.JLabel jConnEnvironmentLabel;
	private javax.swing.JComboBox jConnEnvironmentList;
	private javax.swing.JTextField jConnName;
	private javax.swing.JLabel jConnNameLabel;
	private javax.swing.JPasswordField jConnPassword;
	private javax.swing.JLabel jConnPasswordLabel;
	private javax.swing.JPasswordField jConnPasswordRetype;
	private javax.swing.JLabel jConnPasswordRetypeLabel;
	private javax.swing.JLabel jConnTypeLabel;
	private javax.swing.JComboBox jConnTypeList;
	private javax.swing.JTextField jConnUrl;
	private javax.swing.JLabel jConnUrlLabel;
	private javax.swing.JTextField jConnUserName;
	private javax.swing.JLabel jConnUserNameLabel;
	private javax.swing.JList jListConnectionsID;
	private JDatabaseGroupsPanel jDatabaseGroupsPanel;
	private JEnvironmentsPanel jEnvironmentsPanel;
	private javax.swing.JTabbedPane jMainTabbedPane;
	private javax.swing.JMenu jMenu1;
	private javax.swing.JMenuBar jMenuBar1;
	private javax.swing.JMenuItem jMenuItem1;
	private javax.swing.JMenuItem jMenuItem2;
	private javax.swing.JMenuItem jMenuItem3;
	private javax.swing.JMenuItem jMenuItem4;
	private javax.swing.JMenuItem jMenuItem5;
	private javax.swing.JMenu jMenuGroups;
	private javax.swing.JMenuItem jMenuItemGroupNew;
	private javax.swing.JMenuItem jMenuItemGroupSave;
	private javax.swing.JMenuItem jMenuItemGroupDelete;
	private javax.swing.JMenu jMenuEnvironments;
	private javax.swing.JMenuItem jMenuItemEnvironmentNew;
	private javax.swing.JMenuItem jMenuItemEnvironmentSave;
	private javax.swing.JMenuItem jMenuItemEnvironmentDelete;
	private javax.swing.JMenu jMenuExit;
	private javax.swing.JPanel jPanel1;
	private javax.swing.JPanel jPanelConnection;
	private javax.swing.JScrollPane jScrollPaneConnections;
	private javax.swing.JSplitPane jSplitPane2;
	private javax.swing.JTabbedPane jTabbedPaneConnection;
	private JUserScriptsPanel jUserScriptsPanel;
	// End of variables declaration//GEN-END:variables
}
