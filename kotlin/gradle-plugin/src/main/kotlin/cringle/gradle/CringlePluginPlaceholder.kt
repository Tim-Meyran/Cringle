// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class CringlePluginPlaceholder : Plugin<Project> {
    override fun apply(project: Project) {
        project.logger.info("Applying Cringle Gradle plugin placeholder")
    }
}
