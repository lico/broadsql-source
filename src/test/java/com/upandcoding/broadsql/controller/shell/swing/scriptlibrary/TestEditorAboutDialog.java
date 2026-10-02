package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.upandcoding.broadsql.controller.config.SpringPropertiesConfig;

/** The About dialog is version, copyright and a link to the documentation home page only. The text/target are static so they are testable without a display. */
class TestEditorAboutDialog {

	@Test
	void showsTheCurrentVersionFromTheAuthoritativeSource() {
		Assertions.assertEquals("BroadSQL " + SpringPropertiesConfig.APP_VERSION_NUMBER, EditorAboutDialog.versionLine());
	}

	@Test
	void showsTheCopyright() {
		Assertions.assertEquals("© UpAndCoding.com", EditorAboutDialog.copyrightLine());
	}

	@Test
	void linksToTheDocumentationHomePageOnTheAuthoritativeSiteNotAPage() {
		Assertions.assertEquals(SpringPropertiesConfig.APP_SITE + "/docs/", EditorAboutDialog.documentationUrl());
		Assertions.assertEquals("https://www.broadsql.com/docs/", EditorAboutDialog.documentationUrl());
		Assertions.assertFalse(EditorAboutDialog.documentationUrl().contains("script"), "the home page, not the Editor page");
		Assertions.assertEquals("Documentation", EditorAboutDialog.LINK_TEXT);
	}

	@Test
	void hasNoExplanatoryProductParagraph() throws Exception {
		String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/upandcoding/broadsql/controller/shell/swing/scriptlibrary/EditorAboutDialog.java"));
		Assertions.assertFalse(source.contains("manages SQL Library"), "the promotional paragraph is gone");
		String frame = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/upandcoding/broadsql/controller/shell/swing/scriptlibrary/ScriptLibraryFrame.java"));
		Assertions.assertFalse(frame.contains("format, validate, run, and keep automatic version history"), "the old About text is gone from the frame");
		Assertions.assertTrue(frame.contains("EditorAboutDialog.show"));
		Assertions.assertTrue(frame.contains("\"BroadSQL Editor\""), "window title");
	}
}
