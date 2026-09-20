package com.upandcoding.broadsql.controller.shell;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.output.ShellConsole;
import com.upandcoding.broadsql.dao.DatabaseConnection;
import com.upandcoding.broadsql.dao.DatabaseDefinitionsVault;

public class Session {
	
	private static final Logger log = LoggerFactory.getLogger(Session.class);

    @Autowired
    ConsoleSettings consoleSettings;

    @Autowired
    DatabaseDefinitionsVault databaseConnectionsCollection;

    @Autowired
    DatabaseConnection currentDatabase;

    @Autowired
    ShellConsole console;

    public static int SESSION_DURATION = 10 * 60 * 60 * 1000; // 5 hours, Time of the login session, in milliseconds

    private String id;
    private Date startDateTime = null;
    private Date lastCheckedDate = null;
    private static final int MAX_ATTEMPTS = 4;

    private String userName = null;
    private String password = null;
    private String serverName = null;
    private int serverPort = 5395;
    private int nbAttempts = 1;

    public Session(DatabaseDefinitionsVault databaseConnectionsCollection, ConsoleSettings consoleSettings) throws BroadSQLException {
        this(databaseConnectionsCollection, consoleSettings, null, null);
    }

    public Session(DatabaseDefinitionsVault databaseConnectionsCollection, ConsoleSettings consoleSettings, String userName, String password) throws BroadSQLException {
        this.consoleSettings = consoleSettings;
        this.databaseConnectionsCollection = databaseConnectionsCollection;
        this.id = UUID.randomUUID().toString();
        this.startDateTime = new Date();
        this.lastCheckedDate = this.startDateTime;
        this.userName = userName;
        this.password = password;
        if (this.password != null) {
            loadPlatformsDefinitionFile();
        }
    }

    public boolean isActive() {
        boolean result = false;
        Date currentPeriodOfActivity = new Date();
        long pi = this.lastCheckedDate.getTime();
        long pf = currentPeriodOfActivity.getTime();
        if ((pf - pi) > SESSION_DURATION) {
            result = false;
            this.password = null;
        } else {
            result = true;
            this.lastCheckedDate = new Date();
        }
        return (result);
    }

    public Session(String serverName, int serverPort, String userName, String password) throws BroadSQLException {
        this.serverName = serverName;
        this.serverPort = serverPort;
        this.userName = userName;
        this.password = password;
        if (this.password != null) {
            loadPlatformsDefinitionFile();
        }
    }

    private void loadPlatformsDefinitionFile() throws BroadSQLException {
        databaseConnectionsCollection.load();
    }

    /*
     * Returns a Database object. 
     * Difference with getDatabase: a connect() operation is done
     */
    public void openDatabase(String dbId) throws BroadSQLException {
        if (dbId == null || !databaseConnectionsCollection.contains(dbId)) {
            throw new BroadSQLException("Platform " + dbId + " is not defined in the platforms definition file");
        } else {
            currentDatabase.setPlatformCode(dbId);
            currentDatabase.connect();
        }
        currentDatabase.setToScreen(false);
        currentDatabase.setFileName(null);
    }

    public List<String> getCurrentUserLoginScript(String dbId, DatabaseConnection db) throws BroadSQLException {
        return databaseConnectionsCollection.getUserLoginScript(consoleSettings.getProtectedPlatformsFileName(), password, dbId);
    }

    public void authenticate(ShellConsole console) throws BroadSQLException {
        boolean authenticated = false;
        while (!authenticated) {
            String pwd = console.readPassword();
            this.password = pwd;
            authenticated = this.validateAuthentification(pwd);
        }
    }

    public void authenticate(String password) throws BroadSQLException {
        this.validateAuthentification(password);
    }

    private boolean validateAuthentification(String password) throws BroadSQLException {
        boolean authenticated = false;
        if (StringUtils.isNotBlank(password)) {
            try {
                //PlatformsCollectionZIP platformsCollection = PlatformsCollectionZIP.getInstance();
                //log.debug("Server file type: "+serverFileType);
                //log.debug("Server file name: "+platformsDefinitionFileName);
                //log.debug("Password: "+databaseConnectionsCollection.getPassword());
                databaseConnectionsCollection.setPassword(password);
                if (databaseConnectionsCollection.isValidPassword()) {
                    authenticated = true;
                    this.setPassword(this.password);
                    this.lastCheckedDate = new Date();
                    this.startDateTime = new Date();
                    nbAttempts = 1;
                    databaseConnectionsCollection.load();
                }
                nbAttempts++;
            } catch (IOException ie) {
                throw new BroadSQLException("Unable to open the platforms definition file");
            }
        }
        if (nbAttempts >= MAX_ATTEMPTS && !authenticated) {
            BroadSQLException ce = new BroadSQLException("Too many attempts, exiting application");
            throw ce;
        }
        return (authenticated);
    }

    public void close() throws BroadSQLException {
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) throws IOException, FileNotFoundException, BroadSQLException {
        this.password = password;
        if (this.password != null) {
            loadPlatformsDefinitionFile();
        }
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public Date getLastCheckedDate() {
        return lastCheckedDate;
    }

    public Date getStartDateTime() {
        return startDateTime;
    }

    public static int getSessionDuration() {
        return SESSION_DURATION;
    }
}
