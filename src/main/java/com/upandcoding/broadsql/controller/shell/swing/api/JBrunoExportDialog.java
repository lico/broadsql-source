package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionExporter;
import com.upandcoding.broadsql.dao.api.bruno.BrunoExportOptions;
import com.upandcoding.broadsql.dao.api.bruno.BrunoExportResult;
import com.upandcoding.broadsql.dao.api.model.ApiEnvironment;
import com.upandcoding.broadsql.dao.model.DatabaseDefinition;

/**
 * {@code Export Bruno YAML} modal dialog - docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno
 * YAML Round-trip.md, sections 35-38: destination file, environment selection scope (default: all -
 * section 37), {@code Include secret values} (default off, with a mandatory confirmation when turned on -
 * section 36), and a post-export warning listing anything that could not be represented exactly
 * (section 38, {@link BrunoExportResult#getUnrepresentableAuth()}).
 */
public class JBrunoExportDialog extends JDialog {

	private final ApiDefinitionsVault vault;
	private final String apiId;

	private JTextField fileField;
	private final Map<Integer, JCheckBox> environmentCheckboxes = new LinkedHashMap<>();
	private JCheckBox includeSecretsCheckBox;

	public JBrunoExportDialog(JFrame owner, ApiDefinitionsVault vault, String apiId) {
		super(owner, "Export Bruno YAML", true);
		this.vault = vault;
		this.apiId = apiId;
		buildUi();
	}

	private void buildUi() {
		setLayout(new BorderLayout());
		JPanel top = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(6, 6, 6, 6);
		c.fill = GridBagConstraints.HORIZONTAL;

		fileField = new JTextField(35);
		JButton browseButton = new JButton("Browse...");
		browseButton.addActionListener(e -> chooseFile());
		c.gridx = 0;
		c.gridy = 0;
		top.add(new JLabel("File"), c);
		c.gridx = 1;
		c.weightx = 1;
		top.add(fileField, c);
		c.gridx = 2;
		c.weightx = 0;
		top.add(browseButton, c);
		add(top, BorderLayout.NORTH);

		JPanel envPanel = new JPanel(new GridLayout(0, 1));
		envPanel.setBorder(BorderFactory.createTitledBorder("Environments"));
		try {
			for (ApiEnvironment env : vault.getEnvironmentsForApi(apiId)) {
				if (DatabaseDefinition.STATUS_ACTIVE.equalsIgnoreCase(env.getStatusId())) {
					JCheckBox checkBox = new JCheckBox(env.getName(), true);
					environmentCheckboxes.put(env.getId(), checkBox);
					envPanel.add(checkBox);
				}
			}
		} catch (BroadSQLException ex) {
			// Best-effort: an empty environment list still lets the API/folder/endpoint structure export.
		}
		add(envPanel, BorderLayout.CENTER);

		JPanel bottom = new JPanel(new BorderLayout());
		includeSecretsCheckBox = new JCheckBox("Include secret values", false);
		bottom.add(includeSecretsCheckBox, BorderLayout.NORTH);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton exportButton = new JButton("Export");
		exportButton.addActionListener(e -> doExport());
		JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(e -> dispose());
		buttons.add(exportButton);
		buttons.add(cancelButton);
		bottom.add(buttons, BorderLayout.SOUTH);
		add(bottom, BorderLayout.SOUTH);

		pack();
	}

	private void chooseFile() {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileFilter(new FileNameExtensionFilter("Bruno/OpenCollection YAML", "yml", "yaml"));
		chooser.setSelectedFile(new File(apiId.toLowerCase() + ".yml"));
		if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
			fileField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void doExport() {
		if (fileField.getText().isBlank()) {
			JOptionPane.showMessageDialog(this, "Choose a destination file first.");
			return;
		}
		BrunoExportOptions options = new BrunoExportOptions();
		if (includeSecretsCheckBox.isSelected()) {
			int confirm = JOptionPane.showConfirmDialog(this,
					"This export may contain API keys, passwords, client secrets or tokens in plaintext.\n\nContinue?",
					"Include secret values", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (confirm != JOptionPane.YES_OPTION) {
				return;
			}
			options.setIncludeSecrets(true);
		}
		Set<Integer> selectedEnvironmentIds = new HashSet<>();
		for (Map.Entry<Integer, JCheckBox> entry : environmentCheckboxes.entrySet()) {
			if (entry.getValue().isSelected()) {
				selectedEnvironmentIds.add(entry.getKey());
			}
		}
		if (selectedEnvironmentIds.size() < environmentCheckboxes.size()) {
			options.setEnvironmentIds(selectedEnvironmentIds);
		} // else: leave null - every environment (section 37's default)

		try {
			BrunoExportResult result = new BrunoCollectionExporter(vault).exportToFile(apiId, new File(fileField.getText()), options);
			if (result.hasWarnings()) {
				StringBuilder sb = new StringBuilder("Export completed with warnings:\n\n");
				result.getUnrepresentableAuth().forEach(w -> sb.append(w).append("\n"));
				JOptionPane.showMessageDialog(this, sb.toString(), "Export warning", JOptionPane.WARNING_MESSAGE);
			} else {
				JOptionPane.showMessageDialog(this, "Export completed.");
			}
			dispose();
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}
}
