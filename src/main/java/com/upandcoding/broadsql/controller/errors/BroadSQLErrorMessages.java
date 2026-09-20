package com.upandcoding.broadsql.controller.errors;

public class BroadSQLErrorMessages {

	public static final String ERR_GAL_01 = "You must provide a valid table name";
	public static final String ERR_GAL_02 = "You must provide a valid view name";
	public static final String ERR_GAL_03 = "You must provide a valid schema name";
	
	public static final String ERR_CONN_01 = "You must provide a valid database connection ID";
	public static final String ERR_CONN_02 = "A database connection already exists with this ID";
	public static final String ERR_CONN_03_SUFFIX = "' is inactive. Reactivate it with REACTIVATE CONNECTION, or from the Connections config screen, to use it";
	public static final String ERR_CONN_04 = "Cannot find this ID, it does not exist";
	
	public static final String ERR_LIB_01 = "Invalid path to the SQL Library";
	public static final String ERR_SCRIPTS_01 = "Invalid path to the scripts catalog";
	public static final String ERR_JSSCRIPTS_01 = "Invalid path to the JS scripts catalog";

	public static final String ERR_GROUP_01 = "You must provide a valid Database Group ID";
	public static final String ERR_GROUP_02 = "A Database Group already exists with this ID";
	public static final String ERR_GROUP_04 = "Cannot find this Database Group ID, it does not exist";

	public static final String ERR_ENV_01 = "You must provide a valid Environment ID";
	public static final String ERR_ENV_02 = "An Environment already exists with this ID";
	public static final String ERR_ENV_04 = "Cannot find this Environment ID, it does not exist";

	public static final String ERR_LOGINSCRIPT_01 = "You must provide a valid connection ID";
	public static final String ERR_LOGINSCRIPT_02 = "You must provide a valid line number";
}
