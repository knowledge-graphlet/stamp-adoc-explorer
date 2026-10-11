/*
 * Copyright 2026 knowledge.design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package design.knowledge.stamp.adoc.idea;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

/** Balloons under the plugin's one notification group. */
final class StampNotifications {

    static final String GROUP = "STAMP Annotations";

    private StampNotifications() { }

    static void info(Project project, String title, String content) {
        show(project, title, content, NotificationType.INFORMATION);
    }

    static void warn(Project project, String title, String content) {
        show(project, title, content, NotificationType.WARNING);
    }

    static void error(Project project, String title, String content) {
        show(project, title, content, NotificationType.ERROR);
    }

    private static void show(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
                .createNotification(title, content, type)
                .notify(project);
    }
}
