package com.upandcoding.broadsql.controller.shell.commands.core.config;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.controller.shell.commands.Command;

/**
 * <b>Disabled (see docs/TECHNICAL_CHANGE.md, 10/09/2026).</b> {@link #execute} always refuses to run,
 * and this command is hidden from {@code HELP} and the generated command reference - the one-time
 * correction it used to apply is no longer offered to users. The class, and the underlying vault
 * methods it used to call, are kept in place (not deleted) in case they are needed again.
 *
 * <p>One-time correction for a CDF created before this fix: {@code CONNECTIONS.INSTANCE_ID}/
 * {@code ENVIRONMENT_ID}, and the {@code INSTANCE}/{@code ENVIRONMENT} reference tables, have always
 * held each other's data - every connection's "instance" value is actually a deployment stage (e.g.
 * {@code DEV}, {@code QA}, {@code PROD}) and its "environment" value is actually a product/application
 * name (e.g. {@code DESK}, {@code SALES}). See docs/TECHNICAL_CHANGE.md, 2026-09-05, "Instance/
 * Environment data swap" for the full investigation.
 *
 * <p>{@code FIX INSTANCE ENVIRONMENT SWAP} used to take a timestamped backup of the CDF's data file
 * first ({@link com.upandcoding.broadsql.dao.DatabaseDefinitionsVault#backupCdfFile}), then physically swap
 * both - nothing created, deleted, or reinterpreted, only relocated to the column/table whose name
 * actually matches what it holds ({@link com.upandcoding.broadsql.dao.DatabaseDefinitionsVault#swapInstanceAndEnvironment}).
 *
 * <p><b>Was meant to run exactly once per CDF.</b> There is no way to detect automatically whether it
 * has already been applied - both {@code INSTANCE_ID}/{@code ENVIRONMENT_ID} and {@code INSTANCE}/
 * {@code ENVIRONMENT} exist before and after, just with contents exchanged. Running it a second time
 * on an already-corrected CDF would have silently swapped the data right back to wrong.
 */
public class CommandFixInstanceEnvironmentSwap extends Command {

	public CommandFixInstanceEnvironmentSwap() {
		super("FIX INSTANCE ENVIRONMENT SWAP", "FIX IE", "FIXIE");
		this.hidden = true;
	}

	@Override
	public void execute(String query) throws BroadSQLException {
		throw new BroadSQLException("FIX INSTANCE ENVIRONMENT SWAP has been disabled and can no longer be run.");
	}

	@Override
	public String getDescription() {
		return ("Disabled - always refuses to run. Used to be a one-time fix swapping CONNECTIONS.INSTANCE_ID/"
				+ "ENVIRONMENT_ID and the INSTANCE/ENVIRONMENT reference tables.");
	}

	@Override
	public String getArguments() {
		return "(none)";
	}

	@Override
	public String getExamples() {
		return "(disabled - no longer runnable)";
	}
}
