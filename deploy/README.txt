| ==================================================== |
| BroadSQL release 5.4.5, 2008-2026 by UpAndCoding.com |
|                                                      |
| Documentation and latest release available on:       |
| https://www.broadsql.com                             |
| ==================================================== |

Content:
	1. WHAT'S NEW
    2. REQUIREMENTS
	3. INSTALLATION INSTRUCTIONS
	4. GETTING STARTED
	5. CONFIGURATION INSTRUCTIONS (WINDOWS)
	6. CONFIGURATION INSTRUCTIONS (LINUX)
	7. TROUBLESHOOTING / KNOWN BUGS / CHANGE LOG
	8. LICENSING
	9. CREDITS
	10. CONTACT INFORMATION

	
	
1) WHAT'S NEW
-------------
See "What's new" and the release notes on https://www.broadsql.com
	

2) REQUIREMENTS
---------------
Operating system: 
  - Windows OS
  - Linux
Java Version: 21 and above



3) INSTALLATION INSTRUCTIONS
----------------------------
Unzip the file to a location on your disk, say C:\BroadSQL

Folders structure:
C:\BroadSQL
  |- BroadSQL.bat								BAT script (Windows)
  |- BroadSQL.ps1								PowerShell script (Windows)
  |- broadsql.sh								Linux Shell Script (Linux/Unix)
  |- connect.bat								Synonym for BroadSQL.bat (Windows)
  |- conf										Configuration files
     |- BroadSQL.ini							INI file containing all parameters (Windows)
     |- broadsqlux.ini							INI file (Linux/Unix)
     |- ConnectionsDefinitionFile.cdf.mv.db		Connections Definition File (CDF): encrypted file that contains all your database definitions
     \- logback-broadsql.xml					Settings for the log. No reason for modifying this file, except for debugging purpose
  |- drivers									In this folder, add JDBC drivers not included in the distribution (eg. Oracle, MySQL, etc.)
  |- extensions									In this folder, add the JAR file that contains additional commands
  |- lib										This folder contains all required libraries, including the broadsql.jar file
  |- logs										This folder contains the log files
  |- samples									WorldDB.mv.db, the WORLD sample database (CONNECT WORLD;)
  |- jsscripts									Folder of the experimental JS scripts (JavaScript is not part of the Scripts Library)
  \- scripts										The Scripts Library: your reusable scripts, run with @name or LIB RUN name

 

4) GETTING STARTED
------------------
Read the following before starting the configuration:

:: Connection
The default password is : clipper8AD
The user name is : admin


:: The WORLD sample database
BroadSQL includes a sample H2 database, WORLD (countries, cities, languages, currencies, regions).
After login, type: CONNECT WORLD;  then for example: SHOW TABLES;  DESCR COUNTRY;  SELECT * FROM COUNTRY;
It is the file samples/WorldDB.mv.db (user sa, no password). You may modify it freely; to restore it,
replace that file with the one from the BroadSQL download while BroadSQL is not connected to it.


:: What is the Connections Definition File (CDF)?
BroadSQL uses an H2 database for storing the connection settings to your various databases.
This AES encrypted database is commonly known as the CDF file.
Like other databases, the CDF can be accessed through BroadSQL client.
You can perform whatever operations in the CDF database, modify data at your own risks.


:: What are instances and environments?
In addition to the typical parameters of a JDBC connection (url, driver, user name and password), BroadSQL requires an instance and an environment.
- Instances are the products or systems a connection belongs to, e.g. Wiki1, Wiki2, JIRA, MYSAP - they allow you to group different database connections related to the same scope.
- Environments are deployment platforms such as the test & integration, quality acceptance and production platforms

BroadSQL is distributed with the following list of environments that you can adapt:
- DEV		Development Platform
- TI		T&I/Tests and Integration Platform
- QA		Q&A/Internal Acceptance Platform
- RE		Reporting Platform
- LIVE		Production/Live Platform
- STAGE		Stage/Pre-production Platform
- DEMO		Demo Platform

You can add your own instances and environments by creating rows in the tables INSTANCE and ENVIRONMENT of the CDF.


:: Which JDBC drivers are included in the distribution?
JDBC drivers included in the distribution:
- PostgreSQL JDBC Driver version 42.7.13
- H2 version 2.3.232
- HSQLDB version 2.7.4
- SQLite version 3.53.2.1
- Apache Derby version 10.17.1.0 (embedded mode; the network client needs derbyclient.jar in the folder drivers)

In BroadSQL, type command SHOW DRIVERS to get a list of all installed JDBC drivers.


:: How to install additional JDBC drivers?
In addition to the drivers included in the distribution, you can add any JDBC compatible driver by copying the JAR files in the folder drivers.

You can then create a database connection with the corresponding driver. Just make sure the database type is declared in the table TYPE of the CDF.
A connection uses the driver of its database type (TYPE.DRIVER); only a type without a driver uses the connection's own DRIVER.
The default types and their driver classes are the following:
- DB2: com.ibm.db2.jcc.DB2Driver
- DERBY Client: org.apache.derby.client.ClientAutoloadedDriver
- DERBY Embedded: org.apache.derby.iapi.jdbc.AutoloadedDriver
- Firebird: org.firebirdsql.jdbc.FBDriver
- H2: org.h2.Driver
- HSQL: org.hsqldb.jdbc.JDBCDriver
- IDS Server: com.informix.jdbc.IfxDriver
- Informix: com.informix.jdbc.IfxDriver
- MariaDB: org.mariadb.jdbc.Driver
- MySQL: com.mysql.cj.jdbc.Driver
- Oracle: oracle.jdbc.OracleDriver
- PostgreSQL: org.postgresql.Driver
- SQL Server: com.microsoft.sqlserver.jdbc.SQLServerDriver
- SQLite: org.sqlite.JDBC
- Sybase: com.sybase.jdbc42.jdbc.SybDriver
- Teradata: com.teradata.jdbc.TeraDriver
The CDF also keeps legacy types whose products or drivers are discontinued (Cloudscape, InstantDB, Intersys,
JDBC-ODBC Bridge, Pointbase): no usable driver is known for them.

