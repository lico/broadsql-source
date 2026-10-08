package com.upandcoding.broadsql.controller.shell.swing.api;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.yaml.snakeyaml.Yaml;

import com.upandcoding.broadsql.controller.errors.BroadSQLException;
import com.upandcoding.broadsql.dao.api.ApiDefinitionsVault;
import com.upandcoding.broadsql.dao.api.bruno.BrunoCollectionImporter;
import com.upandcoding.broadsql.dao.api.bruno.BrunoImportResult;

/**
 * {@code Import Bruno YAML} modal dialog - docs/SPRINT XT02-sub sprint 5 - API Configuration GUI + Bruno
 * YAML Round-trip.md, sections 31-34: choose file, show a concise preview (object counts, new-API vs.
 * re-import/update), then Import.
 *
 * <p>The preview walks the raw YAML with a fresh {@link Yaml#load} independently of
 * {@link BrunoCollectionImporter} (which has no "count only, do not persist" mode) - a small, self
 * contained duplication of the generic item-type walk, acceptable since it never writes anything and
 * only needs to answer "how many of what," not resolve full import identity/semantics.
 *
 * <p>Section 34 ("Import and unsaved GUI edits... Save or discard the current changes before importing")
 * is satisfied structurally rather than by an explicit dirty check here: every other tab in
 * {@code JApiSettingsFrame} persists immediately on its own actions, so there is nothing "unsaved" by the
 * time this dialog can be opened except the lightweight General/Authentication/Variables &amp; Headers
 * tabs, which importing an <em>existing</em> API's Bruno file does not touch at all (only environments/
 * folders/endpoints), and importing a <em>new</em> API ID cannot conflict with anything currently being
 * edited.
 */
public class JBrunoImportDialog extends JDialog {

	private final ApiDefinitionsVault vault;
	private String importedApiId;

	private JTextField fileField;
	private JTextField apiIdField;
	private JTextArea previewArea;
	private JButton importButton;

	public JBrunoImportDialog(JFrame owner, ApiDefinitionsVault vault) {
		super(owner, "Import Bruno YAML", true);
		this.vault = vault;
		buildUi();
	}

	public String getImportedApiId() {
		return importedApiId;
	}

	private void buildUi() {
		setLayout(new BorderLayout());
		JPanel form = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(6, 6, 6, 6);
		c.fill = GridBagConstraints.HORIZONTAL;

		fileField = new JTextField(35);
		fileField.setEditable(false);
		JButton browseButton = new JButton("Choose YAML file...");
		browseButton.addActionListener(e -> chooseFile());
		apiIdField = new JTextField(20);

		c.gridx = 0;
		c.gridy = 0;
		form.add(new JLabel("File"), c);
		c.gridx = 1;
		c.weightx = 1;
		form.add(fileField, c);
		c.gridx = 2;
		c.weightx = 0;
		form.add(browseButton, c);

		c.gridx = 0;
		c.gridy = 1;
		form.add(new JLabel("API ID"), c);
		c.gridx = 1;
		c.gridwidth = 2;
		form.add(apiIdField, c);
		c.gridwidth = 1;

		add(form, BorderLayout.NORTH);

		previewArea = new JTextArea(14, 50);
		previewArea.setEditable(false);
		add(new JScrollPane(previewArea), BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		importButton = new JButton("Import");
		importButton.setEnabled(false);
		importButton.addActionListener(e -> doImport());
		JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(e -> dispose());
		buttons.add(importButton);
		buttons.add(cancelButton);
		add(buttons, BorderLayout.SOUTH);

		pack();
	}

	private void chooseFile() {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileFilter(new FileNameExtensionFilter("Bruno/OpenCollection YAML", "yml", "yaml"));
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			File file = chooser.getSelectedFile();
			fileField.setText(file.getAbsolutePath());
			if (apiIdField.getText().isBlank()) {
				apiIdField.setText(deriveId(file.getName()));
			}
			showPreview(file);
		}
	}

	private String deriveId(String fileName) {
		String base = fileName.replaceAll("\\.ya?ml$", "");
		return base.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
	}

