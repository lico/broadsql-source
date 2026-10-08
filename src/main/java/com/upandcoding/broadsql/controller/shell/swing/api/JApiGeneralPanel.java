package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.format.DateTimeFormatter;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.upandcoding.broadsql.controller.shell.swing.api.form.ApiGeneralFormModel;
import com.upandcoding.broadsql.dao.api.model.ApiImportSource;

/**
 * {@code CONFIG API}'s "General" tab - docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno
 * YAML Round-trip.md, section 8: Name, Description, Source/origin (informational, read-only - "Manual"
 * or "Bruno YAML" plus the imported file path and last-import timestamp when applicable), and a
 * read-only Endpoints/Folders/Environments summary. Thin Swing shell over {@link ApiGeneralFormModel}.
 */
public class JApiGeneralPanel extends JPanel {

	private JTextField idField;
	private JTextField nameField;
	private JTextArea descriptionArea;
	private JLabel sourceLabel;
	private JLabel summaryLabel;

	public JApiGeneralPanel() {
		buildUi();
	}

	private void buildUi() {
		setLayout(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(6, 8, 6, 8);
		c.fill = GridBagConstraints.HORIZONTAL;

		idField = new JTextField(20);
		nameField = new JTextField(30);
		descriptionArea = new JTextArea(4, 30);
		descriptionArea.setLineWrap(true);
		descriptionArea.setWrapStyleWord(true);
		sourceLabel = new JLabel(" ");
		summaryLabel = new JLabel(" ");

		int row = 0;
		c.gridx = 0;
		c.gridy = row;
		add(new JLabel("ID"), c);
		c.gridx = 1;
		c.weightx = 1;
		add(idField, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		add(new JLabel("Name"), c);
		c.gridx = 1;
		c.weightx = 1;
		add(nameField, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		c.anchor = GridBagConstraints.NORTHWEST;
		add(new JLabel("Description"), c);
		c.gridx = 1;
		c.weightx = 1;
		c.anchor = GridBagConstraints.WEST;
		add(new JScrollPane(descriptionArea), c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		add(new JLabel("Source / origin"), c);
		c.gridx = 1;
		c.weightx = 1;
		add(sourceLabel, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		add(new JLabel("Summary"), c);
		c.gridx = 1;
		c.weightx = 1;
		add(summaryLabel, c);
	}

	public void setModel(ApiGeneralFormModel model, boolean isNewApi) {
		idField.setText(model.getId());
		idField.setEditable(isNewApi);
		nameField.setText(model.getName());
		descriptionArea.setText(model.getDescription());
	}

	public ApiGeneralFormModel getModel(ApiGeneralFormModel model) {
		model.setId(idField.getText());
		model.setName(nameField.getText());
		model.setDescription(descriptionArea.getText());
		return model;
	}

	/** A deterministic snapshot of the editable fields (not {@code idField} - immutable for an existing API) - the value-snapshot dirty-tracking building block (API Quality and UX Consolidation sprint, Phase 2). */
	public String snapshotKey() {
		return nameField.getText() + "|" + descriptionArea.getText();
	}

	/** {@code importSource} may be {@code null} for a manually-created API - shown as "Manual" per section 8. */
	public void setImportSource(ApiImportSource importSource) {
		if (importSource == null) {
			sourceLabel.setText("Manual");
			return;
		}
		String lastImported = importSource.getLastImportedAt() == null ? "unknown"
				: importSource.getLastImportedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		sourceLabel.setText("<html>Bruno YAML<br>Imported from: " + escape(importSource.getSourceLocation())
				+ "<br>Last import: " + lastImported + "</html>");
	}

	public void setSummary(int endpointCount, int folderCount, int environmentCount) {
		summaryLabel.setText("<html>Endpoints: " + endpointCount + "<br>Folders: " + folderCount + "<br>Environments: " + environmentCount + "</html>");
	}

	private String escape(String value) {
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
