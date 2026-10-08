package com.upandcoding.broadsql.dao.load;

/**
 * Renders the human-readable preflight summary shown both by {@code LOAD ... PREVIEW} and, before
 * asking for confirmation, by a plain interactive {@code LOAD} - see SPRINT 0912B section 4.2/4.3.
 * A separate, pure-string method (rather than printing directly) so it can be unit-tested without a
 * console.
 */
public final class LoadPreviewRenderer {

	private static final int MAX_ISSUES_SHOWN = 25;
	private static final String RULE = "------------------------------------------------------------";

	private LoadPreviewRenderer() {
	}

	public static String render(LoadPlan plan, String connectionId) {
		StringBuilder out = new StringBuilder();
		out.append("LOAD PREVIEW\n").append(RULE).append('\n');
		out.append("Target      : ").append(connectionId != null ? connectionId + " / " : "").append(plan.getTarget().getQualifiedName()).append('\n');
		out.append("Mode        : INSERT\n");
		out.append("Source      : ").append(plan.getSourceFile()).append('\n');
		out.append("Input rows  : ").append(plan.getSourceRowCount()).append('\n');
		out.append("Valid rows  : ").append(plan.getValidRowCount()).append('\n');
		out.append("Rejected    : ").append(plan.getRejectedRowCount()).append('\n');

		if (!plan.getUnmappedHeaders().isEmpty()) {
			out.append('\n').append("Unknown source column(s) - not present in ").append(plan.getTarget().getQualifiedName()).append(":\n");
			for (String header : plan.getUnmappedHeaders()) {
				out.append("  ").append(header).append('\n');
			}
		} else {
			out.append('\n').append("Columns:\n");
			for (String column : plan.getInsertColumns()) {
				LoadTargetColumn targetColumn = plan.getTarget().findColumn(column);
				out.append(column).append(" -> ").append(column)
						.append("  ").append(targetColumn != null ? targetColumn.getTypeName() : "?").append('\n');
			}
		}

		if (!plan.getIssues().isEmpty()) {
			out.append('\n');
			int shown = Math.min(MAX_ISSUES_SHOWN, plan.getIssues().size());
			for (int i = 0; i < shown; i++) {
				out.append(plan.getIssues().get(i)).append('\n');
			}
			if (plan.getIssues().size() > shown) {
				out.append("... and ").append(plan.getIssues().size() - shown).append(" more rejected row(s)\n");
			}
		}

		out.append('\n').append("No changes have been made.");
		return out.toString();
	}
}
