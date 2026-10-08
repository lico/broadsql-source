package com.upandcoding.broadsql.controller.shell.scriptlibrary;

import java.util.List;

/**
 * The result of moving or renaming a Scripts Library folder ({@link ScriptLibraryService#moveFolder},
 * {@link ScriptLibraryService#renameFolder}): the folder's new key and every Script it contained, at its new
 * path, with the same asset id as before (so an editor tab holding one of them keeps representing it).
 *
 * @param newFolderKey   the folder's path relative to the library root after the move
 * @param movedScripts   every Script the folder contained, at its new path
 * @param historyWarning {@code null}, or why the revision history of some moved Scripts could not be updated
 *                       (the files themselves were moved; history catches up the next time they are opened)
 */
public record FolderMoveOutcome(String newFolderKey, List<ScriptAsset> movedScripts, String historyWarning) {
}