You can adapt your own types by inserting rows in the table TYPE, the format is self explanatory.


:: How to manage connections in the CDF ?
Type command CONFIG. A GUI will let you manage the connections.


:: What are the fields of a Connection Definition?
A connection is defined by the following characteristics:
- ID: this is the identifier you will use for connecting with the CONNECT command
      For example, CONNECT PSOFT;
	  The identifier of the CDF database is $CDF.
	  At most 15 characters. IDs are case-insensitive: PSOFT and psoft are the same connection,
	  and an inactive connection keeps its ID.
- NAME: is a free text description of the connection
- TYPE_ID: used for determining the database type (H2, PostgreSQL, Oracle, etc.)
           This ID must match an entry from the TYPE table
- URL: the JDBC url used for connecting the database
- USER_NAME: the user name
- USER_PASSWORD: the user password
- INSTANCE_ID: must match a value in the table INSTANCE
- ENVIRONMENT_ID: must match a value in the table ENVIRONMENT
- STATUS_ID: can be ACTIVE or INACTIVE. Use the value 'INACTIVE' to keep a trace of unused connections (soft delete)
- COMMENT: self explanatory, isnt'it?


:: How does BroadSQL work?
When connected to BroadSQL, you type a command that must end with ;
Specific commands are interpreted by BroadSQL and other SQL commands are send directly to the database.
You will need to know the following commands in order to survive:
CONNECT
DISCONNECT
EXIT
HELP

Type HELP followed by a command name to get specific instructions.


		
5) CONFIGURATION INSTRUCTIONS (WINDOWS)
---------------------------------------
1. Edit the BroadSQL.ini file to specify your settings (optional, the default options are enough)

2. From a Windows command line, start BroadSQL by typing: 
   CONNECT.BAT (or BroadSQL.BAT)
   You can specify a database definition ID as an argument
   
   - OR -
   
   From a PowerShell windows, type the following:
   cmd.exe /c connect.bat
   
   - OR -
   
   From Windows Explorer, right click the file BroadSQL.ps1 and select "Run with PowerShell"

3. Login using the default password: clipper8AD
   You can later change the password by using command SET MASTER PASSWORD;

4. To add database definitions, use command CONFIG;



6) CONFIGURATION INSTRUCTIONS (LINUX)
-------------------------------------
1. Install Java 21 or later. broadsql.sh uses JAVA_HOME/bin/java when JAVA_HOME is set, otherwise the java
   command found on the PATH, and refuses to start with an older Java.

2. Start BroadSQL by typing, from the installation folder:
./broadsql.sh
   or, from any folder, the full path of the script, e.g. /home/myuser/broadsql/broadsql.sh
   (the script always runs from its own installation folder). You can specify a database definition ID
   as an argument, e.g. ./broadsql.sh WORLD
   If the script is not executable (an unzip tool that does not keep file permissions), type once:
chmod +x broadsql.sh

3. Login using the default password: clipper8AD
   You can later change the password by using command SET MASTER PASSWORD;

4. Edit conf/broadsqlux.ini to specify your settings. A relative folder is relative to the installation
   folder. Set at least DefaultFolder, the folder DUMP writes its files to: the shipped value
   /home/myuser/broadsql/temp is an example to replace with an existing folder of your own.
   The activity log is written to the logs folder of the installation (LogFolderName=logs), and extension
   JAR files are read from its extensions folder (CustomExtensionsFolder=extensions).

5. broadsql.sh starts Java with UTF-8 as its default file encoding.

6. The command CONFIG (the graphical connection-management screen) does not work in Linux (at
   least via Putty) - it is Windows only. Manage connections, Database Groups and Environments
   from the command line instead:
   * Connections: ADD CONNECTION, EDIT CONNECTION, DEL CONNECTION, DUPLICATE CONNECTION,
     REACTIVATE CONNECTION, SHOW ALL CONNECTIONS, SHOW INACTIVE CONNECTIONS, SHOW CONNECTION
   * Database Groups: ADD GROUP, EDIT GROUP, DEL GROUP, SHOW ALL GROUPS
   * Environments: ADD ENVIRONMENT, EDIT ENVIRONMENT, DEL ENVIRONMENT, SHOW ALL ENVIRONMENTS
   * Login scripts (SQL run automatically on connect): ADD LOGIN SCRIPT LINE, EDIT LOGIN SCRIPT
     LINE, DEL LOGIN SCRIPT LINE, MOVE LOGIN SCRIPT LINE, SHOW LOGIN SCRIPT
   Type HELP <command> at the BroadSQL prompt for the exact syntax of any of these.

  
  
7) TROUBLESHOOTING / KNOWN BUGS / CHANGE LOG
--------------------------------------------
See https://www.broadsql.com



8) LICENSING
------------
Licence model: Apache 2.0


9) CREDITS
----------
BroadSQL is developed and maintained by UpAndCoding.com
Third party software and libraries are developped by their own contributors.


10) CONTACT INFORMATION
----------------------
See https://www.broadsql.com