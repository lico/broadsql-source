package com.upandcoding.broadsql.controller.shell.output;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;

public class ConsoleWindows {

	public interface CLibrary extends Library {

		CLibrary INSTANCE = (CLibrary) Native.load(Platform.isWindows() ? "kernel32" : "c", CLibrary.class);

		boolean SetConsoleTitleA(String title);

		String GetConsoleTitleA();
	}

	public static void setWindowTitle(String title) {
		if (Platform.isWindows()) {
			CLibrary.INSTANCE.SetConsoleTitleA(title);
		}
	}
}
