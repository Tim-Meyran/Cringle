// SPDX-License-Identifier: Apache-2.0

package cringle.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Placeholder for `cringle.project`. Project packages, with blueprints and fabric configs, are built in issue #52;
 * until then applying the plugin does nothing but say so.
 */
public class CringleProjectPlaceholder : Plugin<Project> {
    override fun apply(project: Project) {
        project.logger.info("cringle.project does nothing yet, project packages are built in issue #52")
    }
}