	@SuppressWarnings("unchecked")
	private void showPreview(File file) {
		try (FileReader reader = new FileReader(file)) {
			Object loaded = new Yaml().load(reader);
			if (!(loaded instanceof Map)) {
				previewArea.setText("This file does not look like a valid OpenCollection document.");
				importButton.setEnabled(false);
				return;
			}
			Map<String, Object> root = (Map<String, Object>) loaded;
			Map<String, Object> info = root.get("info") instanceof Map ? (Map<String, Object>) root.get("info") : Map.of();
			Object configObj = root.get("config");
			int environmentCount = 0;
			if (configObj instanceof Map<?, ?> config && config.get("environments") instanceof List<?> envs) {
				environmentCount = envs.size();
			}
			Map<String, Integer> methodCounts = new HashMap<>();
			int[] folderCount = { 0 };
			countItems(root.get("items"), methodCounts, folderCount);

			StringBuilder sb = new StringBuilder();
			sb.append("Collection: ").append(info.getOrDefault("name", "(unnamed)")).append("\n\n");
			sb.append("Environments: ").append(environmentCount).append("\n");
			sb.append("Folders:      ").append(folderCount[0]).append("\n");
			int totalEndpoints = methodCounts.values().stream().mapToInt(Integer::intValue).sum();
			sb.append("Endpoints:    ").append(totalEndpoints).append("\n\n");
			methodCounts.forEach((method, count) -> sb.append(method).append(": ").append(count).append("\n"));
			sb.append("\n");
			String apiId = apiIdField.getText().trim().toUpperCase();
			if (!apiId.isBlank()) {
				sb.append(vault.contains(apiId) ? "This will re-import/update the existing API '" + apiId + "'." : "This will create a new API '" + apiId + "'.");
			}
			previewArea.setText(sb.toString());
			importButton.setEnabled(true);
		} catch (Exception ex) {
			previewArea.setText("Could not read this file: " + ex.getLocalizedMessage());
			importButton.setEnabled(false);
		}
	}

	@SuppressWarnings("unchecked")
	private void countItems(Object itemsObj, Map<String, Integer> methodCounts, int[] folderCount) {
		if (!(itemsObj instanceof List)) {
			return;
		}
		for (Object itemObj : (List<Object>) itemsObj) {
			if (!(itemObj instanceof Map)) {
				continue;
			}
			Map<String, Object> item = (Map<String, Object>) itemObj;
			Object infoObj = item.get("info");
			String type = infoObj instanceof Map ? String.valueOf(((Map<String, Object>) infoObj).get("type")) : String.valueOf(item.get("type"));
			if ("folder".equals(type)) {
				folderCount[0]++;
				countItems(item.get("items"), methodCounts, folderCount);
			} else if ("http".equals(type) && item.get("http") instanceof Map) {
				Map<String, Object> http = (Map<String, Object>) item.get("http");
				String method = String.valueOf(http.get("method"));
				methodCounts.merge(method, 1, Integer::sum);
			}
		}
	}

	private void doImport() {
		String apiId = apiIdField.getText().trim().toUpperCase();
		if (apiId.isBlank()) {
			JOptionPane.showMessageDialog(this, "API ID is required.");
			return;
		}
		File file = new File(fileField.getText());
		try {
			BrunoImportResult result = new BrunoCollectionImporter(vault).importFile(apiId, file);
			StringBuilder sb = new StringBuilder("Import completed.\n\nCreated:\n  ")
					.append(result.getEnvironmentsCreated()).append(" environment(s)\n  ")
					.append(result.getGroupsCreated()).append(" folder(s)\n  ")
					.append(result.getEndpointsCreated()).append(" endpoint(s)\n\nUpdated:\n  ")
					.append(result.getEnvironmentsUpdated()).append(" environment(s)\n  ")
					.append(result.getGroupsUpdated()).append(" folder(s)\n  ")
					.append(result.getEndpointsUpdated()).append(" endpoint(s)");
			if (!result.getUnsupportedAuthTypes().isEmpty()) {
				sb.append("\n\nAuthentication types imported but not executable in this release: ").append(String.join(", ", result.getUnsupportedAuthTypes()));
			}
			JOptionPane.showMessageDialog(this, sb.toString());
			importedApiId = apiId;
			dispose();
		} catch (BroadSQLException ex) {
			JOptionPane.showMessageDialog(this, "ERROR: " + ex.getLocalizedMessage());
		}
	}
}
