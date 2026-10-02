package com.upandcoding.broadsql.controller.shell.scriptlibrary;

/**
 * One catalog-integrity problem found in an asset's metadata at Save time: the metadata
 * {@link #field()} it concerns ({@code instance}, {@code environment}, {@code alias} or {@code status},
 * the same keys {@link ScriptMetadataHeader} uses) so the Editor can focus the matching field, and a
 * user-facing {@link #message()}.
 */
public record MetadataIssue(String field, String message) {
}
