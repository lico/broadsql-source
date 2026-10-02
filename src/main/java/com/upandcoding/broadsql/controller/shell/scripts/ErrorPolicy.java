package com.upandcoding.broadsql.controller.shell.scripts;

/** SPRINT 0110A: {@code ON ERROR STOP | CONTINUE} (spec section 12); every top-level run starts with {@link #CONTINUE}. */
public enum ErrorPolicy {
	STOP, CONTINUE
}
