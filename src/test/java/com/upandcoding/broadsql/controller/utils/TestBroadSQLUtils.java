package com.upandcoding.broadsql.controller.utils;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.shell.commands.CommandUtils;
import com.upandcoding.broadsql.controller.shell.output.ConsoleUtils;

//@ExtendWith(SpringExtension.class)
//@ContextConfiguration(classes = TestBroadSQLUtils.class, loader = AnnotationConfigContextLoader.class)
public class TestBroadSQLUtils {

	@BeforeEach
	public void setUp() { // Note: It is not required to call this setUp()
		// ...
	}

	@Test
	public void testSplitPreserveQuotes() {
		String test1 = "hello";
		String[] result = CommandUtils.splitPreserveQuotes(test1, " ");
		Assertions.assertEquals(test1, result[0]);

		test1 = "hello world";
		result = CommandUtils.splitPreserveQuotes(test1, " ");
		Assertions.assertEquals("hello", result[0]);
		Assertions.assertEquals("world", result[1]);

		test1 = "\"don't split this\"";
		result = CommandUtils.splitPreserveQuotes(test1, " ");
		Assertions.assertEquals(test1, result[0]);

		test1 = "\"don't split this\" hello world";
		result = CommandUtils.splitPreserveQuotes(test1, " ");
		Assertions.assertEquals("\"don't split this\"", result[0]);
		Assertions.assertEquals("hello", result[1]);
		Assertions.assertEquals("world", result[2]);
	}

	@Test
	public void testGetArgumentsFromQuery() {
		System.out.println("*** ----------------------------------");
		String[] keywords = { "SHOW COLUMN" };
		String[] results = CommandUtils.getArgumentsFromQuery("SHOW COLUMN TOTO", keywords);
		System.out.println("results=" + results[0]);

		System.out.println("*** ----------------------------------");
		String[] keywords2 = { "DESCR", "DESC" };
		String[] results2 = CommandUtils.getArgumentsFromQuery("descr public.user_mapping", keywords2);
		System.out.println("results=" + results2[0]);
		
		System.out.println("*** ----------------------------------");
		String[] results22 = CommandUtils.getArgumentsFromQuery("DESCR user_mapping", keywords2);
		System.out.println("results=" + results22[0]);

		System.out.println("*** ----------------------------------");
		String[] keywords3 = { "SHOW TABLES", "SH TA", "SHTA" };
		String[] results31 = CommandUtils.getArgumentsFromQuery("show tables", keywords3);
		if (results31 != null && results31.length>0) {
			System.out.println("results=" + results31[0]);
		} else {
			System.out.println("No argument");
		}
		
		System.out.println("*** ----------------------------------");
		String[] results32 = CommandUtils.getArgumentsFromQuery("SHTA", keywords3);
		if (results32 != null && results32.length>0) {
			System.out.println("results=" + results32[0]);
		} else {
			System.out.println("No argument");
		}
		
		System.out.println("*** ----------------------------------");
		String[] results33 = CommandUtils.getArgumentsFromQuery("sh ta", keywords3);
		if (results33 != null && results33.length>0) {
			System.out.println("results=" + results33[0]);
		} else {
			System.out.println("No argument");
		}
		
		System.out.println("*** ----------------------------------");
		String[] results34 = CommandUtils.getArgumentsFromQuery("show tables USE", keywords3);
		if (results34 != null && results34.length>0) {
			System.out.println("results=" + results34[0]);
		} else {
			System.out.println("No argument");
		}
		
		System.out.println("*** ----------------------------------");
		String[] results35 = CommandUtils.getArgumentsFromQuery("shta PUBLIC.TOTO", keywords3);
		if (results35 != null && results35.length>0) {
			System.out.println("results=" + results35[0]);
		} else {
			System.out.println("No argument");
		}
		/*
		String[] keywords2 = {"SHOW TABLES", "SH TA", "SHTA"};
		results = CommandUtils.getArgumentsFromQuery("SHOW TABLES", keywords2);
		System.out.println("results=" + results[0]);
		results = CommandUtils.getArgumentsFromQuery("SH TA", keywords2);
		System.out.println("results=" + results[0]);
		results = CommandUtils.getArgumentsFromQuery("SH TABLES", keywords2);
		System.out.println("results=" + results[0]);
		*/
	}

	@Test
	public void testQuery() {
		String[] keywords = { "LOAD", "LO" };
		String query = "load toto test load_test.csv";
		if (StringUtils.isNotBlank(query) && keywords != null && keywords.length > 0) {
			query = query.toUpperCase();
			System.out.println("query=" + query);
			for (String kword : keywords) {
				System.out.println("kword=" + kword);
				if (StringUtils.startsWith(query, kword)) {
					String endQuery = StringUtils.substringAfter(query, kword + " ");
					System.out.println("Endquery: " + endQuery);
				}
			}
		} else {
			System.out.println("No keywords");
		}
	}

	@Test
	public void testGetTitleAndVersion() {
		System.out.println(ConsoleUtils.getTitleAndVersion());
	}

	@Test
	public void testToSingleLine() {
		Assertions.assertEquals("SELECT * FROM CUSTOMER",
				CommandUtils.toSingleLine("SELECT *\nFROM CUSTOMER"));
		Assertions.assertEquals("SELECT 1 WHERE X = 2",
				CommandUtils.toSingleLine("  SELECT 1\n   WHERE X = 2  \n"));
		Assertions.assertEquals("SELECT 1", CommandUtils.toSingleLine("SELECT 1"));
		Assertions.assertNull(CommandUtils.toSingleLine(null));
	}

}
