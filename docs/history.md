# Notification history

MineNotify retains the last 50 enabled notifications in memory, newest first. Timestamps record when the notification is shown, which can be later than arrival if you were outside a world. Each entry includes its source, title, response, and local display time. The history store is private to the Minecraft client and is never sent to server chat or saved to disk. Messages shown through Minecraft chat can appear in Minecraft’s chat log, including response text when previews are enabled. Restarting Minecraft clears the history store. Notifications received while notifications are disabled are discarded and do not enter history.

Press **F8**, then select **History**, or use `/minenotify history`. Select a notification to read its details. **Previous** and **Next** move between list pages or wrapped detail pages. The list updates when new notifications arrive.

The current **Response preview** setting controls response visibility in history. When previews are off, the list and details do not draw response bodies. Bodies remain in memory until newer notifications push them out, history is cleared, or Minecraft exits.

Select **Clear history**, or use `/minenotify clear`, to remove all history entries. Clearing history does not remove messages already shown in Minecraft chat or active popups. **Back to history** returns from details to the list. **Back** returns to the previous screen. History does not pause the game.

Run `./gradlew historyCheck` to verify capacity, ordering, timestamps, snapshot immutability, and clearing. The same check runs in both version builds.
